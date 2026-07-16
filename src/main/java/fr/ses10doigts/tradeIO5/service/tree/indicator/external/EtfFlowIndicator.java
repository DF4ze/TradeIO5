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
 * scraping HTML Farside d'origine (cf. docs/etude-sourcing-etf-flow-alternative-farside.md).
 * {@code values} expose {@code "total"} (flux net du jour toutes émetteurs confondus, en USD brut
 * — voir avertissement d'unité dans {@code SosoValueEtfFlowClient}) plus, le cas échéant, le détail
 * par émetteur directement dans la map ({@code "IBIT"}, {@code "FBTC"}, ...) : avec le client
 * SoSoValue actuel cette map est toujours vide (choix délibéré, cf. javadoc du client), la liste de
 * clés reste néanmoins un {@code Map<String,Double>} ouvert pour ne pas casser le contrat si un
 * futur client la peuple à nouveau.
 */
@Component
public class EtfFlowIndicator implements Indicator {

    public static final String P_ASSET = "asset";
    public static final String V_TOTAL = "total";

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
