package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos;
import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.EpisodeDto;
import fr.ses10doigts.tradeIO5.service.expert.simulation.EpisodeTimeline.AssetSeries;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.TradeDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Timeline des épisodes : PnL au prix moyen et répartition additive du gain")
class EpisodeTimelineTest {

    private static final LocalDate D0 = LocalDate.parse("2026-01-01");
    private static final double EPS = 1e-9;

    private static TradeDay day(int i, int zone, String regime, double price, double dq, double dcash, double cost) {
        return new TradeDay(1L, "BTC", D0.plusDays(i), zone, regime, price, dq, dcash, cost, 0, 0, 0);
    }

    private static TradeDay withRef(TradeDay t, double fq, double fcash, double fcost) {
        return new TradeDay(t.bindingId(), t.asset(), t.day(), t.zone(), t.regime(), t.price(), t.qtyDelta(), t.cashDelta(),
                t.costUsdc(), fq, fcash, fcost);
    }

    private static EpisodeTimeline.Report run(List<TradeDay> days, double qty0, LocalDate from, LocalDate to) {
        return EpisodeTimeline.compute(List.of(new AssetSeries("BTC", days, qty0, Map.of())), from, to);
    }

    @Test
    @DisplayName("Zone neutre : PnL = variation du latent")
    void neutralZoneIsLatentVariation() {
        EpisodeTimeline.Report r = run(List.of(day(0, 4, "BULL", 100, 0, 0, 0), day(1, 4, "BULL", 110, 0, 0, 0),
                day(2, 4, "BULL", 120, 0, 0, 0)), 1.0, null, null);
        assertEquals(1, r.episodes().size());
        EpisodeDto e = r.episodes().getFirst();
        assertEquals(0.0, e.pnlRealized(), EPS);
        assertEquals(20.0, e.pnlLatent(), EPS);
        assertEquals(20.0, e.pnlTotal(), EPS);
        assertEquals("pas d'achat", e.zoneLabel());
        assertEquals(3, e.days());
    }

    @Test
    @DisplayName("Achat puis vente partielle : prix moyen pondéré, réalisé, changement de zone = nouvel épisode")
    void buyThenPartialSell() {
        EpisodeTimeline.Report r = run(List.of(
                day(0, 2, "BULL", 100, 1.0, -100, 0), day(1, 2, "BULL", 110, 0, 0, 0),
                day(2, 3, "BULL", 120, -0.5, 60, 0), day(3, 3, "BULL", 130, 0, 0, 0)), 0.0, null, null);
        assertEquals(2, r.episodes().size());
        EpisodeDto a = r.episodes().get(0);
        EpisodeDto b = r.episodes().get(1);
        assertEquals(100.0, a.boughtUsdc(), EPS);
        assertEquals(10.0, a.pnlTotal(), EPS); // latent 110 - 100
        assertEquals(10.0, b.pnlRealized(), EPS); // 60 - 100 * 0,5
        assertEquals(5.0, b.pnlLatent(), EPS); // latent 15 - 10
        assertEquals(60.0, b.soldUsdc(), EPS);
        assertEquals(100.0, b.avgCostEnd(), EPS);
        // invariant : Σ PnL = variation de valeur (cash -40 + 0,5 × 130 = 25)
        assertEquals(25.0, r.pnlTotal(), EPS);
    }

    @Test
    @DisplayName("Bascule Bull/Bear sans changement de zone coupe l'épisode ; régime nul = épisode INACTIF")
    void regimeSwitchAndInactive() {
        EpisodeTimeline.Report r = run(List.of(day(0, 2, "BULL", 100, 0, 0, 0), day(1, 2, "BEAR", 100, 0, 0, 0),
                day(2, 2, null, 100, 0, 0, 0), day(3, 2, null, 100, 0, 0, 0)), 1.0, null, null);
        assertEquals(List.of("BULL", "BEAR", EpisodeDtos.INACTIVE), r.episodes().stream().map(EpisodeDto::regime).toList());
        assertEquals(2, r.episodes().get(2).days());
        assertEquals(3, r.aggregates().size());
    }

    @Test
    @DisplayName("Épisode tronqué aux bords de la plage et sur sous-plage (prix de revient d'ouverture = cours du 1er jour)")
    void truncatedOnSubRange() {
        List<TradeDay> days = List.of(day(0, 2, "BULL", 100, 1.0, -100, 0), day(1, 2, "BULL", 110, 0, 0, 0),
                day(2, 2, "BULL", 120, 0, 0, 0), day(3, 2, "BULL", 90, 0, 0, 0));
        EpisodeTimeline.Report r = run(days, 0.0, D0.plusDays(1), D0.plusDays(2));
        assertEquals(1, r.episodes().size());
        EpisodeDto e = r.episodes().getFirst();
        assertEquals(D0.plusDays(1), e.from());
        assertEquals(D0.plusDays(2), e.to());
        assertEquals(10.0, e.pnlTotal(), EPS); // position ouverte à 110, fin à 120
        assertEquals(0.0, e.boughtUsdc(), EPS);
    }

