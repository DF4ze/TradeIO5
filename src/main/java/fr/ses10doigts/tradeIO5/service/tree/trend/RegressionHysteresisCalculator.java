package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Machine à états hystérésis appliquée à une série chronologique de scores continus de
 * {@link RegressiveTrendScoreCalculator}, pour produire un état discret {@link TrendRegime} par
 * bougie — Étape 8 de la roadmap Trend unifié (2026-09-24, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §2.2).
 * Service pur, testable indépendamment de {@link TrendAnalyzer}/de la régression elle-même : ne
 * prend en entrée qu'une liste de scores, dans l'ordre chronologique.
 * <p>
 * Algorithme (réglages figés par le walk-forward, non recalibrés dans ce lot) :
 * <pre>
 * état initial = RANGE
 * pour chaque bougie (ordre chronologique) :
 *   si état == UP   et score &lt; EXIT   -&gt; état = RANGE
 *   si état == DOWN et score &gt; -EXIT  -&gt; état = RANGE
 *   si état == RANGE :
 *       si score &gt;  ENTER -&gt; UP
 *       si score &lt; -ENTER -&gt; DOWN
 * </pre>
 * {@code ENTER_THRESHOLD} réutilise la barrière {@code 1/6} déjà utilisée partout ailleurs pour
 * distinguer NEUTRAL de BULLISH/BEARISH ({@link MarketOpinionHelper#BARRIER}) — réutilisée comme
 * constante, pas recopiée séparément (demande explicite du prompt §2.2).
 * <p>
 * Une sortie et une réentrée peuvent se produire sur la même bougie (ex : UP → RANGE → DOWN si le
 * score passe directement de positif à moins de {@code -ENTER_THRESHOLD}) : comportement voulu,
 * validé sur la réplique Python et conservé tel quel (cf. prompt §2.2) — d'où l'implémentation en
 * deux blocs {@code if} séquentiels ci-dessous plutôt qu'un {@code switch} à branches exclusives.
 */
public final class RegressionHysteresisCalculator {

    public static final double ENTER_THRESHOLD = MarketOpinionHelper.BARRIER;
    public static final double EXIT_THRESHOLD = 0.0;

    /**
     * Cf. prompt §2.2 "Warmup" : mesuré empiriquement, 30 bougies de score avant l'instant t
     * suffisent pour reproduire à l'identique l'état obtenu avec tout l'historique disponible
     * (100% des cas testés).
     */
    public static final int MIN_SCORES = 30;

    private RegressionHysteresisCalculator() {
    }

    /**
     * @param scores scores de régression, dans l'ordre chronologique
     * @throws IllegalArgumentException si {@code scores} est {@code null} ou compte moins de
     *                                  {@link #MIN_SCORES} éléments — pas de valeur de repli
     *                                  silencieuse (même convention que {@link LinearRegressionCalculator}).
     */
    public static List<TrendRegime> computeTimeline(List<Double> scores) {
        if (scores == null || scores.size() < MIN_SCORES) {
            throw new IllegalArgumentException(
                    "not enough scores (" + (scores == null ? 0 : scores.size()) + ") for hysteresis : "
                            + MIN_SCORES + " minimum");
        }

        List<TrendRegime> regimes = new ArrayList<>(scores.size());
        TrendRegime state = TrendRegime.RANGE;
        for (double score : scores) {
            state = next(state, score);
            regimes.add(state);
        }
        return regimes;
    }

    private static TrendRegime next(TrendRegime state, double score) {
        if (state == TrendRegime.UP && score < EXIT_THRESHOLD) {
            state = TrendRegime.RANGE;
        } else if (state == TrendRegime.DOWN && score > -EXIT_THRESHOLD) {
            state = TrendRegime.RANGE;
        }
        if (state == TrendRegime.RANGE) {
            if (score > ENTER_THRESHOLD) {
                state = TrendRegime.UP;
            } else if (score < -ENTER_THRESHOLD) {
                state = TrendRegime.DOWN;
            }
        }
        return state;
    }
}
