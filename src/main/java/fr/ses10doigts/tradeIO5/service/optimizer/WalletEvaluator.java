package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.expert.simulation.DrawdownMeter;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Évaluateur d'un wallet à PLUSIEURS liens, cash et exposition communs (étape C4) : même moteur ({@link MultiLinkReplayEngine}), mêmes coûts
 * simplifiés et même mesure ({@link Line}) que {@link SensitivityEvaluator#parity}, avec un montant de base par lien. Pur, aucun I/O, aucun
 * veto. Tous les liens démarrent le même jour (le plus tardif des premiers jours rejouables pour l'historique complet, {@code from} pour
 * une fenêtre) et finissent à {@code in.endDate()}.
 */
final class WalletEvaluator {

    private final ScorerInputs in;
    private final LocalDate from;
    private final AtomicLong replays = new AtomicLong();

    /** @param from premier jour d'une fenêtre ; {@code null} pour l'historique complet */
    WalletEvaluator(ScorerInputs in, LocalDate from) {
        this.in = in;
        this.from = from;
    }

    long replays() {
        return replays.get();
    }

    /** Premier jour rejouable du wallet : le plus tardif des premiers jours rejouables de ses liens ({@code null} si l'un est insuffisant). */
    static LocalDate firstReplayableDay(ScorerInputs in) {
        LocalDate first = null;
        for (AssetInput a : in.assets()) {
            RainbowAtrDataset ds = a.template().ds();
            int i = ReplayScorer.requiredPoints(a);
            if (i >= ds.size()) {
                return null;
            }
            LocalDate d = ReplayDays.dayOf(ds.time(i));
            first = first == null || d.isAfter(first) ? d : first;
        }
        return first;
    }

    /** Bases en USDC, dans l'ordre de {@code in.assets()} ; {@code null} si la plage n'est pas rejouable. */
    Line line(double[] bases) {
        List<MultiLinkReplayEngine.Link> links = new ArrayList<>();
        Set<String> assets = new HashSet<>();
        LocalDate startDay = from != null ? from : firstReplayableDay(in);
        if (startDay == null) {
            return null;
        }
        for (int k = 0; k < in.assets().size(); k++) {
            AssetInput a = in.assets().get(k);
            LinkSignalReplay.Config t = a.template();
            RainbowAtrDataset ds = t.ds();
            int start = ReplayDays.firstIndexOnOrAfter(ds, startDay);
            int end = ReplayDays.lastIndexOnOrBefore(ds, in.endDate());
            if (end < 0 || start >= ds.size() || start > end || start < ReplayScorer.requiredPoints(a)) {
                return null;
            }
            links.add(new MultiLinkReplayEngine.Link(a.slot(), new LinkSignalReplay.Config(ds, t.candles(), t.trend(), t.range(), t.sets(),
                    start, start, end, bases[k], Map.of(), Map.of(), Set.of(), t.windowMonths(), null, null), 0.0));
            assets.add(a.slot().assetSymbol());
        }
        replays.incrementAndGet();
        MultiLinkReplayEngine.Result r = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(links, in.cash0(), in.baseSettings(),
                in.limits(), Map.of(), Map.of(), Map.of(), Map.of(), OptimizerCosts.simplified(assets), null, null));
        List<MultiLinkReplayEngine.WalletDay> w = r.walletDays();
        if (w.isEmpty()) {
            return null;
        }
        List<LocalDate> days = w.stream().map(MultiLinkReplayEngine.WalletDay::day).toList();
        double fin = r.finalValue();
        double expo = w.stream().filter(x -> x.totalValue() > OptimizerDefaults.EPSILON)
                .mapToDouble(x -> 100.0 * x.assetsValue() / x.totalValue()).average().orElse(0.0);
        double dd = DrawdownMeter.measure(days, w.stream().map(MultiLinkReplayEngine.WalletDay::totalValue).toList()).maxPct();
        long buys = r.linkDays().stream().filter(x -> x.action() == fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction.BUY).count();
        long sells = r.linkDays().stream().filter(x -> x.action() == fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction.SELL).count();
        return new Line(fin, 100.0 * (fin - in.cash0()) / in.cash0(), expo, dd, buys, sells);
    }
}
