package fr.ses10doigts.tradeIO5.service.calibration.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import fr.ses10doigts.tradeIO5.service.calibration.ConsolidationZoneDetector;
import fr.ses10doigts.tradeIO5.service.calibration.RegimeCalculator;

import java.util.List;

/**
 * Corps de réponse de {@code GET /api/calibration/zones}. Les noms de champs sont en snake_case
 * ({@link JsonNaming}) pour matcher exactement le format déjà produit par
 * {@code tools/calibration/export_zones_v2.py} (band_low, anchor_date, n_touches, ...) — la page
 * {@code tools/calibration/zone_view_v2.html} n'a donc besoin d'aucune adaptation de son parsing
 * JSON, seulement de pointer son fetch vers ce nouvel endpoint au lieu du snapshot statique.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CalibrationZonesResponse(List<CandlePoint> candles, List<ZonePayload> zones,
                                        List<RegimeSegmentPayload> regimeSegments, String generatedAt) {

    public record CandlePoint(String time, double open, double high, double low, double close) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TouchPayload(String date, double price, double reaction) {
        public static TouchPayload from(ConsolidationZoneDetector.TouchView v) {
            return new TouchPayload(v.date(), v.price(), v.reaction());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ZonePayload(double bandLow, double bandHigh, String anchorDate, double durationDays,
                               double volumeStrength, int nTouches, double meanReaction,
                               TouchPayload firstTouch, List<TouchPayload> touches) {
        public static ZonePayload from(ConsolidationZoneDetector.ZoneView z) {
            return new ZonePayload(z.bandLow(), z.bandHigh(), z.anchorDate(), z.durationDays(),
                    z.volumeStrength(), z.nTouches(), z.meanReaction(),
                    TouchPayload.from(z.firstTouch()),
                    z.touches().stream().map(TouchPayload::from).toList());
        }
    }

    public record RegimeSegmentPayload(String start, String end, String regime) {
        public static RegimeSegmentPayload from(RegimeCalculator.RegimeSegment s) {
            return new RegimeSegmentPayload(s.start(), s.end(), s.regime().name().toLowerCase());
        }
    }
}
