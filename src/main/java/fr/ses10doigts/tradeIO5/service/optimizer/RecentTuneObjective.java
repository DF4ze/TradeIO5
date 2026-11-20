package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Frozen;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fonction à maximiser pour régler UN jeu (Bull ou Bear) sur la vue de réglage, comme à la main dans l'Expert : lecture « gel » (le
 * simulateur de l'Expert, barres hors régime inactives), critère = PnL total du régime en USDC (ventes encaissées + position, moins
 * l'investi : {@link Frozen#totalGain()}). Le garde-fou d'exposition est le SEUL veto : exposition moyenne du cycle réel (les deux jeux
 * actifs) sur la vue de réglage ≤ {@code guardFactor} × celle du preset en place. Un jeu invalide pour l'Expert ou non rejouable est
 * vetoé sans rejeu. Pur, thread-safe, mémoïsé par candidat (les étages de fidélité du moteur partagent le même calcul : une seule vue,
 * frais simplifiés).
 */
final class RecentTuneObjective implements SearchObjective {

    static final String VETO_EXPOSURE_GUARD = "EXPOSURE_GUARD";

    /** Mesure d'un candidat : gel du jeu réglé et cycle réel de la vue de réglage. */
    record Measure(Frozen frozen, Reading cycle) {
    }

    private final SensitivityEvaluator ev;
    private final ParamSpace space;
    private final RainbowAtrParamSet[] current;
    private final int setIndex;
    private final double presetExposure;
    private final double guardFactor;
    private final int windowMonths;
    private final Map<String, Evaluation> memo = new ConcurrentHashMap<>();
    private final AtomicLong replayed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    /**
     * @param current        jeux {@code [BEAR, BULL]} en cours (celui d'indice {@code setIndex} est remplacé par le candidat)
     * @param presetExposure exposition moyenne du cycle réel du preset EN PLACE sur la vue de réglage (%)
     */
    RecentTuneObjective(SensitivityEvaluator ev, ParamSpace space, RainbowAtrParamSet[] current, int setIndex, double presetExposure,
                        double guardFactor) {
        this.ev = ev;
        this.space = space;
        this.current = current.clone();
        this.setIndex = setIndex;
        this.presetExposure = presetExposure;
        this.guardFactor = guardFactor;
        this.windowMonths = ev.template().windowMonths();
    }

    /** Jeux joués pour un candidat ({@code null} si invalide). */
    RainbowAtrParamSet[] sets(Candidate c) {
        RainbowAtrParamSet set = RecentTuneSpace.resolve(space, c, current[setIndex], windowMonths);
        if (set == null) {
            return null;
        }
        RainbowAtrParamSet[] out = current.clone();
        out[setIndex] = set;
        return out;
    }

    @Override
    public Evaluation evaluate(Candidate candidate, Fidelity fidelity, Scenario scenario) {
        String key = candidate.key();
        Evaluation known = memo.get(key);
        if (known != null) {
            return known;
        }
        Evaluation fresh = compute(candidate);
        Evaluation raced = memo.putIfAbsent(key, fresh);
        return raced != null ? raced : fresh;
    }

    private Evaluation compute(Candidate candidate) {
        RainbowAtrParamSet[] sets = sets(candidate);
        if (sets == null) {
            return new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(OptimizerDefaults.VETO_INVALID), null);
        }
        replayed.incrementAndGet();
        Reading cycle = ev.cycle(sets, false);
        if (cycle == null) {
            return new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(OptimizerDefaults.VETO_NO_WINDOW), null);
        }
        if (BlockTuner.rejects(cycle.avgExposurePct(), presetExposure, guardFactor)) {
            rejected.incrementAndGet();
            return new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(VETO_EXPOSURE_GUARD), new Measure(null, cycle));
        }
        Frozen frozen = ev.frozen(sets, setIndex);
        if (frozen == null) {
            return new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of(OptimizerDefaults.VETO_NO_WINDOW), null);
        }
        return Evaluation.ok(frozen.totalGain(), new Measure(frozen, cycle));
    }

    /** Candidats réellement rejoués (hors mémoïsation). */
    long replayed() {
        return replayed.get();
    }

    /** Candidats rejetés par le garde-fou d'exposition. */
    long guardRejections() {
        return rejected.get();
    }
}
