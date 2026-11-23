package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.AssetDiagnostic;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.SwitchRow;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticStudy.Series;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Étape E0 : détection causale, mesures sur séries synthétiques à résultat connu, reproductibilité, switch ouvert, verdict. */
class SwitchDiagnosticTest {

    private static final int BEAR = RainbowAtrPresets.BEAR;
    private static final int BULL = RainbowAtrPresets.BULL;
    private static final LocalDate START = LocalDate.of(2020, 1, 1);
    private static final double FEE = 0.4;
    private static final SwitchDiagnosticConfig CFG = SwitchDiagnosticConfig.defaults(LocalDate.of(2030, 1, 1));

    private static Series series(double[] close, int[] set) {
        LocalDate[] days = new LocalDate[close.length];
        for (int i = 0; i < days.length; i++) {
            days[i] = START.plusDays(i);
        }
        return new Series("SYN", "TEST", days, close, set, 0);
    }

    private static CandleFingerprint fp(Series s) {
        return new CandleFingerprint("SYN", s.days()[0], s.days()[s.close().length - 1], s.close().length, "test");
    }

    /**
     * Série à résultat connu (400 jours) : 3 switchs.
     * #1 S = 50 (clôture 180, plus haut 198) : baisse linéaire jusqu'à 100 (jour 130), retour Bull en 131 (110).
     * #2 S = 300 (270, plus haut 278 depuis le retour Bull du jour 131) : nouveau plus haut dès 301 (290) = faux switch, retour 305.
     * #3 S = 350, jamais de retour : switch ouvert, fenêtres 90 / 180 / 60 j hors données.
     */
    private static Series known() {
        double[] c = new double[400];
        int[] set = new int[400];
        for (int i = 0; i < 400; i++) {
            set[i] = BULL;
            if (i <= 49) {
                c[i] = 100 + 2 * i;
            } else if (i <= 130) {
                c[i] = 180 - (i - 50);
                set[i] = BEAR;
            } else if (i < 300) {
                c[i] = 110 + (i - 131);
            } else if (i == 300) {
                c[i] = 270;
                set[i] = BEAR;
            } else if (i <= 304) {
                c[i] = 290 + (i - 301);
                set[i] = BEAR;
            } else if (i < 350) {
                c[i] = 300 + (i - 305);
            } else {
                c[i] = 330 - (i - 350);
                set[i] = BEAR;
            }
        }
        return series(c, set);
    }

    /** Cycles Bull (+0,5 %/j) / Bear (facteur {@code bearFactor}/j), terminée par une longue queue Bull. */
    private static Series cycles(int count, double bearFactor) {
        List<Double> c = new ArrayList<>();
        List<Integer> set = new ArrayList<>();
        double p = 100;
        for (int k = 0; k < count; k++) {
            for (int i = 0; i < 150; i++) {
                p *= 1.005;
                c.add(p);
                set.add(BULL);
            }
            for (int i = 0; i < 60; i++) {
                p *= bearFactor;
                c.add(p);
                set.add(BEAR);
            }
        }
        for (int i = 0; i < 400; i++) {
            p *= 1.005;
            c.add(p);
            set.add(BULL);
        }
        return series(c.stream().mapToDouble(Double::doubleValue).toArray(), set.stream().mapToInt(Integer::intValue).toArray());
    }

    @Test
    @DisplayName("Détection : les jours Bull → Bear de la série connue")
    void detectsSwitches() {
        Series s = known();
        assertEquals(List.of(50, 300, 350), SwitchDiagnosticStudy.bullToBear(s.set(), 0, 399));
        assertEquals(131, SwitchDiagnosticStudy.nextBull(s.set(), 50, 399));
        assertEquals(305, SwitchDiagnosticStudy.nextBull(s.set(), 300, 399));
        assertEquals(-1, SwitchDiagnosticStudy.nextBull(s.set(), 350, 399));
    }

    @Test
    @DisplayName("Aucune donnée > S pour définir S : la détection sur une série tronquée à S redonne S")
    void noFutureDataForSwitch() {
        Series s = known();
        for (int sw : SwitchDiagnosticStudy.bullToBear(s.set(), 0, 399)) {
            int[] cut = Arrays.copyOf(s.set(), sw + 1);
            List<Integer> found = SwitchDiagnosticStudy.bullToBear(cut, 0, sw);
            assertEquals(sw, found.getLast());
        }
    }

