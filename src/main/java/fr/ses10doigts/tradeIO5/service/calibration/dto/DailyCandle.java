package fr.ses10doigts.tradeIO5.service.calibration.dto;

import java.time.LocalDate;

/**
 * Bougie D1 (OHLCV) utilisée par le pipeline de détection de zones "consolidation"
 * ({@link fr.ses10doigts.tradeIO5.service.calibration.ConsolidationZoneDetector}). Type interne
 * dédié (double, pas BigDecimal comme {@link fr.ses10doigts.tradeIO5.model.dto.market.MarketData})
 * pour rester au plus près du script Python de calibration
 * (tools/calibration/export_zones_v2.py) dont cette classe est un portage — même sémantique
 * numérique (double/numpy), pour éviter toute divergence de comportement liée à l'arithmétique
 * BigDecimal.
 */
public record DailyCandle(LocalDate date, double open, double high, double low, double close, double volume) {
}
