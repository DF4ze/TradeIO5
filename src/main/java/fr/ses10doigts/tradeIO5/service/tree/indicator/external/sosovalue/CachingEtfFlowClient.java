package fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.entity.market.EtfFlowSnapshotEntity;
import fr.ses10doigts.tradeIO5.repository.market.EtfFlowSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * Décorateur cache-aside devant {@link SosoValueEtfFlowClient} (docs/etude-cache-etf-flow-historisation.md) :
 * ETF_FLOW ne change qu'une fois par jour (publication post-clôture US), rappeler l'API à chaque
 * {@code evaluate()} est donc inutile — au plus 1 appel réseau par asset et par jour côté
 * {@link #fetch}. Même patron cache-aside que
 * {@link fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.CachingMarketDataApiClient}
 * pour les candles, appliqué ici à une donnée ponctuelle quotidienne plutôt qu'à une série continue
 * de bougies (pas de notion de "trou"/plage à combler, juste "déjà rafraîchi aujourd'hui ou non").
 * <p>
 * Persiste aussi chaque valeur récupérée dans {@code etf_flow_snapshot}
 * ({@link EtfFlowSnapshotRepository}) — historisation demandée explicitement par Clem (2026-07-16,
 * cf. javadoc {@link EtfFlowSnapshotEntity}), indépendante du service rendu par le cache lui-même :
 * la table grossit d'une ligne par asset et par jour dès qu'un {@link #fetch}/{@link #refresh}
 * réussit, que le résultat soit servi depuis le cache ou fraîchement récupéré.
 * <p>
 * Deux points d'entrée à sémantique différente :
 * <ul>
 *   <li>{@link #fetch} : gated sur "déjà appelé aujourd'hui" (calendrier JVM, via {@code fetchedAt}
 *       du dernier snapshot connu) — c'est celui injecté dans {@code EtfFlowIndicator}, utilisé à
 *       chaque évaluation utilisateur/Strategy. Ne recontacte jamais le réseau plus d'une fois par
 *       jour et par asset, quel que soit le nombre d'évaluations dans la journée.</li>
 *   <li>{@link #refresh} : bypasse le gate, toujours un appel réseau live — réservé à
 *       {@code EtfFlowHistorizationJob} (1x/jour), qui est le point de rafraîchissement délibéré de
 *       la journée et ne doit jamais être court-circuité par un gate pensé pour les appels
 *       utilisateur. Si {@code refresh} tourne avant la publication SoSoValue du jour, {@link #fetch}
 *       sert quand même la dernière valeur connue le reste de la journée (pas d'exception, pas de
 *       trou immédiat) ; le risque résiduel (un jour durablement non couvert si SoSoValue est en
 *       panne plus de 24h) est accepté sans correction automatique — même choix assumé que documenté
 *       sur {@code CandleEntity} ("correction tardive d'un exchange, risque assumé, non résolu").</li>
 * </ul>
 * Ne cache jamais un échec (réponse invalide) : {@code fetch}/{@code refresh} retentent le réseau à
 * chaque appel tant qu'aucune réponse valide n'a été obtenue ce jour, pour ne pas figer une panne
 * transitoire pour le reste de la journée.
 */
public class CachingEtfFlowClient implements EtfFlowProvider {

    private static final Logger log = LoggerFactory.getLogger(CachingEtfFlowClient.class);

    private final EtfFlowProvider delegate;
    private final EtfFlowSnapshotRepository repository;
    private final DomainClock clock;

    public CachingEtfFlowClient(EtfFlowProvider delegate, EtfFlowSnapshotRepository repository, DomainClock clock) {
        this.delegate = delegate;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public EtfFlowResponse fetch(ApiCredentialDTO credential, EtfFlowAsset asset) {
        Optional<EtfFlowSnapshotEntity> latest = repository.findTopByAssetOrderByDateDesc(asset);

        if (latest.isPresent() && isFetchedToday(latest.get())) {
            log.info("ETF_FLOW ({}) cache HIT : déjà rafraîchi aujourd'hui (date={}, fetchedAt={}), aucun appel réseau.",
                    asset, latest.get().getDate(), latest.get().getFetchedAt());
            return toResponse(latest.get());
        }

        log.info("ETF_FLOW ({}) cache MISS : pas encore rafraîchi aujourd'hui, appel réseau SoSoValue.", asset);
        return refresh(credential, asset);
    }

    /**
     * Toujours un appel réseau live, sans passer par le gate quotidien de {@link #fetch}. Réservé à
     * {@code EtfFlowHistorizationJob} — voir javadoc de classe.
     */
    public EtfFlowResponse refresh(ApiCredentialDTO credential, EtfFlowAsset asset) {
        EtfFlowResponse response = delegate.fetch(credential, asset);

        if (response.isValid() && response.getDate() != null && response.getTotal() != null) {
            persist(asset, response);
        }

        return response;
    }

    private boolean isFetchedToday(EtfFlowSnapshotEntity snapshot) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.ofInstant(clock.now(), zone);
        LocalDate fetchedDay = LocalDate.ofInstant(snapshot.getFetchedAt(), zone);
        return fetchedDay.isEqual(today);
    }

    /**
     * Upsert par {@code (asset, date)} : relit d'abord la ligne existante pour ce couple (met à jour
     * {@code totalNetInflow}/{@code fetchedAt} plutôt que de dupliquer si SoSoValue republie la même
     * date, ex. correction tardive), sinon crée une nouvelle ligne. Filet de sécurité sur violation
     * de contrainte unique en écriture concurrente, même garde-fou que
     * {@code CachingMarketDataApiClient#persistRowByRow} : la ligne existe déjà (créée entre-temps
     * par un appel concurrent), ignoré sans remonter d'exception.
     */
    private void persist(EtfFlowAsset asset, EtfFlowResponse response) {
        try {
            EtfFlowSnapshotEntity entity = repository.findByAssetAndDate(asset, response.getDate())
                    .orElseGet(() -> EtfFlowSnapshotEntity.builder().asset(asset).date(response.getDate()).build());
            entity.setTotalNetInflow(response.getTotal());
            entity.setFetchedAt(clock.now());
            repository.save(entity);
        } catch (DataIntegrityViolationException e) {
            log.debug("ETF_FLOW ({}) snapshot déjà persisté par un appel concurrent, ignoré : {}", asset, e.getMessage());
        }
    }

    private static EtfFlowResponse toResponse(EtfFlowSnapshotEntity entity) {
        return EtfFlowResponse.builder()
                .valid(true)
                .date(entity.getDate())
                .total(entity.getTotalNetInflow())
                // Même choix délibéré que SosoValueEtfFlowClient : jamais de détail par émetteur.
                .byIssuer(Map.of())
                .build();
    }
}