    @Test
    @DisplayName("Mesures sur la série connue : consommée, baisse restante, rendements, faux switch, gain de coupure, durée")
    void measuresKnownSeries() {
        AssetDiagnostic a = SwitchDiagnosticStudy.analyze(known(), CFG, fp(known()), true);
        assertEquals(3, a.nSwitches());
        assertEquals(2, a.nClosed());
        assertEquals(1, a.nOpen());
        SwitchRow r = a.switches().getFirst();
        assertEquals(LocalDate.of(2020, 1, 1).plusDays(50), r.day());
        assertEquals(180.0, r.price(), 1e-9);
        assertEquals((180.0 / 198.0 - 1) * 100, r.consumedPct(), 1e-9);
        assertEquals((100.0 / 180.0 - 1) * 100, r.remaining90Pct(), 1e-9);
        assertEquals((100.0 / 180.0 - 1) * 100, r.remaining180Pct(), 1e-9);
        assertEquals((150.0 / 180.0 - 1) * 100, r.ret30Pct(), 1e-9);
        assertEquals((119.0 / 180.0 - 1) * 100, r.ret90Pct(), 1e-9);
        assertEquals((209.0 / 180.0 - 1) * 100, r.ret180Pct(), 1e-9);
        assertEquals(Boolean.FALSE, r.falseSwitch());
        assertEquals((180.0 / 110.0 - 1) * 100, r.cutGrossPct(), 1e-9);
        assertEquals(((1 - 0.004) / (1 + 0.004) * 180.0 / 110.0 - 1) * 100, r.cutNetPct(), 1e-9);
        assertTrue(r.cutNetPct() < r.cutGrossPct());
        assertEquals(81, r.bearDays());
        assertFalse(r.open());
        SwitchRow faux = a.switches().get(1);
        assertEquals(Boolean.TRUE, faux.falseSwitch());
        assertEquals((270.0 / 278.0 - 1) * 100, faux.consumedPct(), 1e-9); // plus haut depuis le dernier retour Bull (jour 131)
    }

    @Test
    @DisplayName("Switch ouvert : exclu des médianes de coupure et de la durée, mesures hors données = null (jamais 0)")
    void openSwitchExcluded() {
        AssetDiagnostic a = SwitchDiagnosticStudy.analyze(known(), CFG, fp(known()), true);
        SwitchRow open = a.switches().getLast();
        assertTrue(open.open());
        assertNull(open.cutGrossPct());
        assertNull(open.cutNetPct());
        assertNull(open.bearDays());
        assertNull(open.remaining90Pct());
        assertNull(open.ret90Pct());
        assertNull(open.falseSwitch());
        var net = a.summaries().stream().filter(x -> x.measure().startsWith("Gain de coupure net")).findFirst().orElseThrow();
        assertEquals(2, net.nSwitches());
        var dur = a.summaries().stream().filter(x -> x.measure().startsWith("Durée")).findFirst().orElseThrow();
        assertEquals(2, dur.nSwitches());
        assertEquals(2, a.verdict().nClosed());
    }

    @Test
    @DisplayName("Reproductibilité : même graine = même rapport à l'octet ; autre graine = autre hasard")
    void reproducible() {
        Series s = cycles(6, 0.99);
        String one = new SwitchDiagnosticReport(SwitchDiagnosticReport.DEFINITION, CFG, List.of(SwitchDiagnosticStudy.analyze(s, CFG, fp(s), true)),
                List.of()).toJson();
        String two = new SwitchDiagnosticReport(SwitchDiagnosticReport.DEFINITION, CFG, List.of(SwitchDiagnosticStudy.analyze(s, CFG, fp(s), true)),
                List.of()).toJson();
        assertEquals(one, two);
        SwitchDiagnosticConfig other = new SwitchDiagnosticConfig(CFG.end(), CFG.seed() + 1, CFG.nullDraws(), CFG.shortHorizon(), CFG.longHorizon(),
                CFG.returnHorizons(), CFG.falseSwitchDays(), CFG.feePct(), CFG.minSwitches(), CFG.minNetPositiveShare(), CFG.phaseLookbackDays());
        String three = new SwitchDiagnosticReport(SwitchDiagnosticReport.DEFINITION, other, List.of(SwitchDiagnosticStudy.analyze(s, other, fp(s), true)),
                List.of()).toJson();
        assertNotEquals(one, three);
        assertTrue(one.contains("\"seed\" : " + CFG.seed()));
    }

    @Test
    @DisplayName("Verdict : N < 5 = NON_CONCLUANT (jamais « fermée »)")
    void verdictNotConclusiveBelowFive() {
        AssetDiagnostic a = SwitchDiagnosticStudy.analyze(known(), CFG, fp(known()), true);
        assertEquals("NON_CONCLUANT", a.verdict().decision());
        assertTrue(a.verdict().causes().isEmpty());
    }

