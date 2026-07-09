package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Indicator External - EtfFlowIndicator")
class EtfFlowIndicatorTest {

    private static final IndicatorParameters PARAMS = IndicatorParameters.builder()
            .indicatorType(IndicatorType.ETF_FLOW)
            .numerics(Map.of())
            .strings(Map.of())
            .booleans(Map.of())
            .credential(null)
            .build();

    @Test
    @DisplayName("compute() expose 'total' et le détail par émetteur quand le provider renvoie une réponse valide")
    void compute_exposesTotalAndPerIssuer_whenValid() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.builder()
                .valid(true)
                .date(LocalDate.of(2026, 7, 7))
                .byIssuer(Map.of("IBIT", 54.8, "FBTC", -24.9))
                .total(21.5)
                .build();

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);

        IndicatorResult result = indicator.compute(null, PARAMS);

        assertTrue(result.isValid());
        assertEquals(21.5, result.getValue());
        assertEquals(21.5, result.getValues().get(EtfFlowIndicator.V_TOTAL));
        assertEquals(54.8, result.getValues().get("IBIT"));
        assertEquals(-24.9, result.getValues().get("FBTC"));
    }

    @Test
    @DisplayName("compute() par défaut interroge l'actif BTC quand le paramètre 'asset' est absent")
    void compute_defaultsToBtc_whenAssetParameterMissing() {
        EtfFlowAsset[] seenAsset = new EtfFlowAsset[1];
        EtfFlowProvider provider = (credential, asset) -> {
            seenAsset[0] = asset;
            return EtfFlowResponse.builder().valid(true).byIssuer(Map.of()).total(0.0).build();
        };

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);
        indicator.compute(null, PARAMS);

        assertEquals(EtfFlowAsset.BTC, seenAsset[0]);
    }

    @Test
    @DisplayName("compute() interroge ETH quand le paramètre 'asset' vaut 'ETH'")
    void compute_usesEth_whenAssetParameterIsEth() {
        EtfFlowAsset[] seenAsset = new EtfFlowAsset[1];
        EtfFlowProvider provider = (credential, asset) -> {
            seenAsset[0] = asset;
            return EtfFlowResponse.builder().valid(true).byIssuer(Map.of()).total(0.0).build();
        };

        IndicatorParameters ethParams = IndicatorParameters.builder()
                .indicatorType(IndicatorType.ETF_FLOW)
                .numerics(Map.of())
                .strings(Map.of(EtfFlowIndicator.P_ASSET, "ETH"))
                .booleans(Map.of())
                .credential(null)
                .build();

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);
        indicator.compute(null, ethParams);

        assertEquals(EtfFlowAsset.ETH, seenAsset[0]);
    }

    @Test
    @DisplayName("compute() retombe sur invalid() quand le provider échoue")
    void compute_returnsInvalid_whenProviderFails() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.invalid();
        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);

        IndicatorResult result = indicator.compute(null, PARAMS);

        assertFalse(result.isValid());
    }
}
