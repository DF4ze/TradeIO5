package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.ParamSpace.Param;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moteur de recherche sur fonctions synthétiques : aucun simulateur, aucune base. */
class SearchEngineTest {

    private static final SearchConfig CONFIG = SearchConfig.defaults();

    private static SearchObjective of(ToDoubleFunction<Candidate> f, AtomicLong calls) {
        return (c, fidelity, scenario) -> {
            if (calls != null) {
                calls.incrementAndGet();
            }
            return Evaluation.ok(f.applyAsDouble(c), null);
        };
    }

    private static SearchResult run(ParamSpace space, SearchObjective objective, SearchConfig config, SearchControl control) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            return new SearchEngine(pool).run(space, objective, config, control);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ParamSpace quadraticSpace() {
        return new ParamSpace(List.of(Param.continuous("a", 0, 10, 0.5, 2), Param.continuous("b", 0, 10, 0.5, 8),
                Param.continuous("noEffect", 0, 10, 1, 5)));
    }

    private static double quadratic(Candidate c) {
        return 100 - Math.pow(c.get("a") - 6, 2) - Math.pow(c.get("b") - 3, 2);
    }

    @Test
    void findsKnownOptimum() {
        SearchResult r = run(quadraticSpace(), of(SearchEngineTest::quadratic, null), CONFIG, SearchControl.none());
        assertTrue(Math.abs(r.chosen().get("a") - 6) <= 1.0, "a=" + r.chosen().get("a"));
        assertTrue(Math.abs(r.chosen().get("b") - 3) <= 1.0, "b=" + r.chosen().get("b"));
        assertTrue(r.plateau().size() >= 1);
    }

    @Test
    void plateauBeatsIsolatedSpike() {
        ParamSpace space = new ParamSpace(List.of(Param.continuous("a", 0, 10, 0.5, 5)));
        // large plateau autour de 3, pic isolé (+40) en a = 9 dont les voisins sont bas
        SearchResult r = run(space, of(c -> {
            double a = c.get("a");
            return 50 - 0.2 * Math.pow(a - 3, 2) + (a == 9.0 ? 40 : 0);
        }, null), CONFIG, SearchControl.none());
        assertTrue(r.chosen().get("a") < 6, "pic isolé retenu : a=" + r.chosen().get("a"));
    }

    @Test
    void noEffectParametersAreFrozenAndSensitivitiesOrdered() {
        SearchResult r = run(quadraticSpace(), of(SearchEngineTest::quadratic, null), CONFIG, SearchControl.none());
        SearchResult.Sensitivity noEffect = r.sensitivities().stream().filter(s -> s.name().equals("noEffect")).findFirst().orElseThrow();
        assertTrue(noEffect.frozen());
        assertTrue(r.searchedSpace().param("noEffect").frozen());
        assertFalse(r.searchedSpace().param("a").frozen());
        assertEquals("noEffect", r.sensitivities().getLast().name());
        for (int i = 1; i < r.sensitivities().size(); i++) {
            assertTrue(r.sensitivities().get(i - 1).effect() >= r.sensitivities().get(i).effect());
        }
    }

    @Test
    void vetoesDoNotDominateSensitivities() {
        ParamSpace space = new ParamSpace(List.of(Param.continuous("a", 0, 10, 0.5, 2), Param.continuous("v", 0, 10, 0.5, 5)));
        SearchObjective objective = (c, f, s) -> c.get("v") > 8 ? new Evaluation(OptimizerDefaults.VETO_SCORE, true, List.of("DRAWDOWN"), null)
                : Evaluation.ok(50 - Math.pow(c.get("a") - 6, 2) / 10, null);
        SearchResult r = run(space, objective, CONFIG, SearchControl.none());
        SearchResult.Sensitivity v = r.sensitivities().stream().filter(s -> s.name().equals("v")).findFirst().orElseThrow();
        assertTrue(v.effect() < 1.0, "effet gonflé par les vetos : " + v.effect());
        assertTrue(v.vetoedPoints() > 0);
        assertFalse(v.frozen());
        assertEquals("a", r.sensitivities().getFirst().name());
    }

    @Test
    void fixedSeedGivesIdenticalRuns() {
        SearchResult r1 = run(quadraticSpace(), of(SearchEngineTest::quadratic, null), CONFIG, SearchControl.none());
        SearchResult r2 = run(quadraticSpace(), of(SearchEngineTest::quadratic, null), CONFIG, SearchControl.none());
        assertEquals(r1.chosen(), r2.chosen());
        assertEquals(r1.trials(), r2.trials());
        assertEquals(r1.sensitivities(), r2.sensitivities());
        assertEquals(r1.finalists(), r2.finalists());
        assertEquals(r1.plateau(), r2.plateau());
        assertEquals(r1.bumps(), r2.bumps());
    }

    @Test
    void trialCounterMatchesEvaluationsExactly() {
        AtomicLong calls = new AtomicLong();
        SearchResult r = run(quadraticSpace(), of(SearchEngineTest::quadratic, calls), CONFIG, SearchControl.none());
        assertEquals(calls.get(), r.trials());
        assertTrue(r.trials() > 0);
    }

    @Test
    void cancellationStopsTheSearch() {
        SearchControl control = new SearchControl(null);
        AtomicLong calls = new AtomicLong();
        SearchObjective objective = (c, f, s) -> {
            if (calls.incrementAndGet() == 10) {
                control.cancel();
            }
            return Evaluation.ok(quadratic(c), null);
        };
        assertThrows(SearchControl.CancelledException.class, () -> run(quadraticSpace(), objective, CONFIG, control));
        assertTrue(calls.get() < 200, "recherche non interrompue : " + calls.get());
    }

    @Test
    void sobolIsDeterministicAndInUnitCube() {
        SobolSequence s1 = new SobolSequence(3, 42L);
        SobolSequence s2 = new SobolSequence(3, 42L);
        for (int i = 0; i < 50; i++) {
            double[] p1 = s1.next();
            double[] p2 = s2.next();
            assertEquals(3, p1.length);
            for (int d = 0; d < 3; d++) {
                assertEquals(p1[d], p2[d]);
                assertTrue(p1[d] >= 0.0 && p1[d] < 1.0);
            }
        }
    }
}
