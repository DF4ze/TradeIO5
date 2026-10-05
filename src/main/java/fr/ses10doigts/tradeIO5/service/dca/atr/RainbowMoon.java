package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * Automate To the moon (pine v4), fonction pure d'une bougie. Source unique : utilisée à la fois par
 * {@link RainbowAtrDataset#moon} (drapeaux d'une série entière, rejeu/bench) et par
 * {@link RainbowAtrStrategy#step} (exécution quotidienne), pour qu'il n'existe qu'une seule implémentation.
 * <p>
 * Entrée : première clôture au-dessus de l'ATH de la VEILLE (mode inactif) ; pic = high de la bougie d'entrée.
 * Sortie : clôture ≤ pic × (1 - stop%). Pas de test de sortie sur la bougie d'entrée.
 */
public final class RainbowMoon {

    /** État persistant de l'automate : mode actif et plus haut depuis l'entrée (NaN si inactif). */
    public record State(boolean active, double peak) {
        public static final State INACTIVE = new State(false, Double.NaN);
    }

    /** Résultat d'une bougie : état APRÈS mise à jour (= {@code mode} de la bougie) et drapeaux d'entrée/sortie. */
    public record Step(State next, boolean entry, boolean exit) {
        public boolean mode() { return next.active(); }
    }

    private RainbowMoon() {
    }

    /**
     * @param athPrevious ATH (plus haut) de la veille ; NaN s'il n'y a pas de veille (1re bougie de la série)
     */
    public static Step advance(State s, boolean moonOn, double trailingStopPct, double close, double high,
                               double athPrevious) {
        boolean newAthClose = !Double.isNaN(athPrevious) && close > athPrevious;
        if (moonOn && !s.active() && newAthClose) {
            return new Step(new State(true, high), true, false);
        }
        if (s.active()) {
            double peak = Math.max(Double.isNaN(s.peak()) ? high : s.peak(), high);
            if (close <= peak * (1 - trailingStopPct / 100)) {
                return new Step(State.INACTIVE, false, true);
            }
            return new Step(new State(true, peak), false, false);
        }
        return new Step(s, false, false);
    }
}
