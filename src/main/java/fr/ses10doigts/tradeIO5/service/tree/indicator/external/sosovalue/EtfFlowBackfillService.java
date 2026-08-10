package fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.repository.market.EtfFlowSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorCredentialResolver;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.FarsideEtfFlowClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backfill historique ETF_FLOW à la demande (docs/etudes/etude-cache-etf-flow-historisation.md, addendum
 * backfill) : demande explicite de Clem le 2026-07-16 ("je voudrais pouvoir cacher en DB la
 * totalité des data que contient sosovalue") après avoir constaté que le cache-aside quotidien
 * ({@link CachingEtfFlowClient#fetch}) ne construit l'historique qu'à raison d'une ligne par jour —
 * bien trop lent pour amorcer une calibration de {@code EtfFlowConfidenceStrategy}.
 * <p>
 * <b>SoSoValue seul ne suffit pas</b> : {@code summary-history} plafonne en pratique à ~1 mois
 * d'historique par appel, {@code limit=300} documenté jamais atteignable (constat empirique du
 * 2026-07-17, confirmé aussi sur {@code /etfs/{ticker}/history} — "most recent 1 month" documenté
 * cette fois). Depuis le 2026-07-17, ce service combine donc deux sources :
 * <ul>
 *   <li><b>Farside</b> ({@link FarsideEtfFlowClient#fetchHistory}, scraping HTML de la page
 *       "all data") : historique profond, du lancement des ETF BTC (11 jan. 2024) à ~1 mois avant
 *       aujourd'hui. Réactivé uniquement pour cet usage ponctuel — {@code IndicatorCredentialResolver#resolve}
 *       ne route toujours {@code ETF_FLOW} que vers SOSOVALUE en {@code fetch()} live.</li>
 *   <li><b>SoSoValue</b> ({@link SosoValueEtfFlowClient#fetchHistory}) : le dernier mois, source
 *       "officielle" désormais en production. Appelé <i>après</i> Farside pour avoir le dernier mot
 *       sur les dates en commun (upsert = simple réécriture, pas de doublon grâce à la contrainte
 *       unique {@code (asset, date)}).</li>
 * </ul>
 * Chaque source est indépendamment optionnelle : si une seule credential est résolue, le backfill
 * se poursuit avec celle-là seule plutôt que d'échouer entièrement.
 * <p>
 * <b>Bug d'unité corrigé le 2026-08-10</b> (repéré le 2026-07-17, cf. {@code
 * docs/suivi/etat-des-lieux-indicateurs-strategies-opinions.md}) : Farside exprime ses flux en
 * millions USD ({@code 655.3} = 655,3 M$) tandis que SoSoValue renvoie du USD brut ({@code
 * -55066297.0}, cf. javadoc {@link SosoValueEtfFlowClient}). Les deux sources écrivaient
 * auparavant dans la même colonne {@code etf_flow_snapshot.total_net_inflow} sans conversion,
 * mélangeant les échelles selon la source qui avait "eu le dernier mot" sur une date donnée.
 * Corrigé ici : toute ligne Farside est convertie en USD brut ({@link #toRawUsd}) avant
 * d'atteindre {@link CachingEtfFlowClient#upsert} — SoSoValue n'a besoin d'aucune conversion,
 * déjà en USD brut. <b>Les lignes historiques déjà persistées avant ce correctif restent à
 * l'ancienne échelle</b> tant que {@code POST /api/admin/etf-flow/backfill} n'est pas rejoué (il
 * réécrit les lignes Farside avec la conversion correcte, upsert idempotent par {@code (asset,
 * date)}) — à faire une fois ce correctif déployé.
 * <p>
 * <b>Déclenchement manuel uniquement</b> (via {@code EtfFlowAdminController}), jamais automatique
 * au démarrage — même principe que {@link fr.ses10doigts.tradeIO5.configuration.initializer.TransactionSyncInitializer}
 * (retiré de l'{@code ApplicationRunner} le 2026-07-09, décision Clem : ne pas faire de travail
 * réseau/DB lourd et non sollicité à chaque boot). Idempotent par construction : réutilise
 * {@link CachingEtfFlowClient#upsert}, une ligne déjà connue est simplement réécrite à l'identique,
 * relancer ce backfill plusieurs fois ne duplique jamais rien (contrainte unique {@code (asset, date)}).
 */
@Service
@RequiredArgsConstructor
public class EtfFlowBackfillService {

    private static final Logger logger = LoggerFactory.getLogger(EtfFlowBackfillService.class);

    /** Plafond dur de l'API SoSoValue (cf. javadoc de classe) — pas un choix arbitraire. */
    static final int BACKFILL_LIMIT = 300;

    private final SosoValueEtfFlowClient sosoValueEtfFlowClient;
    private final FarsideEtfFlowClient farsideEtfFlowClient;
    private final CachingEtfFlowClient cachingEtfFlowClient;
    private final EtfFlowSnapshotRepository repository;
    private final IndicatorCredentialResolver credentialResolver;

    /**
     * Backfill BTC + ETH, Farside puis SoSoValue (cf. javadoc de classe). Isolation par asset et
     * par source (même principe que
     * {@code MediaWatchIngestionJob#pollActiveSources}/{@code EtfFlowHistorizationJob#refreshDailySnapshots}) :
     * un échec sur une source/un asset n'empêche pas la tentative sur les autres. Retourne, par
     * asset, le nombre total d'opérations d'upsert effectuées toutes sources confondues (peut
     * dépasser le nombre de lignes distinctes en base si les deux sources couvrent une même date —
     * voir le log détaillé pour le compte distinct via {@code EtfFlowSnapshotRepository#countByAsset}).
     */
    public Map<EtfFlowAsset, Integer> backfillAll() {
        ApiCredentialDTO sosoValueCredential = credentialResolver.resolve(IndicatorType.ETF_FLOW);
        ApiCredentialDTO farsideCredential = credentialResolver.resolveWebProvider(WebProviderCode.FARSIDE);

        if (sosoValueCredential == null && farsideCredential == null) {
            logger.warn("EtfFlowBackfillService: aucune credential (SOSOVALUE ni FARSIDE) résolue, backfill ignoré.");
            return Map.of();
        }

        Map<EtfFlowAsset, Integer> upsertedByAsset = new EnumMap<>(EtfFlowAsset.class);
        for (EtfFlowAsset asset : EtfFlowAsset.values()) {
            try {
                upsertedByAsset.put(asset, backfillAsset(farsideCredential, sosoValueCredential, asset));
            } catch (Exception e) {
                logger.error("EtfFlowBackfillService: échec du backfill pour {}", asset, e);
                upsertedByAsset.put(asset, 0);
            }
        }
        return upsertedByAsset;
    }

    private int backfillAsset(ApiCredentialDTO farsideCredential, ApiCredentialDTO sosoValueCredential, EtfFlowAsset asset) {
        int upserted = 0;

        if (farsideCredential != null) {
            try {
                List<EtfFlowResponse> farsideHistoryRawUsd = toRawUsd(farsideEtfFlowClient.fetchHistory(farsideCredential, asset));
                upserted += upsertHistory(asset, farsideHistoryRawUsd, "FARSIDE");
            } catch (Exception e) {
                logger.error("EtfFlowBackfillService: échec Farside pour {}", asset, e);
            }
        }

        if (sosoValueCredential != null) {
            try {
                upserted += upsertHistory(asset,
                        sosoValueEtfFlowClient.fetchHistory(sosoValueCredential, asset, BACKFILL_LIMIT), "SOSOVALUE");
            } catch (Exception e) {
                logger.error("EtfFlowBackfillService: échec SoSoValue pour {}", asset, e);
            }
        }

        logger.info("EtfFlowBackfillService: {} opération(s) d'upsert au total pour {}, {} ligne(s) distincte(s) "
                        + "en base désormais.",
                upserted, asset, repository.countByAsset(asset));
        return upserted;
    }

    /** Farside publie ses flux en millions USD ({@code 655.3} = 655,3 M$) — cf. javadoc de classe
     *  et {@link SosoValueEtfFlowClient} pour le détail du désaccord d'unité entre les deux sources.
     *  Convertit chaque ligne en USD brut (même échelle que SoSoValue) avant persistance, sans
     *  muter les objets d'origine (immutabilité par reconstruction via {@code builder()}). */
    private static List<EtfFlowResponse> toRawUsd(List<EtfFlowResponse> farsideHistoryMillionsUsd) {
        List<EtfFlowResponse> converted = new ArrayList<>(farsideHistoryMillionsUsd.size());
        for (EtfFlowResponse row : farsideHistoryMillionsUsd) {
            converted.add(EtfFlowResponse.builder()
                    .valid(row.isValid())
                    .date(row.getDate())
                    .total(toRawUsd(row.getTotal()))
                    .byIssuer(toRawUsd(row.getByIssuer()))
                    .build());
        }
        return converted;
    }

    private static Double toRawUsd(Double millionsUsd) {
        return millionsUsd == null ? null : millionsUsd * 1_000_000d;
    }

    private static Map<String, Double> toRawUsd(Map<String, Double> byIssuerMillionsUsd) {
        if (byIssuerMillionsUsd == null || byIssuerMillionsUsd.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : byIssuerMillionsUsd.entrySet()) {
            converted.put(entry.getKey(), toRawUsd(entry.getValue()));
        }
        return converted;
    }

    private int upsertHistory(EtfFlowAsset asset, List<EtfFlowResponse> history, String sourceLabel) {
        int upserted = 0;
        LocalDate min = null;
        LocalDate max = null;
        for (EtfFlowResponse row : history) {
            if (row.getDate() == null || row.getTotal() == null) {
                continue;
            }
            cachingEtfFlowClient.upsert(asset, row);
            upserted++;
            if (min == null || row.getDate().isBefore(min)) {
                min = row.getDate();
            }
            if (max == null || row.getDate().isAfter(max)) {
                max = row.getDate();
            }
        }

        logger.info("EtfFlowBackfillService [{}]: {} ligne(s) rétro-remplie(s) pour {} (couverture {} -> {}).",
                sourceLabel, upserted, asset, min, max);
        return upserted;
    }
}
