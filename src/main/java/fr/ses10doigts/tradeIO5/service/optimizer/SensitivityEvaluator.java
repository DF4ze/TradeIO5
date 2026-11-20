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
    private final LocalDate from;
    private final int[] setOfBar;
    private final TrendRegime[] resolved;
    private final AtomicLong replays = new AtomicLong();

    /** @param months durée de la vue de test (24), glissante à {@code in.endDate()} */
    public SensitivityEvaluator(ScorerInputs in, int months) {
        this(in, months, null);
    }

    /**
     * @param from premier jour de la vue (inclus) ; {@code null} = {@code endDate − months} (vue glissante de la carte de sensibilité).
     *             Les blocs de l'étape B l'imposent pour ne jamais se chevaucher.
     */
    public SensitivityEvaluator(ScorerInputs in, int months, LocalDate from) {
        if (in.assets().size() != 1) {
            throw new IllegalArgumentException("Un seul lien attendu");
        }
        this.in = in;
        this.link = in.assets().getFirst();
        this.months = months;
        this.from = from;
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

    /** Premier jour rejouable (fin du préchauffage) : aucune plage ne peut commencer avant ; {@code null} si l'historique est insuffisant. */
    public LocalDate firstReplayableDay(RainbowAtrParamSet[] sets) {
        RainbowAtrDataset ds = link.template().ds();
        int i = fullStart(sets);
        return i >= ds.size() ? null : ReplayDays.dayOf(ds.time(i));
    }

    private int startIndex(RainbowAtrParamSet[] sets, boolean fullHistory) {
        RainbowAtrDataset ds = link.template().ds();
        if (fullHistory) {
            return fullStart(sets);
        }
        return ReplayDays.firstIndexOnOrAfter(ds, from != null ? from : in.endDate().minusMonths(months));
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
     * Mesure d'une série quotidienne du wallet (exposition = valeur de la position / valeur totale, moyenne sur les jours ; drawdown de la
     * valeur du portefeuille, ≤ 0).
     */
    public record Line(double finalValue, double pnlPct, double avgExposurePct, double maxDrawdownPct, long buys, long sells) {
    }

    /** Un rejeu, deux lignes : le preset (jeux actifs) et le DCA fixe de référence du moteur (même cash commun, tout-ou-rien). */
    public record Parity(LocalDate from, LocalDate to, Line preset, double realized, double latent, Line dca) {
    }

    /**
     * Rejeu de l'étape B' : même moteur et même mesure que {@link #cycle} (les chiffres du preset et du DCA habituel sont identiques),
     * avec en plus l'exposition et le drawdown du DCA fixe. {@code fixedBase} = montant de base du DCA fixe ({@code null} = base du
     * lien, c'est-à-dire le DCA habituel) ; il ne change rien au preset. {@code shiftDays} décale le début de la plage (bande de bruit).
     * {@code null} si la plage n'est pas rejouable.
     */
    public Parity parity(RainbowAtrParamSet[] sets, boolean fullHistory, int shiftDays, Double fixedBase) {
        return parity(sets, fullHistory, shiftDays, fixedBase, null);
    }

    /** Montant de base de référence du lien (celui du lien, à défaut celui du premier jeu) : base de l'étape C1 (multiplicateur ×1). */
    public double referenceBase(RainbowAtrParamSet[] sets) {
        Double b = link.template().linkBaseAmount();
        return b != null && b > 0 ? b : sets[0].globals().baseAmount();
    }

    /**
     * Comme {@link #parity(RainbowAtrParamSet[], boolean, int, Double)} avec un montant de base du lien imposé ({@code linkBase}, étape C1 :
     * multiplicateur × {@link #referenceBase}) ; {@code null} = base du lien inchangée (chiffres identiques à l'étape B'). Sans {@code fixedBase},
     * le DCA fixe suit la base du lien. Preset, Trend et modulation inchangés : seule la taille des achats change.
     */
    public Parity parity(RainbowAtrParamSet[] sets, boolean fullHistory, int shiftDays, Double fixedBase, Double linkBase) {
        return parity(sets, fullHistory, shiftDays, fixedBase, linkBase, null);
    }

    /**
     * Comme {@link #parity(RainbowAtrParamSet[], boolean, int, Double, Double)} avec une politique de modulation dans le temps (étape C2) ;
     * {@code null} = aucune politique : chiffres strictement identiques à l'appel sans politique.
     */
    public Parity parity(RainbowAtrParamSet[] sets, boolean fullHistory, int shiftDays, Double fixedBase, Double linkBase,
                         fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicy policy) {
        LinkSignalReplay.Config t = link.template();
        RainbowAtrDataset ds = t.ds();
        int base = startIndex(sets, fullHistory);
        int end = ReplayDays.lastIndexOnOrBefore(ds, in.endDate());
        if (end < 0 || base >= ds.size() || base > end || base < fullStart(sets)) {
            return null;
        }
        int start = shiftDays == 0 ? base : ReplayDays.firstIndexOnOrAfter(ds, ReplayDays.dayOf(ds.time(base)).plusDays(shiftDays));
        if (start < 0 || start >= ds.size() || start > end) {
            return null;
        }
        replays.incrementAndGet();
        String asset = asset();
        MultiLinkReplayEngine.Link engineLink = new MultiLinkReplayEngine.Link(link.slot(), new LinkSignalReplay.Config(ds, t.candles(),
                t.trend(), t.range(), sets, start, start, end, linkBase != null ? linkBase : t.linkBaseAmount(), Map.of(), Map.of(), Set.of(),
                t.windowMonths(), null, fixedBase), 0.0);
        MultiLinkReplayEngine.Result r = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(List.of(engineLink), in.cash0(),
                in.baseSettings(), in.limits(), Map.of(), Map.of(), Map.of(), Map.of(), OptimizerCosts.simplified(Set.of(asset)), policy,
                null));
        List<MultiLinkReplayEngine.WalletDay> w = r.walletDays();
        if (w.isEmpty()) {
            return null;
        }
        List<LocalDate> days = w.stream().map(MultiLinkReplayEngine.WalletDay::day).toList();
        double cash0 = in.cash0();
        double fin = r.finalValue();
        double fixedFin = r.fixedFinalValue();
        double expoPreset = w.stream().filter(x -> x.totalValue() > OptimizerDefaults.EPSILON)
                .mapToDouble(x -> 100.0 * x.assetsValue() / x.totalValue()).average().orElse(0.0);
        double expoDca = w.stream().filter(x -> x.fixedValue() > OptimizerDefaults.EPSILON)
                .mapToDouble(x -> 100.0 * (x.fixedValue() - x.fixedCash()) / x.fixedValue()).average().orElse(0.0);
        double ddPreset = DrawdownMeter.measure(days, w.stream().map(MultiLinkReplayEngine.WalletDay::totalValue).toList()).maxPct();
        double ddDca = DrawdownMeter.measure(days, w.stream().map(MultiLinkReplayEngine.WalletDay::fixedValue).toList()).maxPct();
        long buys = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.BUY).count();
        long sells = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.SELL).count();
        long dcaBuys = r.trades().stream().filter(x -> x.fixedQtyDelta() > 0).count();
        double realized = r.trades().stream().filter(x -> x.qtyDelta() < 0).mapToDouble(MultiLinkReplayEngine.TradeDay::cashDelta).sum();
        return new Parity(days.getFirst(), days.getLast(),
                new Line(fin, 100.0 * (fin - cash0) / cash0, expoPreset, ddPreset, buys, sells), realized, w.getLast().assetsValue(),
                new Line(fixedFin, 100.0 * (fixedFin - cash0) / cash0, expoDca, ddDca, dcaBuys, 0));
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
