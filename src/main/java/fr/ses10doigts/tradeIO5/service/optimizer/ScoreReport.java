package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.Gain;

import java.time.LocalDate;
import java.util.List;

/**
 * Rapport d'un score : fenêtres, vetos, médiane pondérée du gain vs DCA fixe, pénalité de dispersion, <b>timing pur</b> (calculé et
 * rapporté à part : il n'entre jamais dans {@code score}) et empreinte des bougies.
 *
 * @param score           {@code médiane pondérée du gain % − pénalité} ; {@link OptimizerDefaults#VETO_SCORE} si vetoé
 * @param rawScore        même calcul sans tenir compte des vetos (diagnostic)
 * @param excluded        fenêtres non éligibles (jamais remplacées silencieusement), avec le motif
 * @param pureTiming      timing pur pondéré (second, hors score) ; {@code null} si aucune fenêtre
 * @param costMode        {@code SIMPLIFIED} ou {@code COMPLETE}
 * <p>
 * {@code windows} liste toutes les fenêtres rejouées ; seules les fenêtres {@link WindowReport#scored() bornées} (12 m / 24 m / test) entrent dans
 * {@code medianGainPct}, {@code dispersionPct}, {@code penalty}, {@code score}, le timing pur et le veto de drawdown.
 */
public record ScoreReport(double score, double rawScore, boolean vetoed, List<String> vetoes, Double medianGainPct,
                          double dispersionPct, double penalty, List<WindowReport> windows, List<String> excluded,
                          PureTiming pureTiming, String costMode, List<CandleFingerprint> fingerprint) {

    /**
     * Résultat d'une fenêtre. {@code split} = répartition du gain vs DCA fixe (exposition / timing / frais, {@code EpisodeTimeline}).
     * {@code minCash} : cash minimal du wallet sur la fenêtre.
     */
    public record WindowReport(String name, Integer months, int weight, LocalDate from, LocalDate to, double finalValue,
                               double fixedFinalValue, double gainUsdc, Double gainPct, double maxDrawdownPct, double avgExposurePct,
                               Gain split, long blockedDays, long belowMinDays, long buys, long sells, double maxStakeMultiple,
                               double maxFinalStakeMultiple, long cappedBuys, boolean neverSells, boolean neverRebuys,
                               double minCash, double fixedMinCash, double cash0, Double timingGainPct) {

        /**
         * Fenêtre qui compte dans le score, la dispersion, le timing pur et le veto de drawdown : les fenêtres bornées (12 m / 24 m / test).
         * L'historique complet ({@code months} nul) est seulement rapporté (gain, drawdown).
         */
        public boolean scored() {
            return months != null;
        }
    }

    /**
     * Statut d'historique d'un lien sur une fenêtre : {@code HISTORIQUE_INSUFFISANT} quand il manque des bougies (disponibles avant le début de la
     * fenêtre &lt; requises par les warm-ups). Rapporté tel quel, jamais une exclusion silencieuse.
     */
    public record HistoryStatus(String asset, String status, String window, int candlesAvailable, int candlesRequired) {
    }

    /**
     * Gain à exposition égale : terme « timing » de la répartition exposition / timing / frais d'{@code EpisodeTimeline} (l'effet
     * d'exposition, écart de quantité moyen × mouvement du prix, en est retiré), en % de la valeur du DCA fixe ; médiane pondérée des
     * fenêtres. Mesure la plus simple disponible : aucune seconde simulation.
     */
    public record PureTiming(double weightedMedianPct, String definition) {
    }
}
