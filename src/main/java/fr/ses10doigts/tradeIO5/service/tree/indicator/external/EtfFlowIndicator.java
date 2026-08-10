package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flux ETF quotidien, étude "indicateurs-macro-externes" §14 item I. Valeur externe sans notion
 * de MarketData ({@code getRequiredData() == 0}), même patron que
 * {@link FearAndGreedIndicator}/{@link StablecoinMarketCapIndicator}. Paramètre supplémentaire
 * {@code asset} ("BTC"/"ETH", "BTC" par défaut) — le document source demandait explicitement les
 * deux actifs.
 * <p>
 * Source : {@code SosoValueEtfFlowClient} (API REST officielle) depuis le 2026-07-16, remplace le
 * scraping HTML Farside d'origine (cf. docs/etudes/etude-sourcing-etf-flow-alternative-farside.md).
 * {@code values} expose {@code "total"} (flux net du jour toutes émetteurs confondus, en USD brut
 * — voir avertissement d'unité dans {@code SosoValueEtfFlowClient}) plus, le cas échéant, le détail
 * par émetteur directement dans la map ({@code "IBIT"}, {@code "FBTC"}, ...) : avec le client
 * SoSoValue actuel cette map est toujours vide (choix délibéré, cf. javadoc du client), la liste de
 * clés reste néanmoins un {@code Map<String,Double>} ouvert pour ne pas casser le contrat si un
 * futur client la peuple à nouveau.
 * <p>
 * <b>Fraîcheur de la donnée (ajouté le 2026-07-18, demande explicite de Clem)</b> : ETF_FLOW ne
 * parle jamais du "moment présent" — {@code response.getDate()} est la dernière journée publiée par
 * la source (SoSoValue/Farside), constatée en pratique avec au moins un jour de retard (vérifié
 * empiriquement le 2026-07-16 : un appel réseau à 19h58 heure locale n'a renvoyé que la donnée du
 * jour précédent). Rien en amont ({@code CachingEtfFlowClient#fetch}, gaté sur "appel déjà tenté
 * aujourd'hui", pas sur "donnée datée d'aujourd'hui") ne garantit que la valeur retournée décrit
 * réellement le jour courant. {@code values} expose donc en plus {@code "ageInDays"} (nombre de
 * jours entre {@code response.getDate()} et aujourd'hui, calculé via {@code context.clock()} — 0 si
 * la donnée est bien datée d'aujourd'hui, jamais négatif en pratique) et
 * {@code "dateEpochDay"} (la date elle-même, encodée en jours depuis l'epoch faute de pouvoir
 * porter un {@code LocalDate} dans un {@code Map<String,Double>} — reconstructible via
 * {@code LocalDate.ofEpochDay(...)}) pour que tout consommateur (qualifieur d'intensité à venir,
 * {@code EtfFlowConfidenceStrategy}, etc.) puisse moduler son interprétation selon la fraîcheur
 * réelle, plutôt que de supposer implicitement "cette valeur parle d'aujourd'hui". Uniquement
 * calculé si {@code response.getDate()} est renseignée (comme aujourd'hui dans certains tests qui ne
 * fixent pas cette valeur) — absence silencieuse plutôt qu'exception, même tolérance que le reste de
 * cette méthode. Aucune logique de modulation elle-même ajoutée ici : ce lot expose seulement le
 * fait, la décision de comment s'en servir reste hors scope (cf. échange avec Clem sur le risque de
 * perdre l'essence d'un indicateur en le forçant dans une formule combinée prématurément).
 */
@Component
public class EtfFlowIndicator implements Indicator {

    public static final String P_ASSET = "asset";
    public static final String V_TOTAL = "total";
    public static final String V_AGE_IN_DAYS = "ageInDays";
    public static final String V_DATE_EPOCH_DAY = "dateEpochDay";

    private final EtfFlowProvider provider;

    public EtfFlowIndicator(EtfFlowProvider provider) {
        this.provider = provider;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.ETF_FLOW;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        return 0;
    }

    @Override
    public List<String> getParametersNames() {
        // "asset" est optionnel (défaut "BTC", cf. EtfFlowAsset.fromParameter) : volontairement
        // absent d'ici pour ne pas être exigé par Indicator.checkParameters.
        return List.of(AbstractExternalIndicator.P_CREDENTIAL);
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        ApiCredentialDTO credential = parameters.getCredential();
        EtfFlowAsset asset = EtfFlowAsset.fromParameter(rawAsset(parameters));

        EtfFlowResponse response = provider.fetch(credential, asset);

        // response.isValid() couvre déjà les pannes réseau/parsing (cf. FarsideEtfFlowClient) ;
        // le check de nullité supplémentaire couvre le cas (normalement impossible en pratique,
        // mais pas garanti par le type) d'une réponse "valid" sans byIssuer/total exploitables.
        if (!response.isValid() || response.getByIssuer() == null || response.getTotal() == null) {
            return IndicatorResult.invalid();
        }

        Map<String, Double> values = new HashMap<>(response.getByIssuer());
        values.put(V_TOTAL, response.getTotal());

        // Fraîcheur (cf. javadoc de classe) : seulement si le provider a renseigné une date -- ne
        // fait jamais échouer compute() si elle est absente (certains contextes de test ne la
        // fixent pas), pas plus que context/context.clock() n'est requis pour le reste de cette
        // méthode aujourd'hui.
        if (response.getDate() != null && context != null && context.clock() != null) {
            LocalDate today = LocalDate.ofInstant(context.clock().now(), ZoneOffset.UTC);
            long ageInDays = ChronoUnit.DAYS.between(response.getDate(), today);
            values.put(V_AGE_IN_DAYS, (double) ageInDays);
            values.put(V_DATE_EPOCH_DAY, (double) response.getDate().toEpochDay());
        }

        return IndicatorResult.builder()
                .valid(true)
                .value(response.getTotal())
                .values(values)
                .build();
    }

    private String rawAsset(IndicatorParameters parameters) {
        return parameters.getStrings() != null ? parameters.getString(P_ASSET) : null;
    }
}
