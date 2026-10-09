package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;

import java.util.List;

/**
 * Trend « Mix » (régression multi-fenêtres + prix vs SMA ± k × ATR), portage de la méthode
 * {@code Mix (régression + SMA/ATR)} de {@code tools/pine/rainbow_trend_dca_v1.pine} (SOURCE DE VÉRITÉ).
 * Service pur (ni Spring, ni {@code IndicatorContext}) ; la SMA et l'ATR sont fournies par l'appelant (mêmes séries
 * que le Rainbow : {@code ta.sma(close, n)} et {@code ta.atr(n)} = RMA du true range, NaN avant leur warmup).
 * <p>
 * Deux sous-régimes, chacun causal (un état par bougie) :
 * <ul>
 *   <li><b>régression</b> : score = moyenne (poids égaux) de {@code tanh(pente normalisée × échelle) × r²} sur les 3
 *   fenêtres, borné à [-1, 1] ; machine à hystérésis ENTER/EXIT (enchaînement {@code else if} : une sortie et une
 *   entrée ne se produisent jamais sur la même bougie, comme le pine) puis confirmation de {@code confirm} jours
 *   consécutifs avant d'appliquer le changement ;</li>
 *   <li><b>SMA</b> : DOWN si le bas (mèche si {@code wickDown}, sinon clôture) passe sous SMA − k × ATR, UP si le haut
 *   (mèche si {@code wickUp}, sinon clôture) passe au-dessus de SMA + k × ATR ; les deux à la fois : clôture ≥ SMA
 *   → UP, sinon DOWN ; sinon on garde le dernier état ({@link TrendRegime#RANGE} tant qu'aucune bande n'a été franchie).</li>
 * </ul>
 * Composition : la régression décisive (UP/DOWN) donne le régime ; la régression en RANGE laisse le régime de la
 * SMA. Le 1er des deux à changer de côté fait basculer : un changement de direction de la régression (par rapport à
 * sa dernière direction décisive mémorisée — RANGE n'est pas un switch) ou un changement d'état de la SMA tant que la
 * régression est décisive. Tant qu'aucune source n'a tranché, le régime est celui de la régression.
 */
public final class TrendMixCalculator {

    /** Source du régime Mix d'une bougie. */
    public enum Source { NONE, REGRESSION, SMA }

    /**
     * Réglages. Défauts = réglages retenus par Clem (2026-10-07) : régression 14/30/60, échelle 400, ENTER 0,3,
     * EXIT 0,1, confirmation 10 j ; SMA 100, ATR 5, k 1,25, mèche basse oui / haute non.
     */
    public record Params(int shortWindow, int mediumWindow, int longWindow, double slopeScale, double enter,
                         double exit, int confirm, int smaPeriod, int atrPeriod, double atrMultiplier,
                         boolean wickDown, boolean wickUp) {

        public static Params defaults() {
            return new Params(14, 30, 60, 400.0, 0.30, 0.10, 10, 100, 5, 1.25, true, false);
        }

        /** Bougies nécessaires avant que le Mix soit défini (pine {@code ready}). */
        public int warmup() {
            return Math.max(Math.max(Math.max(shortWindow, mediumWindow), longWindow), Math.max(smaPeriod, atrPeriod));
        }
    }

    /** Séries alignées sur les bougies ; {@code regime[i] == null} tant que {@code i < warmup - 1}. */
    public record Result(TrendRegime[] regime, TrendRegime[] regression, TrendRegime[] sma, Source[] source) {
    }

    private TrendMixCalculator() {
    }