    @Test
    @DisplayName("Frais : coût de la stratégie vs référence, exposition/timing/frais exactement additifs (propriété)")
    void gainIsExactlyAdditive() {
        Random rnd = new Random(42);
        for (int run = 0; run < 50; run++) {
            int n = 40;
            List<TradeDay> days = new ArrayList<>();
            double price = 100;
            double qS = 0.5;
            double qF = 0.5;
            double cashS = 0;
            double cashF = 0;
            for (int i = 0; i < n; i++) {
                price *= 1 + (rnd.nextDouble() - 0.5) * 0.1;
                double dq = 0;
                double dc = 0;
                double cost = 0;
                double slip = rnd.nextDouble() * 0.4;
                if (rnd.nextInt(3) == 0) {
                    double amt = 10 + rnd.nextInt(90);
                    cost = amt * 0.002;
                    dc = -amt;
                    dq = (amt - cost) / (price * (1 + slip / 100));
                } else if (rnd.nextInt(4) == 0 && qS > 0.1) {
                    dq = -qS * 0.2;
                    cost = -dq * price * 0.002;
                    dc = -dq * price - cost;
                }
                double fq = 0;
                double fc = 0;
                double fcost = 0;
                if (i % 5 == 0) {
                    fc = -30;
                    fcost = 30 * 0.002;
                    fq = (30 - fcost) / price;
                }
                days.add(new TradeDay(1L, "BTC", D0.plusDays(i), rnd.nextInt(6), rnd.nextBoolean() ? "BULL" : "BEAR", price, dq, dc,
                        cost, fq, fc, fcost));
                qS += dq;
                qF += fq;
                cashS += dc;
                cashF += fc;
            }
            for (LocalDate[] range : new LocalDate[][]{{null, null}, {D0.plusDays(7), D0.plusDays(30)}, {D0.plusDays(15), null}}) {
                EpisodeTimeline.Report r = run(days, 0.5, range[0], range[1]);
                // écart direct stratégie - référence, ouverture valorisée au cours du 1er jour de la plage
                int first = range[0] == null ? 0 : 7 == range[0].getDayOfMonth() - 1 ? 7 : 15;
                int last = range[1] == null ? n - 1 : 30;
                double openS = 0.5;
                double openF = 0.5;
                for (int i = 0; i < first; i++) {
                    openS += days.get(i).qtyDelta();
                    openF += days.get(i).fixedQtyDelta();
                }
                double p0 = days.get(first).price();
                double vs = 0;
                double vf = 0;
                double qs = openS;
                double qf = openF;
                for (int i = first; i <= last; i++) {
                    vs += days.get(i).cashDelta();
                    vf += days.get(i).fixedCashDelta();
                    qs += days.get(i).qtyDelta();
                    qf += days.get(i).fixedQtyDelta();
                }
                double pEnd = days.get(last).price();
                double expectedD = (vs + qs * pEnd - openS * p0) - (vf + qf * pEnd - openF * p0);
                EpisodeDtos.Gain g = r.gain().total();
                assertEquals(expectedD, g.total(), 1e-7);
                assertEquals(g.exposure() + g.timing() + g.fees(), g.total(), 1e-9);
                // par épisode
                for (EpisodeDto e : r.episodes()) {
                    assertEquals(e.gain().exposure() + e.gain().timing() + e.gain().fees(), e.gain().total(), 1e-9);
                }
                double sumEp = r.episodes().stream().mapToDouble(e -> e.gain().total()).sum();
                assertEquals(g.total(), sumEp, 1e-7);
                // invariant PnL = variation de valeur du wallet
                assertEquals(vs + qs * pEnd - openS * p0, r.pnlTotal(), 1e-7);
                double fees = r.episodes().stream().mapToDouble(e -> e.gain().fees()).sum();
                assertEquals(g.fees(), fees, 1e-9);
            }
        }
    }

    @Test
    @DisplayName("Découpe en deux sous-plages : les frais s'additionnent")
    void feesAddOverSplit() {
        List<TradeDay> days = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TradeDay t = day(i, 2, "BULL", 100 + i, i % 2 == 0 ? 0.1 : 0, i % 2 == 0 ? -10 : 0, i % 2 == 0 ? 0.05 : 0);
            days.add(withRef(t, i % 3 == 0 ? 0.1 : 0, i % 3 == 0 ? -10 : 0, i % 3 == 0 ? 0.02 : 0));
        }
        double all = run(days, 0, null, null).gain().total().fees();
        double a = run(days, 0, null, D0.plusDays(4)).gain().total().fees();
        double b = run(days, 0, D0.plusDays(5), null).gain().total().fees();
        assertEquals(all, a + b, 1e-12);
        assertTrue(all < 0.0 || all >= 0.0);
    }

    @Test
    @DisplayName("Plage vide ou série vide : aucun épisode")
    void emptyRange() {
        EpisodeTimeline.Report r = run(List.of(day(0, 2, "BULL", 100, 0, 0, 0)), 1.0, D0.plusDays(5), null);
        assertTrue(r.episodes().isEmpty());
        assertEquals(0.0, r.pnlTotal(), 0.0);
        assertNull(r.aggregates().stream().findAny().orElse(null));
    }
}
