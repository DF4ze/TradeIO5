package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

/**
 * Trend → jeu actif. {@code UP} → Bull, {@code DOWN} → Bear, {@code RANGE} selon {@link RangeMapping} (pas de
 * Sideways dans les jeux). Réévalué chaque jour ; l'état de la machine Rainbow est conservé au changement de jeu
 * (cf. {@link RainbowAtrReplay}). Tant que la Trend n'est pas définie (warmup) ou au démarrage : jeu Bear (prudent).
 */
public final class RainbowSetSelector {

    /** Que faire quand la Trend est RANGE. */
    public enum RangeMapping { KEEP_PREVIOUS, TO_BEAR, TO_BULL }

    private RainbowSetSelector() {
    }

    /**
     * @param regimeOfBar régime de Trend par bougie ({@code null} = indéfini)
     * @return index de jeu par bougie : {@link RainbowAtrPresets#BEAR} ou {@link RainbowAtrPresets#BULL}
     */
    public static int[] select(TrendRegime[] regimeOfBar, RangeMapping mapping) {
        int[] out = new int[regimeOfBar.length];
        int current = RainbowAtrPresets.BEAR;
        for (int i = 0; i < out.length; i++) {
            TrendRegime r = regimeOfBar[i];
            if (r == TrendRegime.UP) {
                current = RainbowAtrPresets.BULL;
            } else if (r == TrendRegime.DOWN) {
                current = RainbowAtrPresets.BEAR;
            } else if (r == TrendRegime.RANGE) {
                switch (mapping) {
                    case TO_BEAR -> current = RainbowAtrPresets.BEAR;
                    case TO_BULL -> current = RainbowAtrPresets.BULL;
                    case KEEP_PREVIOUS -> { }
                }
            }
            out[i] = current;
        }
        return out;
    }

    /** Un seul jeu pour toutes les bougies (mode « preset fixe », comparaison propre au pine). */
    public static int[] fixed(int length, int setIndex) {
        int[] out = new int[length];
        java.util.Arrays.fill(out, setIndex);
        return out;
    }
}