    /**
     * @param candles bougies D1 chronologiques
     * @param sma     SMA({@code params.smaPeriod}) de la clôture, NaN avant son warmup
     * @param atr     ATR({@code params.atrPeriod}), NaN avant son warmup
     */
    public static Result compute(List<MarketData> candles, double[] sma, double[] atr, Params p) {
        int n = candles.size();
        if (sma.length != n || atr.length != n) {
            throw new IllegalArgumentException("sma/atr doivent avoir la longueur des bougies");
        }
        double[] score = regressionScores(candles, p);

        TrendRegime[] regime = new TrendRegime[n];
        TrendRegime[] regOut = new TrendRegime[n];
        TrendRegime[] smaOut = new TrendRegime[n];
        Source[] source = new Source[n];

        int raw = 0, applied = 0, diff = 0;       // machine régression (pine f_regime)
        int smaCur = 0, prevSma = 0;              // machine SMA (pine f_priceAtr) et sa valeur à la bougie précédente
        int regDir = 0, prevRegDir = 0;           // dernière direction décisive de la régression
        int outMix = 0, mixSrc = 0;
        int ready = p.warmup() - 1;

        for (int i = 0; i < n; i++) {
            double s = score[i];
            if (!Double.isNaN(s)) {
                if (raw == 1 && s < p.exit()) {
                    raw = 0;
                } else if (raw == -1 && s > -p.exit()) {
                    raw = 0;
                } else if (raw == 0) {
                    if (s > p.enter()) {
                        raw = 1;
                    } else if (s < -p.enter()) {
                        raw = -1;
                    }
                }
            }
            if (raw == applied) {
                diff = 0;
            } else if (++diff >= p.confirm()) {
                applied = raw;
                diff = 0;
            }
            int fastReg = applied;

            if (!Double.isNaN(sma[i]) && !Double.isNaN(atr[i])) {
                double dnPx = p.wickDown() ? candles.get(i).getLow().doubleValue() : candles.get(i).getClose().doubleValue();
                double upPx = p.wickUp() ? candles.get(i).getHigh().doubleValue() : candles.get(i).getClose().doubleValue();
                double cl = candles.get(i).getClose().doubleValue();
                boolean dnHit = dnPx < sma[i] - p.atrMultiplier() * atr[i];
                boolean upHit = upPx > sma[i] + p.atrMultiplier() * atr[i];
                if (dnHit && upHit) {
                    smaCur = cl >= sma[i] ? 1 : -1;
                } else if (upHit) {
                    smaCur = 1;
                } else if (dnHit) {
                    smaCur = -1;
                }
            }
            int maReg = smaCur;

            if (fastReg != 0) {
                regDir = fastReg;
            }
            boolean regEvent = fastReg != 0 && regDir != prevRegDir;
            boolean smaEvent = maReg != 0 && maReg != prevSma;
            boolean rngSma = fastReg == 0 && maReg != 0;
            boolean regSw = fastReg != 0 && regEvent;
            boolean smaSw = fastReg != 0 && !regEvent && smaEvent;
            if (rngSma) {
                outMix = maReg;
                mixSrc = 2;
            }
            if (regSw) {
                outMix = regDir;
                mixSrc = 1;
            }
            if (smaSw) {
                outMix = maReg;
                mixSrc = 2;
            }
            if (outMix == 0 && fastReg != 0) {
                outMix = fastReg;
                mixSrc = 1;
            }
            prevRegDir = regDir;
            prevSma = maReg;

            if (i >= ready) {
                regime[i] = of(outMix);
                regOut[i] = of(fastReg);
                smaOut[i] = of(maReg);
                source[i] = mixSrc == 1 ? Source.REGRESSION : mixSrc == 2 ? Source.SMA : Source.NONE;
            }
        }
        return new Result(regime, regOut, smaOut, source);
    }

    /** Score de régression par bougie (NaN avant la fenêtre longue) ; poids égaux, borné à [-1, 1]. */
    private static double[] regressionScores(List<MarketData> candles, Params p) {
        LinearRegressionCalculator calc = new LinearRegressionCalculator();
        List<LinearRegressionSnapshot> a = calc.computeTimeline(candles, p.shortWindow());
        List<LinearRegressionSnapshot> b = calc.computeTimeline(candles, p.mediumWindow());
        List<LinearRegressionSnapshot> c = calc.computeTimeline(candles, p.longWindow());
        double[] out = new double[candles.size()];
        for (int i = 0; i < out.length; i++) {
            if (a.get(i) == null || b.get(i) == null || c.get(i) == null) {
                out[i] = Double.NaN;
                continue;
            }
            out[i] = RegressiveTrendScoreCalculator.computeScore(
                    new RegressiveTrendScoreCalculator.WindowInput(a.get(i).normalizedSlope(), a.get(i).r2()),
                    new RegressiveTrendScoreCalculator.WindowInput(b.get(i).normalizedSlope(), b.get(i).r2()),
                    new RegressiveTrendScoreCalculator.WindowInput(c.get(i).normalizedSlope(), c.get(i).r2()),
                    p.slopeScale(), 1.0, 1.0, 1.0, false);
        }
        return out;
    }

    private static TrendRegime of(int v) {
        return v > 0 ? TrendRegime.UP : v < 0 ? TrendRegime.DOWN : TrendRegime.RANGE;
    }
}
