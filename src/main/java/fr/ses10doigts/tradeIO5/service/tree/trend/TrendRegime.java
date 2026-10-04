package fr.ses10doigts.tradeIO5.service.tree.trend;

/**
 * État discret de la Trend unifiée (Étape 8 de la roadmap Trend unifié, 2026-09-24 — cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §1/§3) —
 * sortie de {@link RegressionHysteresisCalculator} appliquée au score continu de
 * {@link RegressiveTrendScoreCalculator}. Remplace {@code SwingStructureRegime} comme axe Régime de
 * {@link TrendAnalyzer} (retiré de cet axe par ce même lot, cf. javadoc de {@link TrendAnalyzer}).
 * <p>
 * {@code RANGE} est l'état neutre, décidé par Clem (2026-09-24) pour le futur consommateur Rainbow
 * DCA (paramétrage UP/DOWN/RANGE).
 */
public enum TrendRegime {
    UP,
    DOWN,
    RANGE
}
