package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.EtfFlowResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowAsset;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Horloge fixe réutilisée par les tests qui n'ont pas besoin d'une fraîcheur précise --
     *  seuls les tests dédiés à ageInDays/dateEpochDay choisissent une date de référence
     *  significative par rapport à la date de la réponse. */
    private static IndicatorContext contextAt(String isoInstant) {
        return new IndicatorContext(null, null, null, null, () -> Instant.parse(isoInstant));
    }

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

        IndicatorResult result = indicator.compute(contextAt("2026-07-07T18:00:00Z"), PARAMS);

        assertTrue(result.isValid());
        assertEquals(21.5, result.getValue());
        assertEquals(21.5, result.getValues().get(EtfFlowIndicator.V_TOTAL));
        assertEquals(54.8, result.getValues().get("IBIT"));
        assertEquals(-24.9, result.getValues().get("FBTC"));
    }

    @Test
    @DisplayName("compute() expose ageInDays=0 quand la donnée est datée d'aujourd'hui (même jour UTC que l'horloge)")
    void compute_exposesZeroAge_whenDataIsFromToday() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.builder()
                .valid(true)
                .date(LocalDate.of(2026, 7, 16))
                .byIssuer(Map.of())
                .total(100.0)
                .build();

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);
        IndicatorResult result = indicator.compute(contextAt("2026-07-16T10:00:00Z"), PARAMS);

        assertTrue(result.isValid());
        assertEquals(0.0, result.getValues().get(EtfFlowIndicator.V_AGE_IN_DAYS));
        assertEquals(LocalDate.of(2026, 7, 16).toEpochDay(), result.getValues().get(EtfFlowIndicator.V_DATE_EPOCH_DAY));
    }

    @Test
    @DisplayName("compute() expose ageInDays=1 quand la donnée date de la veille -- cas réel observé le "
            + "2026-07-16 (SoSoValue en retard d'un jour, cf. javadoc de classe)")
    void compute_exposesOneDayAge_whenDataIsFromYesterday() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.builder()
                .valid(true)
                .date(LocalDate.of(2026, 7, 15))
                .byIssuer(Map.of())
                .total(107_804_553.8)
                .build();

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);
        // 19h58 heure locale Paris (UTC+2 l'été) le 16/07 -> 17h58 UTC, même horaire que le run réel
        // documenté dans la javadoc de classe.
        IndicatorResult result = indicator.compute(contextAt("2026-07-16T17:58:00Z"), PARAMS);

        assertTrue(result.isValid());
        assertEquals(1.0, result.getValues().get(EtfFlowIndicator.V_AGE_IN_DAYS));
    }

    @Test
    @DisplayName("compute() n'expose ni ageInDays ni dateEpochDay quand la réponse n'a pas de date "
            + "(dégrade proprement, pas d'exception)")
    void compute_omitsFreshnessFields_whenDateMissing() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.builder()
                .valid(true)
                .byIssuer(Map.of())
                .total(0.0)
                .build();

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);
        IndicatorResult result = indicator.compute(contextAt("2026-07-16T10:00:00Z"), PARAMS);

        assertTrue(result.isValid());
        assertNull(result.getValues().get(EtfFlowIndicator.V_AGE_IN_DAYS));
        assertNull(result.getValues().get(EtfFlowIndicator.V_DATE_EPOCH_DAY));
    }

    @Test
    @DisplayName("compute() n'expose pas la fraîcheur quand context/clock sont absents (compat. tests existants)")
    void compute_omitsFreshnessFields_whenContextIsNull() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.builder()
                .valid(true)
                .date(LocalDate.of(2026, 7, 7))
                .byIssuer(Map.of())
                .total(21.5)
                .build();

        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);
        IndicatorResult result = indicator.compute(null, PARAMS);

        assertTrue(result.isValid());
        assertEquals(21.5, result.getValue());
        assertNull(result.getValues().get(EtfFlowIndicator.V_AGE_IN_DAYS));
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
        indicator.compute(contextAt("2026-07-16T10:00:00Z"), PARAMS);

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
        indicator.compute(contextAt("2026-07-16T10:00:00Z"), ethParams);

        assertEquals(EtfFlowAsset.ETH, seenAsset[0]);
    }

    @Test
    @DisplayName("compute() retombe sur invalid() quand le provider échoue")
    void compute_returnsInvalid_whenProviderFails() {
        EtfFlowProvider provider = (credential, asset) -> EtfFlowResponse.invalid();
        EtfFlowIndicator indicator = new EtfFlowIndicator(provider);

        IndicatorResult result = indicator.compute(contextAt("2026-07-16T10:00:00Z"), PARAMS);

        assertFalse(result.isValid());
    }
}
