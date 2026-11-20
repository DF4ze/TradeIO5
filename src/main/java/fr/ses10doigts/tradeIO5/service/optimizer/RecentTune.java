package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Change;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.SearchInfo;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;

/**
 * Étape D0 : réglage « à la main » d'un preset à une date T, avec les seules données {@code ≤ T}. Méthode calquée sur celle de Clem :
 * vue de 24 mois se terminant en T ; Bull seul d'abord (Bear en place), puis Bear seul (Bull = résultat du tour précédent), {@code rounds}
 * tours au maximum ; lecture « gel » ; recherche CONJOINTE ({@link SearchEngine} : Sobol + halving sur tous les réglages du jeu à la fois,
 * {@link RecentTuneSpace}) ; seul garde-fou : exposition moyenne de la vue ≤ facteur × celle du preset en place.
 * Hors ligne : aucun preset n'est modifié, rien n'est appliqué ni persisté.
 */
public final class RecentTune {

    private static final double EPS = 1e-9;

    private RecentTune() {
    }

    /** Résultat du réglage à T : preset en place, jeux réglés, recherches, mesures dans l'échantillon, évaluateur de la vue de réglage. */
    public record Tuned(LocalDate t, LocalDate from, RainbowAtrParamSet[] preset, RainbowAtrParamSet[] tuned, List<SearchInfo> searches,
                        SensitivityEvaluator ev, Reading presetCycle, Reading tunedCycle, SensitivityEvaluator.Frozen[] presetGel,
                        SensitivityEvaluator.Frozen[] tunedGel, long replays, long guardRejections) {

        public boolean changed() {
            return !changes().isEmpty();
        }

        public List<Change> changes() {
            List<Change> out = new ArrayList<>();
            for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
                String role = role(idx);
                for (Map.Entry<String, String[]> e : RecentTuneSpace.diff(preset[idx], tuned[idx]).entrySet()) {
                    out.add(new Change(role, e.getKey(), e.getValue()[0], e.getValue()[1], RecentTuneSpace.move(e.getKey(), preset[idx], tuned[idx])));
                }
            }
            return out;
        }
    }

    static String role(int idx) {
        return idx == RainbowAtrPresets.BULL ? "BULL" : "BEAR";
    }

    /**
     * Entrées sans AUCUNE bougie postérieure à {@code t} : le dataset (SMA, ATR, ATH, Moon, Trend) est reconstruit sur les bougies
     * {@code ≤ t}. Garantie par construction, vérifiée par un test (bougies futures empoisonnées).
     */
    public static ScorerInputs truncated(ScorerInputs in, LocalDate t) {
        ScorerInputs.AssetInput link = in.assets().getFirst();
        LinkSignalReplay.Config c = link.template();
        List<MarketData> candles = c.candles().stream()
                .filter(m -> !ReplayDays.dayOf(m.getTimestamp().toEpochMilli()).isAfter(t)).toList();
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
        LinkSignalReplay.Config cut = new LinkSignalReplay.Config(ds, candles, c.trend(), c.range(), c.sets(), 0, 0, candles.size() - 1,
                c.linkBaseAmount(), c.priceOverride(), c.athBefore(), c.rebootstrapDays(), c.windowMonths(), c.athCap(), c.fixedBaseAmount());
        return in.withAssets(List.of(link.withTemplate(cut))).withEndDate(t);
    }

    /** Fenêtre de réglage : les {@code tuneMonths} mois se terminant en T (T inclus). */
    public static LocalDate tuneFrom(LocalDate t, RecentTuneConfig cfg) {
        return t.minusMonths(cfg.tuneMonths()).plusDays(1);
    }

    /** Nombre de tirages Sobol pour viser {@code trials} essais du moteur (borne approchée : le compte exact est rapporté). */
    static int sobolFor(int trials, int free) {
        int overhead = free * 2 + 4 + 8 * free + 16 + 4 * free;
        return Math.max(8, (int) Math.round((trials - overhead) * 0.75));
    }

    static SearchConfig searchConfig(RecentTuneConfig cfg, int free, long seed) {
        return new SearchConfig(seed, sobolFor(cfg.trialsPerSearch(), free), OptimizerDefaults.HALVING_FACTOR, OptimizerDefaults.FINALISTS, 2,
                0.0, OptimizerDefaults.REFINE_SAMPLES, OptimizerDefaults.REFINE_RADIUS_STEPS, OptimizerDefaults.REFINE_TOP,
                OptimizerDefaults.PLATEAU_BAND, OptimizerDefaults.ROBUSTNESS_TOLERANCE, OptimizerDefaults.ROBUSTNESS_FLOOR);
    }

    /** @return {@code null} si la vue de réglage n'est pas rejouable (préchauffage insuffisant) */
    public static Tuned tune(ScorerInputs in, LocalDate t, RecentTuneConfig cfg, ExecutorService pool) {
        long t0 = System.nanoTime();
        ScorerInputs inT = truncated(in, t);
        LocalDate from = tuneFrom(t, cfg);
        SensitivityEvaluator ev = new SensitivityEvaluator(inT, cfg.tuneMonths(), from);
        RainbowAtrParamSet[] preset = ev.template().sets();
        Reading presetCycle = ev.cycle(preset, false);
        if (presetCycle == null) {
            return null;
        }
        double presetExposure = presetCycle.avgExposurePct();
        RainbowAtrParamSet[] current = preset.clone();
        List<SearchInfo> searches = new ArrayList<>();
        long guard = 0;
        for (int round = 1; round <= cfg.rounds(); round++) {
            boolean changed = false;
            for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
                ParamSpace space = RecentTuneSpace.build(current[idx]);
                RecentTuneObjective objective = new RecentTuneObjective(ev, space, current, idx, presetExposure, cfg.exposureGuardFactor());
                Candidate start = space.startCandidate();
                Evaluation startEval = objective.evaluate(start, Fidelity.ALL_WINDOWS_SIMPLIFIED, Scenario.BASE);
                SearchConfig sc = searchConfig(cfg, space.free().size(), cfg.searchSeed(t, idx, round));
                SearchResult result = new SearchEngine(pool).run(space, objective, sc, SearchControl.none());
                Evaluation chosenEval = result.chosenEvaluation();
                RainbowAtrParamSet[] chosenSets = objective.sets(result.chosen());
                boolean adopt = chosenSets != null && !chosenEval.vetoed() && (startEval.vetoed() || chosenEval.score() > startEval.score() + EPS);
                String reason = adopt ? "retenu" : chosenEval.vetoed() ? "choisi vetoé (" + chosenEval.vetoes() + ")"
                        : "pas mieux que le départ dans l'échantillon";
                int before = RecentTuneSpace.diff(current[idx], chosenSets == null ? current[idx] : chosenSets[idx]).size();
                if (adopt) {
                    current = chosenSets;
                    changed |= before > 0;
                }
                guard += objective.guardRejections();
                searches.add(new SearchInfo(round, role(idx), result.trials(), objective.replayed(), objective.guardRejections(), sc.sobolSamples(),
                        adopt, startEval.vetoed() ? Double.NaN : startEval.score(), chosenEval.vetoed() ? Double.NaN : chosenEval.score(), reason,
                        result.vetoCounts()));
            }
            if (!changed) {
                break;
            }
        }
        Reading tunedCycle = ev.cycle(current, false);
        SensitivityEvaluator.Frozen[] presetGel = {ev.frozen(preset, RainbowAtrPresets.BEAR), ev.frozen(preset, RainbowAtrPresets.BULL)};
        SensitivityEvaluator.Frozen[] tunedGel = {ev.frozen(current, RainbowAtrPresets.BEAR), ev.frozen(current, RainbowAtrPresets.BULL)};
        return new Tuned(t, from, preset, current, searches, ev, presetCycle, tunedCycle, presetGel, tunedGel, ev.replays(), guard);
    }

    // ------------------------------------------------------------------------------------------------------------ perturbations

    /** Perturbations acceptées par le garde-fou et nombre de tirages consommés. */
    public record Perturbations(List<RainbowAtrParamSet[]> sets, int draws) {
    }

    /**
     * Perturbations aléatoires du preset EN PLACE de MÊME AMPLITUDE que le réglage : sur les seuls réglages que le réglage a déplacés, chaque
     * valeur numérique est déplacée de la même distance normalisée (dans la plage de recherche) dans un sens tiré au hasard (réfléchi au
     * bord) ; un interrupteur / mode déplacé prend une autre option tirée au hasard. Mêmes tris de bornes, même région de recherche, même
     * garde-fou d'exposition sur la vue de réglage ; tirages refusés par le garde-fou redessinés (au plus {@code maxPerturbationDraws}).
     * Déterministe (graine du T).
     */
    public static Perturbations perturbations(Tuned tuned, RecentTuneConfig cfg) {
        SplittableRandom rng = new SplittableRandom(cfg.perturbationSeed(tuned.t()));
        SensitivityEvaluator ev = tuned.ev();
        double presetExposure = tuned.presetCycle().avgExposurePct();
        int window = ev.template().windowMonths();
        ParamSpace[] spaces = new ParamSpace[2];
        for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
            spaces[idx] = RecentTuneSpace.build(tuned.preset()[idx]);
        }
        List<RainbowAtrParamSet[]> out = new ArrayList<>();
        int draws = 0;
        boolean anyChange = tuned.changed();
        while (out.size() < cfg.perturbations() && draws < cfg.maxPerturbationDraws()) {
            draws++;
            RainbowAtrParamSet[] sets = tuned.preset().clone();
            if (anyChange) {
                for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
                    sets[idx] = perturbed(spaces[idx], tuned.preset()[idx], tuned.tuned()[idx], rng, window);
                }
            }
            if (sets[0] == null || sets[1] == null) {
                continue;
            }
            Reading r = ev.cycle(sets, false);
            if (r == null || BlockTuner.rejects(r.avgExposurePct(), presetExposure, cfg.exposureGuardFactor())) {
                continue;
            }
            out.add(sets);
        }
        return new Perturbations(out, draws);
    }

    private static RainbowAtrParamSet perturbed(ParamSpace space, RainbowAtrParamSet preset, RainbowAtrParamSet tuned, SplittableRandom rng,
                                                int window) {
        Candidate c = space.startCandidate();
        for (String key : RecentTuneSpace.diff(preset, tuned).keySet()) {
            ParamSpace.Param p = space.param(key);
            double from = p.start();
            double to = RecentTuneSpace.positionOf(space, key, tuned);
            double moved;
            if (p.kind() == ParamSpace.Kind.CATEGORICAL) {
                int n = p.choices().size();
                int pick = rng.nextInt(n - 1);
                moved = pick >= (int) from ? pick + 1 : pick;
            } else {
                double range = p.max() - p.min();
                double amplitude = Math.abs(to - from) / range;
                double u = (from - p.min()) / range;
                double sign = rng.nextBoolean() ? 1.0 : -1.0;
                double target = u + sign * amplitude;
                if (target < 0.0 || target > 1.0) {
                    target = u - sign * amplitude;
                }
                target = Math.clamp(target, 0.0, 1.0);
                moved = p.snap(p.min() + target * range);
            }
            c = c.with(key, moved);
        }
        return RecentTuneSpace.resolve(space, c, preset, window);
    }
}
