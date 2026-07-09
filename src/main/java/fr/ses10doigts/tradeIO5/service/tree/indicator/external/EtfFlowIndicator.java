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
 * Flux ETF quotidien (scraping Farside), étude "indicateurs-macro-externes" §14 item I. Valeur
 * externe sans notion de MarketData ({@code getRequiredData() == 0}), même patron que
 * {@link FearAndGreedIndicator}/{@link StablecoinMarketCapIndicator}. Paramètre supplémentaire
 * {@code asset} ("BTC"/"ETH", "BTC" par défaut) pour choisir la page Farside consultée — le
 * document source demandait explicitement les deux actifs.
 * <p>
 * {@code values} expose {@code "total"} (flux du jour toutes émetteurs confondus) plus le détail
 * par émetteur directement dans la map ({@code "IBIT"}, {@code "FBTC"}, ...) : la liste de clés
 * peut varier dans le temps si Farside ajoute/retire un émetteur, ce n'est pas un problème pour un
 * {@code Map<String,Double>}.
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
