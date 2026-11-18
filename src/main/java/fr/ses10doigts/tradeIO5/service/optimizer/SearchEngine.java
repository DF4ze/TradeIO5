package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.Bump;
import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.Finalist;
import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.NeighborScore;
import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.Plateau;
import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.Robustness;
import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.Sensitivity;
import fr.ses10doigts.tradeIO5.service.optimizer.SearchResult.Timings;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Moteur de recherche headless, pur Java : tri par sensibilité (un paramètre à la fois) → échantillonnage Sobol déterministe →
 * successive halving (budget croissant : fenêtre courte en frais simplifiés, toutes fenêtres, coûts complets) → choix du
 * <b>plateau</b> (médiane du score du centre et de ses voisins à ±1 cran, jamais le maximum) → raffinement local → bousculades du
 * candidat retenu. Pas de montée coordonnée. Les évaluations tournent sur le pool fourni (jamais celui des simulations utilisateur) ;
 * les résultats sont consommés dans l'ordre de soumission, donc le rapport ne dépend pas de l'ordre d'achèvement.
 */
@Slf4j
public final class SearchEngine {

    private static final Comparator<Scored> BEST_FIRST = Comparator.comparingDouble((Scored s) -> s.evaluation().score()).reversed()
            .thenComparing(s -> s.candidate().key());

    private final ExecutorService pool;

    public SearchEngine(ExecutorService pool) {
        this.pool = pool;
    }

    private record Scored(Candidate candidate, Evaluation evaluation) {
    }

    /** @throws SearchControl.CancelledException si {@code control} est annulé */
    public SearchResult run(ParamSpace space, SearchObjective objective, SearchConfig cfg, SearchControl control) {
        long t0 = System.nanoTime();
        Session session = new Session(objective, control, estimateTotal(space, cfg, objective));
        Candidate start = space.startCandidate();

        List<Sensitivity> sensitivities = sensitivities(session, space, start, cfg);
        List<String> frozen = sensitivities.stream().filter(Sensitivity::frozen).map(Sensitivity::name).toList();
        ParamSpace searched = space.freezing(frozen);

        List<Finalist> finalists = new ArrayList<>();
        Plateau best;
        if (searched.free().isEmpty()) {
            best = plateauOf(session, searched, start, cfg);
            finalists.add(new Finalist(start, best.centerScore(), best.plateauScore(), best.size(), false));
        } else {
            List<Candidate> survivors = halving(session, searched, start, cfg);
            List<Plateau> plateaus = new ArrayList<>();
            for (Candidate c : survivors) {
                Plateau p = plateauOf(session, searched, c, cfg);
                plateaus.add(p);
                finalists.add(new Finalist(c, p.centerScore(), p.plateauScore(), p.size(), session.eval(c, Fidelity.ALL_WINDOWS_COMPLETE).vetoed()));
            }
            best = bestPlateau(plateaus);
            best = refine(session, searched, best, cfg, plateaus);
        }
        Candidate chosen = best.center();
        Evaluation chosenEval = session.eval(chosen, Fidelity.ALL_WINDOWS_COMPLETE);
        List<Bump> bumps = bumps(session, best, chosenEval, cfg);
        Robustness robustness = robustness(bumps, cfg);
        control.progress(1.0, "terminé");
        long totalMs = (System.nanoTime() - t0) / 1_000_000L;
        long trials = control.evaluations();
        Timings timings = new Timings(totalMs, session.evalNanos / 1_000_000L, trials == 0 ? 0.0 : session.evalNanos / 1e6 / trials);
        log.info("Recherche terminée : {} essais, plateau {} (score {}), robuste={}, {} ms", trials, chosen.key(),
                best.plateauScore(), robustness.robust(), totalMs);
        return new SearchResult(space, searched, cfg.seed(), sensitivities, finalists, best, chosen, chosenEval, bumps, robustness,
                trials, new TreeMap<>(session.vetoCounts), timings);
    }

    // ------------------------------------------------------------------ étapes

    private List<Sensitivity> sensitivities(Session s, ParamSpace space, Candidate start, SearchConfig cfg) {
        List<Sensitivity> out = new ArrayList<>();
        for (ParamSpace.Param p : space.free()) {
            s.control.throwIfCancelled();
            List<Candidate> points = new ArrayList<>();
            for (int i = 0; i < cfg.sensitivityPoints(); i++) {
                double v = p.snap(p.min() + (p.max() - p.min()) * i / (cfg.sensitivityPoints() - 1));
                Candidate c = start.with(p.name(), v);
                if (points.stream().noneMatch(x -> x.key().equals(c.key()))) {
                    points.add(c);
                }
            }
            List<Evaluation> evals = s.evalAll(points, Fidelity.ALL_WINDOWS_SIMPLIFIED, Scenario.BASE, "sensibilité " + p.name());
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            double bestValue = start.get(p.name());
            int vetoedPoints = 0;
            for (int i = 0; i < evals.size(); i++) {
                if (evals.get(i).vetoed()) {
                    vetoedPoints++;
                    continue;
                }
                double score = evals.get(i).score();
                min = Math.min(min, score);
                if (score > max) {
                    max = score;
                    bestValue = points.get(i).get(p.name());
                }
            }
            // l'effet se mesure entre jeux valides : un veto (score -1000) écraserait tout classement ; il est rapporté à part
            double effect = max >= min ? max - min : 0.0;
            if (max < min) {
                min = OptimizerDefaults.VETO_SCORE;
                max = OptimizerDefaults.VETO_SCORE;
            }
            out.add(new Sensitivity(p.name(), effect, min, max, bestValue, vetoedPoints, vetoedPoints == 0 && effect < cfg.freezeThreshold()));
        }
        out.sort(Comparator.comparingDouble(Sensitivity::effect).reversed().thenComparing(Sensitivity::name));
        return out;
    }

