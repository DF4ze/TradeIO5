package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.expert.simulation.DrawdownMeter;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.expert.simulation.SimulationRegimes;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Évaluateur léger de la carte de sensibilité (un lien, pur, aucun I/O, aucun veto, aucune fenêtre multiple, aucune pénalité de
 * dispersion : on n'utilise donc pas {@link ReplayScorer}). Deux lectures du MÊME jeu de réglages sur la même plage :
 * <ul>
 *   <li>{@link #cycle} : rejeu wallet 1 lien ({@link LinkSignalReplay.Config#withSets} + {@link MultiLinkReplayEngine}, cash fixe,
 *   coûts simplifiés) : Trend → Bull / Bear, les DEUX jeux vivent ; mesure = valeur du portefeuille à la fin de la plage (PnL réel =
 *   valeur finale − capital), donc un gain de bull vendu ou repris par le bear suivant y est compté ;</li>
 *   <li>{@link #frozen} : lecture « gel » de l'interface Expert (type {@code RAINBOW}) : {@link RainbowAtrReplay#run} sur le seul jeu
 *   testé, barres hors régime inactives ({@code RainbowAtrReplay.inactive}), mêmes colonnes que l'écran.</li>
 * </ul>
 * Compte chaque rejeu ({@link #replays()}).
 */
public final class SensitivityEvaluator {

    /** Lecture « cycle » : valeurs en USDC (capital fixe {@code cash0}). */
    public record Reading(LocalDate from, LocalDate to, double finalValue, double fixedFinalValue, double pnl, double pnlPct,
                          double fixedPnl, double realized, double bullEndValue, double bullEndPosition, LocalDate bullEndDay,
                          double maxDrawdownPct, double avgExposurePct, long buys, long sells) {
    }

    /**
     * Lecture « gel » : un seul régime compte ; {@code totalGain} = ventes encaissées + position − investi (PnL total de l'écran),
     * {@code dcaSameBudget} = gain du DCA fixe mis à budget égal (gain × investi / investi du DCA fixe), comme le front.
     */
    public record Frozen(double totalGain, double realizedGain, double potentialGain, double invested, double dcaSameBudget,
                         long buys, long sells) {
    }

    private final ScorerInputs.AssetInput link;
    private final ScorerInputs in;
    private final int months;
    private final int[] setOfBar;
    private final TrendRegime[] resolved;
    private final AtomicLong replays = new AtomicLong();

    /** @param months durée de la vue de test (24), glissante à {@code in.endDate()} */
    public SensitivityEvaluator(ScorerInputs in, int months) {
        if (in.assets().size() != 1) {
            throw new IllegalArgumentException("Un seul lien attendu");
        }
        this.in = in;
        this.link = in.assets().getFirst();
        this.months = months;
        LinkSignalReplay.Config t = link.template();
        RainbowAtrDataset ds = t.ds();
        TrendMixCalculator.Params tp = t.trend();
        TrendRegime[] raw = TrendMixCalculator.compute(t.candles(), ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime();
        this.setOfBar = RainbowSetSelector.select(raw, t.range());
        this.resolved = SimulationRegimes.resolve(raw, t.range());
    }

    public long replays() {
        return replays.get();
    }

    public LinkSignalReplay.Config template() {
        return link.template();
    }

    public String asset() {
        return link.slot().assetSymbol();
    }

    public ScorerInputs inputs() {
        return in;
    }

    /** Première bougie rejouable de l'historique complet (warm-up Trend / Rainbow / fenêtre d'analyse). */
    private int fullStart(RainbowAtrParamSet[] sets) {
        return ReplayScorer.requiredPoints(link.withTemplate(link.template().withSets(sets)));
    }

    private int startIndex(RainbowAtrParamSet[] sets, boolean fullHistory) {
        RainbowAtrDataset ds = link.template().ds();
        if (fullHistory) {
            return fullStart(sets);
        }
        return ReplayDays.firstIndexOnOrAfter(ds, in.endDate().minusMonths(months));
    }

    /** {@code null} si la plage n'est pas rejouable (historique insuffisant avant le début, aucune bougie). */
    public Reading cycle(RainbowAtrParamSet[] sets, boolean fullHistory) {
        LinkSignalReplay.Config t = link.template();
        RainbowAtrDataset ds = t.ds();
        int start = startIndex(sets, fullHistory);
        int end = ReplayDays.lastIndexOnOrBefore(ds, in.endDate());
        if (end < 0 || start >= ds.size() || start > end || start < fullStart(sets)) {
            return null;
        }
        replays.incrementAndGet();
        String asset = asset();
        MultiLinkReplayEngine.Link engineLink = new MultiLinkReplayEngine.Link(link.slot(), new LinkSignalReplay.Config(ds, t.candles(),
                t.trend(), t.range(), sets, start, start, end, t.linkBaseAmount(), Map.of(), Map.of(), Set.of(), t.windowMonths(), null,
                null), 0.0);
        MultiLinkReplayEngine.Result r = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(engineLink), in.cash0(),
                in.baseSettings(), in.limits(), Map.of(), Map.of(), Map.of(), Map.of(), OptimizerCosts.simplified(Set.of(asset)), null,
                null));
        if (r.walletDays().isEmpty()) {
            return null;
        }
        List<LocalDate> days = r.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::day).toList();
        List<Double> totals = r.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::totalValue).toList();
        double avgExposure = r.walletDays().stream().filter(w -> w.totalValue() > OptimizerDefaults.EPSILON)
                .mapToDouble(w -> 100.0 * w.assetsValue() / w.totalValue()).average().orElse(0.0);
        double realized = r.trades().stream().filter(x -> x.qtyDelta() < 0).mapToDouble(MultiLinkReplayEngine.TradeDay::cashDelta).sum();
        long buys = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.BUY).count();
        long sells = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.SELL).count();
        int bullEnd = bullEndIndex(start, end);
        LocalDate bullDay = bullEnd < 0 ? null : ReplayDays.dayOf(ds.time(bullEnd));
        MultiLinkReplayEngine.WalletDay atBull = bullDay == null ? null
                : r.walletDays().stream().filter(w -> !w.day().isAfter(bullDay)).reduce((a, b) -> b).orElse(null);
        double fin = r.finalValue();
        return new Reading(days.getFirst(), days.getLast(), fin, r.fixedFinalValue(), fin - in.cash0(), 100.0 * (fin - in.cash0()) / in.cash0(),
                r.fixedFinalValue() - in.cash0(), realized, atBull == null ? Double.NaN : atBull.totalValue(),
                atBull == null ? Double.NaN : atBull.assetsValue(), bullDay, DrawdownMeter.measure(days, totals).maxPct(), avgExposure, buys,
                sells);
    }

    /**
     * Dernier jour de la dernière phase Bull terminée par un Bear dans {@code [start, end]} (à défaut, de la dernière phase Bull) ;
     * -1 s'il n'y a aucune phase Bull.
     */
    private int bullEndIndex(int start, int end) {
        int lastCompleted = -1;
        int lastAny = -1;
        for (int i = start; i <= end; i++) {
            if (setOfBar[i] != RainbowAtrPresets.BULL) {
                continue;
            }
            lastAny = i;
            if (i < end && setOfBar[i + 1] == RainbowAtrPresets.BEAR) {
                lastCompleted = i;
            }
        }
        return lastCompleted >= 0 ? lastCompleted : lastAny;
    }

    /** Lecture « gel » du jeu {@code setIndex} ({@link RainbowAtrPresets#BULL} / {@link RainbowAtrPresets#BEAR}) ; {@code null} si rien à rejouer. */
    public Frozen frozen(RainbowAtrParamSet[] sets, int setIndex) {
        LinkSignalReplay.Config t = link.template();
        RainbowAtrDataset ds = t.ds();
        int start = startIndex(sets, false);
        int end = ReplayDays.lastIndexOnOrBefore(ds, in.endDate());
        if (end < 0 || start >= ds.size() || start > end || start < fullStart(sets)) {
            return null;
        }
        replays.incrementAndGet();
        TrendRegime target = setIndex == RainbowAtrPresets.BULL ? TrendRegime.UP : TrendRegime.DOWN;
        int[] bars = new int[ds.size()];
        for (int i = 0; i < bars.length; i++) {
            bars[i] = resolved[i] == target ? 0 : RainbowAtrReplay.inactive(0);
        }
        RainbowAtrParamSet[] one = {sets[setIndex]};
        List<DayRow> rows = RainbowAtrReplay.run(ds, one, bars, start, end);
        if (rows.isEmpty()) {
            return null;
        }
        DayRow last = rows.getLast();
        RainbowAtrResult res = RainbowAtrResult.fromRows(rows, one[0].globals().baseAmount());
        double dca = res.fixedInvested() > 0 ? last.fixedGain() * last.invested() / res.fixedInvested() : 0.0;
        return new Frozen(last.totalGain(), last.realizedGain(), last.potentialGain(), last.invested(), dca,
                res.zoneBuys() + res.triggeredBuys(), res.sells() + res.moonStops());
    }
}
