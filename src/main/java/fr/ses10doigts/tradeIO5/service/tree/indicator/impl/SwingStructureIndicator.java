package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.trend.SwingPivot;
import fr.ses10doigts.tradeIO5.service.tree.trend.SwingStructureCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.SwingStructureRegimeScore;
import fr.ses10doigts.tradeIO5.service.tree.trend.SwingStructureState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptateur fin autour de {@link SwingStructureCalculator} (couche calculateur, service pur).
 * N'implémente pas l'algorithme de détection de pivots lui-même : appelle le calculateur et encode
 * le {@link SwingStructureState} résultant en {@link IndicatorResult}.
 * <p>
 * Plus de dépendance ATR depuis la réécriture du 2026-09-19 de {@link SwingStructureCalculator} (à
 * la demande de Clem : l'ancien filtre {@code atrMultiplier x ATR} confirmait les pivots avec un
 * délai jugé inexploitable en prod, remplacé par une confirmation dès la bougie de retournement,
 * sans seuil ni paramètre — cf. javadoc de {@link SwingStructureCalculator}). Cette classe n'a donc
 * plus besoin d'implémenter {@code DependentIndicator} : elle se contente des bougies du contexte.
 */
@Component
public class SwingStructureIndicator implements Indicator {

    private final Logger logger = LoggerFactory.getLogger(SwingStructureIndicator.class);

    public static final String V_LAST_SWING_HIGH = "lastSwingHigh";
    public static final String V_LAST_SWING_HIGH_EPOCH_DAY = "lastSwingHighEpochDay";
    public static final String V_LAST_SWING_LOW = "lastSwingLow";
    public static final String V_LAST_SWING_LOW_EPOCH_DAY = "lastSwingLowEpochDay";
    public static final String V_PREVIOUS_SWING_HIGH = "previousSwingHigh";
    public static final String V_PREVIOUS_SWING_HIGH_EPOCH_DAY = "previousSwingHighEpochDay";
    public static final String V_PREVIOUS_SWING_LOW = "previousSwingLow";
    public static final String V_PREVIOUS_SWING_LOW_EPOCH_DAY = "previousSwingLowEpochDay";
    public static final String V_SR_PREFIX = "sr";

    @Override
    public IndicatorType getType() {
        return IndicatorType.SWING_STRUCTURE;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        // Aucun warmup requis par l'algorithme (pas d'ATR) : un minimum indicatif pour qu'un
        // premier pivot ait une chance de se confirmer (candidat + bougie de retournement).
        return 2;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of();
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        MarketDataset series = context.marketDataset();
        List<MarketData> candles = series.getMarketDatas();

        if (candles == null || candles.isEmpty()) {
            logger.error("Invalid context : no MarketData for SWING_STRUCTURE");
            return IndicatorResult.invalid();
        }

        SwingStructureState state = new SwingStructureCalculator().compute(candles);

        double value = SwingStructureRegimeScore.toScore(state.regime());

        Map<String, Double> values = new HashMap<>();
        putPivot(values, V_LAST_SWING_HIGH, V_LAST_SWING_HIGH_EPOCH_DAY, state.lastSwingHigh());
        putPivot(values, V_LAST_SWING_LOW, V_LAST_SWING_LOW_EPOCH_DAY, state.lastSwingLow());
        putPivot(values, V_PREVIOUS_SWING_HIGH, V_PREVIOUS_SWING_HIGH_EPOCH_DAY, state.previousSwingHigh());
        putPivot(values, V_PREVIOUS_SWING_LOW, V_PREVIOUS_SWING_LOW_EPOCH_DAY, state.previousSwingLow());

        List<BigDecimal> sr = state.nearestSrLevels();
        for (int i = 0; i < sr.size() && i < 4; i++) {
            values.put(V_SR_PREFIX + (i + 1), sr.get(i).doubleValue());
        }

        logger.info("{} indicator on TF {} returns regime={}", getType(), series.getTimeFrame(), state.regime());
        logger.debug("{} state detail : {}", getType(), state);

        return IndicatorResult.builder()
                .value(value)
                .values(values)
                .valid(true)
                .build();
    }

    private void putPivot(Map<String, Double> values, String priceKey, String epochDayKey, SwingPivot pivot) {
        if (pivot == null) {
            return;
        }
        values.put(priceKey, pivot.price().doubleValue());
        values.put(epochDayKey, (double) toEpochDay(pivot.timestamp()));
    }

    private long toEpochDay(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

}