    /** Sobol → halving : retourne les finalistes (pleine fidélité évaluée), meilleurs d'abord. */
    private List<Candidate> halving(Session s, ParamSpace searched, Candidate start, SearchConfig cfg) {
        int dims = searched.free().size();
        SobolSequence sobol = new SobolSequence(dims, cfg.seed());
        List<Candidate> sample = new ArrayList<>();
        sample.add(start);
        for (int i = 0; i < cfg.sobolSamples(); i++) {
            Candidate c = searched.fromUnit(sobol.next(), start);
            if (sample.stream().noneMatch(x -> x.key().equals(c.key()))) {
                sample.add(c);
            }
        }
        List<Candidate> rung = keepBest(s, sample, Fidelity.SHORT_WINDOW_SIMPLIFIED, (int) Math.ceil(sample.size() / (double) cfg.halvingFactor()), "halving 12 m");
        int toComplete = Math.max(cfg.finalists(), 1);
        rung = keepBest(s, rung, Fidelity.ALL_WINDOWS_SIMPLIFIED, toComplete, "halving toutes fenêtres");
        return keepBest(s, rung, Fidelity.ALL_WINDOWS_COMPLETE, rung.size(), "finalistes coûts complets");
    }

    private List<Candidate> keepBest(Session s, List<Candidate> candidates, Fidelity fidelity, int keep, String stage) {
        List<Evaluation> evals = s.evalAll(candidates, fidelity, Scenario.BASE, stage);
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            scored.add(new Scored(candidates.get(i), evals.get(i)));
        }
        scored.sort(BEST_FIRST);
        return scored.stream().limit(Math.max(1, keep)).map(Scored::candidate).toList();
    }

    /** Plateau d'un candidat : médiane des scores (pleine fidélité) du centre et de ses voisins ±1 cran. */
    private Plateau plateauOf(Session s, ParamSpace searched, Candidate center, SearchConfig cfg) {
        List<Candidate> neighbors = searched.neighbors(center);
        List<Candidate> all = new ArrayList<>();
        all.add(center);
        all.addAll(neighbors);
        List<Evaluation> evals = s.evalAll(all, Fidelity.ALL_WINDOWS_COMPLETE, Scenario.BASE, "plateau");
        double centerScore = evals.getFirst().score();
        List<NeighborScore> neighborScores = new ArrayList<>();
        int size = 0;
        for (int i = 0; i < neighbors.size(); i++) {
            Evaluation e = evals.get(i + 1);
            neighborScores.add(new NeighborScore(neighbors.get(i), e.score(), e.vetoed()));
            if (!e.vetoed() && e.score() >= centerScore - cfg.plateauBand()) {
                size++;
            }
        }
        return new Plateau(center, centerScore, median(evals.stream().map(Evaluation::score).toList()), size, neighbors.size(),
                cfg.plateauBand(), neighborScores);
    }

    private static Plateau bestPlateau(List<Plateau> plateaus) {
        return plateaus.stream().max(Comparator.comparingDouble(Plateau::plateauScore).thenComparingInt(Plateau::size)
                .thenComparingDouble(Plateau::centerScore).thenComparing(p -> p.center().key(), Comparator.reverseOrder())).orElseThrow();
    }

    /** Raffinement local : Sobol resserré (±rayon crans) autour du centre ; les meilleurs voient leur plateau mesuré. */
    private Plateau refine(Session s, ParamSpace searched, Plateau best, SearchConfig cfg, List<Plateau> known) {
        if (cfg.refineSamples() == 0 || cfg.refineTop() == 0) {
            return best;
        }
        SobolSequence sobol = new SobolSequence(searched.free().size(), cfg.seed() + 1);
        List<Candidate> local = new ArrayList<>();
        for (int i = 0; i < cfg.refineSamples(); i++) {
            Candidate c = searched.fromUnit(sobol.next(), best.center(), cfg.refineRadiusSteps());
            if (local.stream().noneMatch(x -> x.key().equals(c.key()))) {
                local.add(c);
            }
        }
        List<Candidate> top = keepBest(s, local, Fidelity.ALL_WINDOWS_COMPLETE, cfg.refineTop(), "raffinement local");
        List<Plateau> all = new ArrayList<>(known);
        for (Candidate c : top) {
            all.add(plateauOf(s, searched, c, cfg));
        }
        return bestPlateau(all);
    }

    private List<Bump> bumps(Session s, Plateau best, Evaluation base, SearchConfig cfg) {
        List<Bump> out = new ArrayList<>();
        List<Scenario> scenarios = s.objective.bumpScenarios();
        for (Scenario scenario : scenarios) {
            s.control.throwIfCancelled();
            Evaluation e = s.evalAll(List.of(best.center()), Fidelity.ALL_WINDOWS_COMPLETE, scenario, "bousculade " + scenario.label()).getFirst();
            out.add(bump(scenario.label(), base.score(), e, cfg));
        }
        for (NeighborScore n : best.neighborScores()) {
            Evaluation e = new Evaluation(n.score(), n.vetoed(), List.of(), null);
            out.add(bump("voisin " + diff(best.center(), n.candidate()), base.score(), e, cfg));
        }
        return out;
    }

    private static String diff(Candidate a, Candidate b) {
        StringBuilder sb = new StringBuilder();
        a.values().forEach((k, v) -> {
            if (!v.equals(b.values().get(k))) {
                sb.append(k).append(b.get(k) > v ? "+" : "-");
            }
        });
        return sb.toString();
    }

    private static Bump bump(String label, double baseScore, Evaluation e, SearchConfig cfg) {
        double gap = Math.abs(baseScore - e.score()) / Math.max(Math.abs(baseScore), cfg.robustnessFloor());
        return new Bump(label, e.score(), e.vetoed(), gap, !e.vetoed() && gap <= cfg.robustnessTolerance());
    }

    private static Robustness robustness(List<Bump> bumps, SearchConfig cfg) {
        double worst = bumps.stream().mapToDouble(Bump::relativeGap).max().orElse(0.0);
        return new Robustness(cfg.robustnessTolerance(), cfg.robustnessFloor(), bumps.stream().allMatch(Bump::tolerated), worst);
    }

    static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static long estimateTotal(ParamSpace space, SearchConfig cfg, SearchObjective objective) {
        int free = space.free().size();
        long sens = (long) free * cfg.sensitivityPoints();
        long rung0 = cfg.sobolSamples() + 1L;
        long rung1 = (long) Math.ceil(rung0 / (double) cfg.halvingFactor());
        long plateau = (long) cfg.finalists() * (1 + 2L * free);
        long refine = cfg.refineSamples() + (long) cfg.refineTop() * (1 + 2L * free);
        return Math.max(1, sens + rung0 + rung1 + cfg.finalists() + plateau + refine + objective.bumpScenarios().size());
    }

    // ------------------------------------------------------------------ évaluation parallèle mémoïsée

    private final class Session {
        private final SearchObjective objective;
        private final SearchControl control;
        private final long planned;
        private final Map<String, Evaluation> memo = new HashMap<>();
        private final Map<String, Integer> vetoCounts = new LinkedHashMap<>();
        private long evalNanos;

        private Session(SearchObjective objective, SearchControl control, long planned) {
            this.objective = objective;
            this.control = control;
            this.planned = planned;
        }

        Evaluation eval(Candidate c, Fidelity f) {
            return evalAll(List.of(c), f, Scenario.BASE, "évaluation").getFirst();
        }

        /** Évalue les candidats (calculs en parallèle, résultats rendus dans l'ordre d'entrée) ; un couple déjà évalué n'est pas recalculé. */
        List<Evaluation> evalAll(List<Candidate> candidates, Fidelity fidelity, Scenario scenario, String stage) {
            control.throwIfCancelled();
            Map<String, Candidate> missing = new LinkedHashMap<>();
            for (Candidate c : candidates) {
                if (!memo.containsKey(memoKey(c, fidelity, scenario))) {
                    missing.putIfAbsent(c.key(), c);
                }
            }
            Map<String, Future<Evaluation>> futures = new LinkedHashMap<>();
            long t = System.nanoTime();
            for (Candidate c : missing.values()) {
                futures.put(c.key(), pool.submit(() -> {
                    control.throwIfCancelled();
                    return objective.evaluate(c, fidelity, scenario);
                }));
            }
            for (Map.Entry<String, Future<Evaluation>> e : futures.entrySet()) {
                Evaluation ev = await(e.getValue());
                memo.put(fidelity + "|" + scenario.label() + "|" + e.getKey(), ev);
                ev.vetoes().forEach(v -> vetoCounts.merge(v, 1, Integer::sum));
                long done = control.countEvaluation();
                control.progress(Math.min(0.99, done / (double) planned), stage);
            }
            evalNanos += System.nanoTime() - t;
            return candidates.stream().map(c -> memo.get(memoKey(c, fidelity, scenario))).toList();
        }

        private String memoKey(Candidate c, Fidelity f, Scenario s) {
            return f + "|" + s.label() + "|" + c.key();
        }

        private Evaluation await(Future<Evaluation> future) {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SearchControl.CancelledException();
            } catch (CancellationException e) {
                throw new SearchControl.CancelledException();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SearchControl.CancelledException c) {
                    throw c;
                }
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                throw new IllegalStateException(e.getCause());
            }
        }
    }
}
