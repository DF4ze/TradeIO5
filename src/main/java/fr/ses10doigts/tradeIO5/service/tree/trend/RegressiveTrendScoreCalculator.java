package fr.ses10doigts.tradeIO5.service.tree.trend;

/**
 * Score de tendance par régression linéaire multi-fenêtres — formule mutualisée (Étape 8 de la
 * roadmap Trend unifié, 2026-09-24, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §2.1),
 * extraite de {@code RegressiveTrendStrategy#evaluate} pour être consommée à la fois par
 * {@code RegressiveTrendStrategy} et par {@link TrendAnalyzer}, qui porte désormais l'axe Trend/
 * Régime de production. Service pur (mêmes garanties que {@link LinearRegressionCalculator}) : ne
 * dépend ni de {@code IndicatorResult}, ni de {@code IndicatorContext}, ni de Spring.
 * <p>
 * Formule (inchangée dans sa forme depuis l'introduction de {@code RegressiveTrendStrategy}, cf. sa
 * javadoc historique) :
 * <pre>
 * signal_i = tanh(normalizedSlope_i × slopeScaleFactor)
 * score    = clamp( Σ w_i × signal_i × r2_i / Σ w_i  ×  alignmentFactor , -1, 1)
 * alignmentFactor = 1.0 si alignmentEnabled == false (nouveau défaut) ; sinon 0.4 + 0.6 × (accords/3)
 *                   (comportement historique : 1.0 si les 3 signaux ont le même signe, dégradé sinon)
 * </pre>
 * <p>
 * <b>Nouveaux défauts</b> (walk-forward BTC D1 2017-2026, cf.
 * {@code docs/etudes/spec-composition-trend-unifie.md} §10.1/10.3) : {@code slopeScaleFactor=400}
 * (plateau de sensibilité mesuré entre 200 et l'infini, 400 pris au milieu plutôt qu'en butée de
 * grille), poids égaux entre les 3 fenêtres (les poids croissants 0.2/0.3/0.5 avec alignement actif
 * étaient systématiquement battus), alignement désactivé par défaut (le facteur d'alignement
 * dégradait l'accord avec la tendance réelle dans toutes les variantes testées). Les défauts
 * historiques (100 / 0.2-0.3-0.5 / toujours actif) restent portés par
 * {@code RegressiveTrendStrategy.DEFAULT_*} — <b>seul et unique endroit</b> où ces constantes sont
 * définies (mutualisées avec {@code TrendConfirmationStrategy}, cf. sa javadoc), conformément à la
 * demande explicite du prompt Étape 8 §2.1 ("les constantes par défaut ne doivent exister qu'à un
 * seul endroit").
 */
public final class RegressiveTrendScoreCalculator {

    private RegressiveTrendScoreCalculator() {
    }

    /**
     * @param normalizedSlope pente normalisée d'une fenêtre de régression (cf.
     *                        {@link LinearRegressionCalculator})
     * @param r2              coefficient de détermination de cette même fenêtre
     */
    public record WindowInput(double normalizedSlope, double r2) {
    }

    /**
     * @param shortWindow  fenêtre la plus courte (ex : period=7)
     * @param mediumWindow fenêtre intermédiaire (ex : period=14)
     * @param longWindow   fenêtre la plus longue (ex : period=30) — l'ordre court/moyen/long est
     *                     celui des rôles (timing/confirmation/régime), pas les périodes elles-mêmes
     *                     en dur (même convention que l'ancienne javadoc de classe de
     *                     {@code RegressiveTrendStrategy})
     */
    public static double computeScore(
            WindowInput shortWindow, WindowInput mediumWindow, WindowInput longWindow,
            double slopeScaleFactor, double weightShort, double weightMedium, double weightLong,
            boolean alignmentEnabled
    ) {
        WindowInput[] windows = {shortWindow, mediumWindow, longWindow};
        double[] weights = {weightShort, weightMedium, weightLong};

        double[] signals = new double[3];
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (int i = 0; i < 3; i++) {
            double signal = Math.tanh(windows[i].normalizedSlope() * slopeScaleFactor);
            signals[i] = signal;
            weightedSum += weights[i] * signal * windows[i].r2();
            weightTotal += weights[i];
        }
        double weightedSignal = weightTotal == 0 ? 0.0 : weightedSum / weightTotal;

        double alignmentFactor = alignmentEnabled ? computeAlignmentFactor(signals) : 1.0;

        return Math.clamp(weightedSignal * alignmentFactor, -1.0, 1.0);
    }

    /**
     * 1.0 si les 3 signaux ont le même signe (pentes alignées, court/moyen/long tirent dans le
     * même sens) ; dégradé sinon, proportionnellement au nombre de paires en accord (0 à 3 sur les
     * 3 paires possibles). Comportement historique, actif seulement si {@code alignmentEnabled}
     * (désactivé par défaut depuis ce lot, cf. javadoc de classe).
     */
    private static double computeAlignmentFactor(double[] signals) {
        int agreements = 0;
        int pairs = 0;
        for (int i = 0; i < signals.length; i++) {
            for (int j = i + 1; j < signals.length; j++) {
                pairs++;
                if (Math.signum(signals[i]) == Math.signum(signals[j])) {
                    agreements++;
                }
            }
        }
        double agreementRatio = pairs == 0 ? 1.0 : (double) agreements / pairs;
        return 0.4 + 0.6 * agreementRatio;
    }
}
