package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.AssetCalibration;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.ProfileCalibration;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationReport.TestPoint;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Window;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyStudy.ConstantPick;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.AssetFrontier;
import fr.ses10doigts.tradeIO5.service.optimizer.FrontierReport.ProfileLevel;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Étape C3 : aucune donnée postérieure à T dans le calage, niveau sans marge = interpolation de C1 sur les 4 blocs, k = 1 = plafond brut,
 * fenêtres glissantes complètes, reproductibilité. Config réduite (6 niveaux, pas de 120 jours, 6 dates T) : même code que le run réel.
 */
class ExposureCalibrationTest {

    private static final LocalDate END = PresetOptimizerTestSupport.END;
    private static final double CASH = 10_000.0;
    private static final List<Double> GRID = List.of(0.5, 1.0, 2.0, 4.0, 8.0, 16.0);
    private static ScorerInputs inputs;
    private static ExposureCalibrationConfig cfg;
    private static ExecutorService pool;
    private static AssetCalibration report;

    @BeforeAll
    static void load() throws IOException {
        var link = PresetOptimizerTestSupport.link("BTC", PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE));
        inputs = ScorerInputs.of(List.of(link), CASH, END, StartingProfile.BALANCED, PresetOptimizerTestSupport.LIMITS);
        ExposureCalibrationConfig d = ExposureCalibrationConfig.defaults(END);
        cfg = new ExposureCalibrationConfig(d.parity(), GRID, 24, 120, 72, 12, 12, 12, List.of(1.0, 2.0), 0.9, d.bearFrom(), d.bearTo(), 90);
        pool = Executors.newFixedThreadPool(4);
        report = ExposureCalibrationStudy.run(inputs, cfg, pool);
    }

    @AfterAll
    static void close() {
        pool.shutdown();
    }

    private static List<Window> windows() {
        return ExposureCalibrationStudy.windowsFor(inputs, cfg);
    }

    @Test
    @DisplayName("Fenêtres glissantes : 24 mois pleins, pas de 30 jours, la dernière finit à lastEnd, aucune avant le préchauffage (aucune tronquée)")
    void windowsAreComplete() {
        LocalDate first = LocalDate.parse("2019-01-10");
        List<Window> w = ExposureCalibrationStudy.windows(END, 24, 30, first);
        assertFalse(w.isEmpty());
        assertEquals(END, w.getLast().to());
        for (int i = 0; i < w.size(); i++) {
            assertEquals(w.get(i).to().minusMonths(24).plusDays(1), w.get(i).from());
            assertFalse(w.get(i).from().isBefore(first), "fenêtre avant le préchauffage : " + w.get(i));
            if (i > 0) {
                assertEquals(30, java.time.temporal.ChronoUnit.DAYS.between(w.get(i - 1).to(), w.get(i).to()));
            }
        }
        // maximal : la fenêtre suivante (plus ancienne) commencerait avant le préchauffage
        assertTrue(w.getFirst().to().minusDays(30).minusMonths(24).plusDays(1).isBefore(first));
        // sur les vraies données : le replay couvre bien chaque fenêtre (aucune tronquée ni non rejouable)
        assertEquals(0, report.windows().truncated());
        assertEquals(0, report.windows().notReplayable());
        assertEquals(windows().size(), report.windows().count());
    }

    @Test
    @DisplayName("Niveau sans marge sur les 4 blocs de C1 = interpolation de C1 (même grille, même règle)")
    void noMarginOnC1BlocksEqualsC1() {
        FrontierConfig fc = new FrontierConfig(ParityConfig.defaults(END), GRID, List.of(), List.of(), 0.25);
        AssetFrontier c1 = ExposureFrontier.run(inputs, fc, pool);
        int compared = 0;
        for (ProfileLevel pl : c1.profiles()) {
            ProfileCalibration pc = report.profiles().stream().filter(p -> p.profile().equals(pl.profile())).findFirst().orElseThrow();
            assertEquals(pl.capPct(), pc.capPct(), 1e-12);
            if ("INTERPOLE".equals(pl.status()) || pl.status().startsWith("PLAFOND_NON_ATTEINT")) {
                assertEquals(pl.multiplier(), pc.c1().multiplier(), 1e-9, "niveau C1 du profil " + pl.profile());
                compared++;
            }
        }
        assertTrue(compared > 0, "au moins un profil comparable");
    }

    @Test
    @DisplayName("k = 1 redonne le plafond brut ; k > 1 divise le plafond ; un plafond plus strict ne monte jamais le niveau")
    void marginOneIsRawCap() {
        double[] worst = {-5.0, -10.0, -20.0, -35.0, -55.0, -70.0};
        double cap = -25.0;
        ConstantPick raw = ExposurePolicyStudy.constantMultiplier(GRID, worst, cap);
        assertEquals(raw, ExposureCalibrationStudy.pick(GRID, worst, cap, 1.0));
        assertEquals(ExposurePolicyStudy.constantMultiplier(GRID, worst, cap / 2.0), ExposureCalibrationStudy.pick(GRID, worst, cap, 2.0));
        double previous = Double.MAX_VALUE;
        for (double k : List.of(1.0, 1.1, 1.25, 1.5, 2.0)) {
            double m = ExposureCalibrationStudy.pick(GRID, worst, cap, k).multiplier();
            assertTrue(m <= previous + 1e-12, "niveau en hausse avec k = " + k);
            previous = m;
        }
        // interpolation linéaire entre ×2 (−20) et ×4 (−35) pour −25 : poids 1/3
        assertEquals(2.0 + 2.0 / 3.0, raw.multiplier(), 1e-9);
        assertEquals("INTERPOLE", raw.status());
        assertEquals("SOUS_LE_MINIMUM", ExposureCalibrationStudy.pick(GRID, worst, -1.0, 1.0).status());
        assertEquals("PLAFOND_NON_ATTEINT", ExposureCalibrationStudy.pick(GRID, worst, -90.0, 1.0).status());
    }

    @Test
    @DisplayName("Le calage à T ne lit aucune fenêtre qui finit à partir de T (lignes empoisonnées sans effet) ni l'historique postérieur")
    void calibrationReadsNothingAfterAnchor() {
        List<Window> w = windows();
        LocalDate t = LocalDate.parse("2023-09-01");
        double[][] dd = new double[w.size()][GRID.size()];
        double[][] poisoned = new double[w.size()][GRID.size()];
        for (int i = 0; i < w.size(); i++) {
            for (int g = 0; g < GRID.size(); g++) {
                dd[i][g] = -(1 + i) * (g + 1) * 0.1;
                poisoned[i][g] = w.get(i).to().isBefore(t) ? dd[i][g] : -999.0;
            }
        }
        double[] full = new double[GRID.size()];
        java.util.Arrays.fill(full, -1.0);
        double[] a = ExposureCalibrationStudy.worstByLevel(w, dd, full, t);
        double[] b = ExposureCalibrationStudy.worstByLevel(w, poisoned, full, t);
        assertNotNull(a);
        assertTrue(java.util.Arrays.equals(a, b), "une fenêtre postérieure à T a servi au calage");
        // sans aucune fenêtre antérieure : seul l'historique clos à T − 1 contribue
        double[] onlyFull = ExposureCalibrationStudy.worstByLevel(w, poisoned, full, w.getFirst().to());
        assertTrue(java.util.Arrays.equals(full, onlyFull));
        for (TestPoint p : report.profiles().getFirst().margins().getFirst().points()) {
            long usable = w.stream().filter(x -> x.to().isBefore(p.anchor())).count();
            assertTrue(p.calibWindows() <= usable, "plus de fenêtres de calage que de fenêtres terminées avant " + p.anchor());
        }
    }

    @Test
    @DisplayName("Bougies tronquées à T − 1 : les fenêtres terminées avant T se rejouent à l'identique (aucune dépendance au futur dans Trend / indicateurs)")
    void replaysBeforeAnchorDoNotDependOnLaterCandles() throws IOException {
        LocalDate t = LocalDate.parse("2023-09-01");
        List<MarketData> all = PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE);
        List<MarketData> cut = all.stream().filter(c -> c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate().isBefore(t)).toList();
        assertTrue(cut.size() < all.size());
        ScorerInputs truncated = ScorerInputs.of(List.of(PresetOptimizerTestSupport.link("BTC", cut)), CASH, t.minusDays(1), StartingProfile.BALANCED,
                PresetOptimizerTestSupport.LIMITS);
        List<Window> before = windows().stream().filter(w -> w.to().isBefore(t)).toList();
        assertFalse(before.isEmpty());
        List<Window> picked = List.of(before.getFirst(), before.get(before.size() / 2), before.getLast());
        for (Window w : picked) {
            SensitivityEvaluator full = new SensitivityEvaluator(inputs.withEndDate(w.to()), 24, w.from());
            SensitivityEvaluator trunc = new SensitivityEvaluator(truncated.withEndDate(w.to()), 24, w.from());
            RainbowAtrParamSet[] sets = full.template().sets();
            double ref = full.referenceBase(sets);
            for (double m : List.of(1.0, 4.0)) {
                Parity a = full.parity(sets, false, 0, null, ExposureFrontier.linkBase(m, ref));
                Parity b = trunc.parity(trunc.template().sets(), false, 0, null, ExposureFrontier.linkBase(m, trunc.referenceBase(trunc.template().sets())));
                assertNotNull(a);
                assertEquals(a, b, "fenêtre " + w.label() + " ×" + m + " : le résultat dépend de bougies postérieures");
            }
        }
        // l'historique complet clos à T − 1 se rejoue aussi à l'identique
        SensitivityEvaluator f1 = new SensitivityEvaluator(inputs.withEndDate(t.minusDays(1)), 24);
        SensitivityEvaluator f2 = new SensitivityEvaluator(truncated, 24);
        assertEquals(f1.parity(f1.template().sets(), true, 0, null), f2.parity(f2.template().sets(), true, 0, null));
    }

    @Test
    @DisplayName("r(T), tenue du plafond, choix de k et autonomie équivalente (fonctions pures)")
    void pureRules() {
        assertEquals(1.2, ExposureCalibrationStudy.ratio(-30.0, -25.0), 1e-12);
        assertEquals(0.0, ExposureCalibrationStudy.ratio(0.0, -25.0), 1e-12);
        assertTrue(ExposureCalibrationStudy.holds(-25.0, -25.0));
        assertFalse(ExposureCalibrationStudy.holds(-25.5, -25.0));
        assertEquals(2, ExposureCalibrationStudy.firstSufficient(new double[]{0.5, 0.8, 0.9, 1.0}, 0.9));
        assertEquals(-1, ExposureCalibrationStudy.firstSufficient(new double[]{0.5, Double.NaN, 0.89}, 0.9));
        // autonomie = cash / (multiplicateur moyen × ×m × base ×1)
        assertEquals(2484.0 / (1.2 * 10.0), ExposureCalibrationStudy.equivalentAutonomy(2484.0, 1.2, 1.0, 10.0), 1e-9);
        assertEquals(2484.0 / (1.2 * 2.0 * 10.0), ExposureCalibrationStudy.equivalentAutonomy(2484.0, 1.2, 2.0, 10.0), 1e-9);
        assertNull(ExposureCalibrationStudy.equivalentAutonomy(2484.0, null, 1.0, 10.0));
    }

    @Test
    @DisplayName("Rapport : mêmes dates T (6), k = 1 mesuré partout, plus la marge est forte plus le plafond tient, plafonds de ModulationDefaults")
    void reportInvariants() {
        assertEquals(cfg.anchors().size(), report.anchors());
        assertEquals(4, report.profiles().size());
        for (ProfileCalibration p : report.profiles()) {
            assertEquals(FrontierConfig.profileCaps().get(StartingProfile.valueOf(p.profile())), p.capPct(), 1e-12);
            assertEquals(2, p.margins().size());
            assertEquals(1.0, p.margins().getFirst().k(), 1e-12);
            assertEquals(p.capPct(), p.margins().getFirst().capUsedPct(), 1e-12);
            assertEquals(p.capPct() / 2.0, p.margins().getLast().capUsedPct(), 1e-12);
            assertTrue(p.margins().getFirst().tested() > 0);
            assertEquals(p.margins().getFirst().tested(), p.margins().getLast().tested(), "mêmes dates T testées pour toutes les marges");
        }
        // profil plus prudent => niveau sans marge plus bas
        List<ProfileCalibration> ps = report.profiles();
        for (int i = 1; i < ps.size(); i++) {
            assertTrue(ps.get(i).noMargin().multiplier() >= ps.get(i - 1).noMargin().multiplier() - 1e-9);
        }
    }

    @Test
    @DisplayName("Reproductibilité : série et pool donnent le même JSON (hors durées)")
    void reproducible() {
        AssetCalibration serial = ExposureCalibrationStudy.run(inputs, cfg, null);
        assertEquals(new ExposureCalibrationReport("d", cfg, List.of(report), List.of()).toJsonWithoutTimings(),
                new ExposureCalibrationReport("d", cfg, List.of(serial), List.of()).toJsonWithoutTimings());
    }
}
