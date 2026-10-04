package fr.ses10doigts.tradeIO5.service.tree.trend;

import java.time.Instant;

/**
 * Sortie du calculateur de Trend unifié ({@link TrendAnalyzer}) — contrat figé par l'Étape 8 de la
 * roadmap Trend unifié (2026-09-24, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §3).
 * Remplace l'ancien contrat porté par {@code SwingStructureRegime}/ADX (Étapes 1-7) : la Trend est
 * désormais entièrement portée par la régression linéaire multi-fenêtres à hystérésis (candidate
 * REG_H du walk-forward, cf. {@code docs/etudes/spec-composition-trend-unifie.md} §10).
 *
 * @param regime          état discret de l'hystérésis sur la dernière bougie ({@link TrendRegime})
 * @param score           score de régression brut [-1,1] de la dernière bougie (cf.
 *                        {@link RegressiveTrendScoreCalculator}) — pour un consommateur qui veut le
 *                        signal continu sans le filtre de l'hystérésis
 * @param force           intensité de la tendance, [0,1] — {@code abs(score)}
 * @param confidence      {@code min(1, runLength / 10)}, où {@code runLength} = nombre de bougies
 *                        consécutives dans le {@code regime} courant. <b>Provisoire, non calibré</b>
 *                        (10 ≈ durée médiane d'un état UP/DOWN observée au walk-forward, cf. spec
 *                        §10.4) — volontairement <b>non utilisé</b> dans le score de
 *                        {@code TrendConfirmationStrategy}
 * @param bosJustOccurred {@code true} si {@code regime} a changé sur la toute dernière bougie de la
 *                        série fournie
 * @param bosTimestamp    horodatage du début du {@code regime} courant (date de la dernière
 *                        transition d'état, quelle que soit son ancienneté)
 */
public record TrendState(
        TrendRegime regime,
        double score,
        double force,
        double confidence,
        boolean bosJustOccurred,
        Instant bosTimestamp
) {
}