    @Test
    @DisplayName("Verdict : coupure gagnante sur 6 cycles baissiers = PISTE_OUVERTE")
    void verdictOpen() {
        Series s = cycles(6, 0.99);
        AssetDiagnostic a = SwitchDiagnosticStudy.analyze(s, CFG, fp(s), true);
        assertEquals(6, a.nClosed());
        assertEquals("PISTE_OUVERTE", a.verdict().decision(), a.verdict().toString());
        assertTrue(a.verdict().conditionA() && a.verdict().conditionB() && a.verdict().conditionC());
        assertEquals(0.0, a.falseSwitchShare(), 1e-9);
    }

    @Test
    @DisplayName("Verdict : faux switchs (prix qui monte en Bear) = PISTE_FERMÉE, cause (a)")
    void verdictClosedByFalseSwitches() {
        Series s = cycles(6, 1.005);
        AssetDiagnostic a = SwitchDiagnosticStudy.analyze(s, CFG, fp(s), true);
        assertEquals("PISTE_FERMÉE", a.verdict().decision());
        assertTrue(a.verdict().causes().contains("a"));
        assertEquals(100.0, a.falseSwitchShare(), 1e-9);
    }

    @Test
    @DisplayName("Faux switch : plus haut depuis le dernier retour Bull, pas l'ATH de l'historique")
    void falseSwitchUsesLocalHigh() {
        double[] c = new double[200];
        java.util.Arrays.fill(c, 100);
        c[10] = 1000; // ATH ancien
        c[50] = 120;  // plus haut local (retour Bull en 40)
        c[60] = 110;  // S
        c[70] = 125;  // nouveau plus haut local, très sous l'ATH
        assertEquals(Boolean.TRUE, SwitchDiagnosticStudy.falseSwitch(c, 40, 60, 60, 199));
        assertEquals(Boolean.FALSE, SwitchDiagnosticStudy.falseSwitch(c, 0, 60, 60, 199));
        assertNull(SwitchDiagnosticStudy.falseSwitch(c, 0, 160, 60, 199));
    }

    @Test
    @DisplayName("Rang et gain net : rang = part du hasard ≤ valeur ; frais aller-retour retranchés")
    void rankAndNet() {
        assertEquals(50.0, SwitchDiagnosticStudy.rank(List.of(1.0, 2.0, 3.0, 4.0), 2.0), 1e-9);
        assertNull(SwitchDiagnosticStudy.rank(List.of(), 2.0));
        assertEquals(10.0, SwitchDiagnosticStudy.cutPct(110, 100, 0.0), 1e-9);
        assertTrue(SwitchDiagnosticStudy.cutPct(110, 100, 0.004) < 10.0);
        assertEquals(FEE, CFG.feePct(), 1e-12);
    }

    @Test
    @DisplayName("BTC réel : régime du rejeu (même code), aucun futur dans le régime au switch, repères de Clem listés")
    void realBtc() throws IOException {
        List<MarketData> candles = PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE);
        var link = PresetOptimizerTestSupport.link("BTC", candles);
        Series s = SwitchDiagnosticStudy.series(link);
        // mêmes séries de régimes que le rejeu : la série vient de LinkSignalReplay.regimes / setOfBar, utilisés par son constructeur
        LinkSignalReplay.Config cfg = link.template();
        assertTrue(Arrays.equals(LinkSignalReplay.setOfBar(cfg, LinkSignalReplay.regimes(cfg)), s.set()));
        List<Integer> switches = SwitchDiagnosticStudy.bullToBear(s.set(), s.histStart(), s.close().length - 1);
        assertFalse(switches.isEmpty());
        // causalité : le jeu aux jours S - 1 et S est identique quand les bougies sont tronquées à S
        for (int sw : switches) {
            Series cut = SwitchDiagnosticStudy.series(PresetOptimizerTestSupport.link("BTC", candles.subList(0, sw + 1)));
            assertEquals(s.set()[sw], cut.set()[sw], "futur lu au jour " + s.days()[sw]);
            assertEquals(s.set()[sw - 1], cut.set()[sw - 1]);
            assertEquals(BEAR, cut.set()[sw]);
        }
        AssetDiagnostic a = SwitchDiagnosticStudy.analyze(s, CFG, CandleFingerprint.of("BTC", candles, CFG.end()), true);
        assertEquals(switches.size(), a.nSwitches());
        assertEquals(2, a.landmarks().size());
        a.landmarks().forEach(l -> assertNotNull(l.note()));
        assertTrue(a.switches().stream().allMatch(x -> x.consumedPct() <= 1e-9));
    }
}
