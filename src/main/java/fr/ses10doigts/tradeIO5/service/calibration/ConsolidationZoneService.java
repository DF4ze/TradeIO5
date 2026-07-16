package fr.ses10doigts.tradeIO5.service.calibration;

import fr.ses10doigts.tradeIO5.service.calibration.dto.CalibrationZonesResponse;
import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Orchestration : fetch D1 (via {@link BinanceDailyCandleFetcher}) -> détection de zones
 * "consolidation" + dédup + métriques (via {@link ConsolidationZoneDetector}) -> régime ADX (via
 * {@link RegimeCalculator}) -> assemblage de la réponse JSON pour {@code zone_view_v2.html}.
 * <p>
 * Paramètres par défaut identiques à {@code tools/calibration/export_zones_v2.py} (même
 * configuration que celle visuellement validée par Clem sur le snapshot du 2026-07-09) : ne pas
 * les modifier sans re-valider visuellement, ce ne sont pas des valeurs arbitraires.
 */
@Service
public class ConsolidationZoneService {

    private static final int ATR_PERIOD = 14;
    private static final int HORIZON = 5;
    private static final int MIN_WINDOW = 10;
    private static final int MAX_WINDOW = 40;
    private static final int WINDOW_STEP = 4;
    private static final int TRIM_PCT = 10;
    private static final double MAX_RANGE_ATR_MULT = 2.5;
    private static final int BREAKOUT_CONFIRM_CANDLES = 3;
    private static final double BREAKOUT_MARGIN_ATR_MULT = 0.5;
    private static final double DEDUP_MAX_GAP_ATR_MULT = 1.0;
    private static final int DEDUP_MAX_CANDLES_APART_DAYS = 15;

    private final BinanceDailyCandleFetcher candleFetcher;

    public ConsolidationZoneService(BinanceDailyCandleFetcher candleFetcher) {
        this.candleFetcher = candleFetcher;
    }

    public CalibrationZonesResponse getZones(String symbol) {
        List<DailyCandle> candles = candleFetcher.fetchFullHistory(symbol);

        double[] atrSeries = ConsolidationZoneDetector.wilderAtr(candles, ATR_PERIOD);
        List<ConsolidationZoneDetector.Band> rawBands = ConsolidationZoneDetector.detectConsolidationBands(
                candles, candles.size(), atrSeries, MIN_WINDOW, MAX_WINDOW, WINDOW_STEP, TRIM_PCT,
                MAX_RANGE_ATR_MULT, BREAKOUT_CONFIRM_CANDLES, BREAKOUT_MARGIN_ATR_MULT);
        List<ConsolidationZoneDetector.Band> dedupedBands = ConsolidationZoneDetector.dedupZones(
                rawBands, candles, atrSeries, DEDUP_MAX_GAP_ATR_MULT, DEDUP_MAX_CANDLES_APART_DAYS);
        List<ConsolidationZoneDetector.ZoneView> zoneViews = ConsolidationZoneDetector.buildZoneViews(
                dedupedBands, candles, atrSeries, HORIZON);

        double[] adx = RegimeCalculator.computeAdx(candles, RegimeCalculator.ADX_PERIOD);
        List<RegimeCalculator.RegimeSegment> segments = RegimeCalculator.regimeSegments(candles, adx);

        List<CalibrationZonesResponse.CandlePoint> candlePoints = candles.stream()
                .map(c -> new CalibrationZonesResponse.CandlePoint(c.date().toString(), c.open(), c.high(),
                        c.low(), c.close()))
                .toList();
        List<CalibrationZonesResponse.ZonePayload> zonePayloads = zoneViews.stream()
                .map(CalibrationZonesResponse.ZonePayload::from)
                .toList();
        List<CalibrationZonesResponse.RegimeSegmentPayload> regimePayloads = segments.stream()
                .map(CalibrationZonesResponse.RegimeSegmentPayload::from)
                .toList();

        return new CalibrationZonesResponse(candlePoints, zonePayloads, regimePayloads, Instant.now().toString());
    }
}
