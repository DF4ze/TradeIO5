package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;

import java.util.ArrayList;
import java.util.List;

/**
 * Régression linéaire (moindres carrés / OLS) des clôtures sur une fenêtre glissante de
 * {@code period} bougies — piste étudiée à la demande de Clem (2026-09-19) en alternative/
 * complément à SWING_STRUCTURE pour mesurer une tendance de façon continue (recalculée à chaque
 * bougie, pas d'attente de confirmation de pivot comme {@link SwingStructureCalculator}).
 * Service pur (même esprit que {@code SwingStructureCalculator}) : ne dépend ni de
 * {@code IndicatorResult}, ni de {@code IndicatorContext}, ni de Spring.
 * <p>
 * {@code normalizedSlope} = pente / prix moyen de la fenêtre (fraction de prix par bougie), pour
 * rester comparable entre actifs et régimes de volatilité plutôt que de comparer des pentes en
 * unité de prix brute (même retour d'expérience que le bug d'unité ETF_FLOW/OI — un seuil sur une
 * grandeur non normalisée ne se transpose pas d'un actif à l'autre). {@code r2} (coefficient de
 * détermination) mesure la qualité de l'ajustement : proche de 1 = tendance propre, proche de 0 =
 * bruit/range — pensé pour jouer un rôle proche de la Force ADX dans {@code TrendConfirmationStrategy},
 * mais nativement produit par la régression plutôt que par un indicateur tiers.
 * <p>
 * Limite connue, assumée : la régression reste un filtre linéaire suiveur (lag comparable à une
 * SMA de même période), pas un indicateur prédictif — elle élimine le délai de confirmation
 * structurelle (BOS) mais pas le lag intrinsèque du lissage sur {@code period} bougies.
 */
public class LinearRegressionCalculator {

    /**
     * @param candles bougies triées chronologiquement
     * @param period  taille de la fenêtre de régression (ex : 7, 14, 30)
     * @throws IllegalArgumentException si {@code candles} est null, {@code period < 2}, ou s'il y
     *                                  a moins de {@code period} bougies (pas de valeur de repli
     *                                  silencieuse — à l'appelant de vérifier l'historique
     *                                  disponible avant d'appeler, cf. {@code LinearRegressionIndicator})
     */
    public LinearRegressionResult compute(List<MarketData> candles, int period) {
        if (candles == null) {
            throw new IllegalArgumentException("candles must not be null");
        }
        if (period < 2) {
            throw new IllegalArgumentException("period must be >= 2");
        }
        if (candles.size() < period) {
            throw new IllegalArgumentException("not enough candles (" + candles.size() + ") for period " + period);
        }

        List<MarketData> window = candles.subList(candles.size() - period, candles.size());
        return computeOn(window);
    }

    /**
     * Timeline complète (une valeur par bougie, {@code null} tant que l'historique est
     * insuffisant pour la première fenêtre) — même patron que
     * {@code SwingStructureCalculator#computeTimeline} : besoin du benchmark de calibration et de
     * la page de visualisation, qui veulent tracer la courbe de régression à chaque instant, pas
     * seulement sa dernière valeur.
     * <p>
     * Duplique volontairement la fenêtre glissante plutôt que d'appeler {@link #compute} en boucle
     * sur des sous-listes croissantes : {@link #compute} reste indépendant et testé isolément
     * (même choix que {@code SwingStructureCalculator}).
     *
     * @param candles identique à {@link #compute}, peut être vide
     */
    public List<LinearRegressionSnapshot> computeTimeline(List<MarketData> candles, int period) {
        if (candles == null) {
            throw new IllegalArgumentException("candles must not be null");
        }
        if (period < 2) {
            throw new IllegalArgumentException("period must be >= 2");
        }

        List<LinearRegressionSnapshot> timeline = new ArrayList<>(candles.size());
        for (int i = 0; i < candles.size(); i++) {
            if (i + 1 < period) {
                timeline.add(null);
                continue;
            }
            List<MarketData> window = candles.subList(i + 1 - period, i + 1);
            LinearRegressionResult result = computeOn(window);
            timeline.add(new LinearRegressionSnapshot(
                    candles.get(i).getTimestamp(), result.regressionValue(), result.normalizedSlope(), result.r2()));
        }
        return timeline;
    }

    private LinearRegressionResult computeOn(List<MarketData> window) {
        int n = window.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            double y = window.get(i).getClose().doubleValue();
            sumX += i;
            sumY += y;
            sumXY += (double) i * y;
            sumX2 += (double) i * i;
        }

        double meanX = sumX / n;
        double meanY = sumY / n;

        double denominator = sumX2 - n * meanX * meanX;
        double slope = denominator == 0 ? 0.0 : (sumXY - n * meanX * meanY) / denominator;
        double intercept = meanY - slope * meanX;

        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < n; i++) {
            double y = window.get(i).getClose().doubleValue();
            double predicted = slope * i + intercept;
            ssRes += (y - predicted) * (y - predicted);
            ssTot += (y - meanY) * (y - meanY);
        }
        // ssTot == 0 : fenêtre parfaitement plate, aucune variance à expliquer -> r2 défini à 0
        // (pas de tendance, pas 1 malgré un ajustement parfait trivial).
        double r2 = ssTot == 0 ? 0.0 : Math.max(0.0, 1.0 - ssRes / ssTot);

        double regressionValue = slope * (n - 1) + intercept;
        double normalizedSlope = meanY == 0 ? 0.0 : slope / meanY;

        return new LinearRegressionResult(slope, normalizedSlope, r2, regressionValue);
    }
}
