package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.Gain;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.expert.simulation.DrawdownMeter;
import fr.ses10doigts.tradeIO5.service.expert.simulation.EpisodeSeries;
import fr.ses10doigts.tradeIO5.service.expert.simulation.EpisodeTimeline;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.expert.simulation.SimulationEligibility;
import fr.ses10doigts.tradeIO5.service.optimizer.ScoreReport.PureTiming;
import fr.ses10doigts.tradeIO5.service.optimizer.ScoreReport.WindowReport;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;
import fr.ses10doigts.tradeIO5.service.optimizer.WeightedStats.Point;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Évaluateur pur (aucun I/O, aucun Spring) : rejoue le simulateur sur des fenêtres ancrées à la même date de fin avec les
 * entrées explicites de {@link ScorerInputs}, et note un jeu de paramètres.
 * <ul>
 *   <li><b>Gain d'une fenêtre</b> = {@code (valeur finale − valeur finale du DCA fixe de RÉFÉRENCE) / capital}, en %. Le DCA fixe est
 *   toujours calculé au montant de base de référence du lien ({@link ScorerInputs.AssetInput#referenceBase()}), identique pour tous les
 *   candidats : le montant de base du candidat ({@link ResolvedCandidate#baseMultiplier()}, multiple de la référence) ne change plus la
 *   référence, donc ne peut plus gagner juste en investissant moins.</li>
 *   <li><b>Référence au même budget</b> : le DCA fixe partage le cash commun de la stratégie (tout-ou-rien, jamais au-delà du cash, aucune
 *   recharge ; {@link WindowReport#fixedMinCash()} ≥ 0 le prouve). Sur l'historique complet, il peut finir très au-dessus du capital : c'est
 *   le marché, pas une dépense hors budget ; cette fenêtre est seulement rapportée.</li>
 *   <li><b>Score</b> = médiane pondérée de ce gain sur les fenêtres bornées éligibles (12 m / 24 m / test ; pas l'historique complet), moins
 *   {@code λ ×} écart absolu moyen pondéré autour de cette médiane ; vetoé ⇒ {@link OptimizerDefaults#VETO_SCORE} ({@link Vetoes}).</li>
 *   <li><b>Fenêtre non éligible</b> (historique avant la fenêtre &lt; warm-up requis des presets) : exclue et signalée, jamais
 *   remplacée. Fenêtre « complète » : du premier jour éligible de tous les liens à la date de fin.</li>
 *   <li><b>Timing pur</b> : terme « timing » de la répartition {@link EpisodeTimeline} (gain à écart d'exposition moyen retiré),
 *   rapporté à part, jamais dans le score.</li>
 * </ul>
 */
public final class ReplayScorer {

    static final String TIMING_DEFINITION = "Terme timing de la répartition exposition / timing / frais d'EpisodeTimeline (gain une fois "
            + "retiré l'effet d'exposition : écart de quantité moyen × mouvement du prix), en % du capital ; médiane "
            + "pondérée des fenêtres. Calculé en second, hors score.";
    private static final double EPSILON = 1e-9;

    private ReplayScorer() {
    }

    /** Une fenêtre résolue : bornes en jours et index de bougie par lien. */
    private record Plan(WindowSpec spec, LocalDate from, Map<String, Integer> startIdx, Map<String, Integer> endIdx) {
    }

    /**
     * @param shortWindowOnly seule la fenêtre la plus courte est rejouée (budget minimal du halving)
     */
    public static ScoreReport score(ScorerInputs in, ResolvedCandidate candidate, CostMode mode, boolean shortWindowOnly) {
        List<String> excluded = new ArrayList<>();
        List<Plan> plans = new ArrayList<>();
        for (WindowSpec spec : windowsToRun(in, shortWindowOnly)) {
            String reason = plan(in, spec, plans);
            if (reason != null) {
                excluded.add(spec.name() + " : " + reason);
            }
        }
        List<WindowReport> windows = new ArrayList<>();
        for (Plan p : plans) {
            WindowReport w = runWindow(in, candidate, mode, p);
            if (w == null) {
                excluded.add(p.spec().name() + " : aucun jour rejouable (SMA / ATR pas encore définis)");
            } else {
                windows.add(w);
            }
        }
        List<CandleFingerprint> fingerprint = in.assets().stream()
                .map(a -> CandleFingerprint.of(a.slot().assetSymbol(), a.template().candles(), in.endDate())).toList();
        if (windows.isEmpty()) {
            return new ScoreReport(OptimizerDefaults.VETO_SCORE, OptimizerDefaults.VETO_SCORE, true,
                    List.of(OptimizerDefaults.VETO_NO_WINDOW), null, 0.0, 0.0, List.of(), excluded, null, mode.name(), fingerprint);
        }
        List<WindowReport> scored = windows.stream().filter(WindowReport::scored).toList();
        if (scored.isEmpty()) {
            return new ScoreReport(OptimizerDefaults.VETO_SCORE, OptimizerDefaults.VETO_SCORE, true,
                    List.of(OptimizerDefaults.VETO_NO_WINDOW), null, 0.0, 0.0, windows, excluded, null, mode.name(), fingerprint);
        }
        List<Point> gains = scored.stream().map(w -> new Point(w.gainPct(), w.weight())).toList();
        double median = WeightedStats.median(gains);
        double dispersion = WeightedStats.meanAbsoluteDeviation(gains, median);
        double penalty = in.dispersionPenalty() * dispersion;
        double raw = median - penalty;
        List<String> vetoes = vetoes(in).evaluate(windows);
        boolean vetoed = !vetoes.isEmpty();
        List<Point> timings = scored.stream().filter(w -> w.timingGainPct() != null)
                .map(w -> new Point(w.timingGainPct(), w.weight())).toList();
        PureTiming pureTiming = timings.isEmpty() ? null : new PureTiming(WeightedStats.median(timings), TIMING_DEFINITION);
        return new ScoreReport(vetoed ? OptimizerDefaults.VETO_SCORE : raw, raw, vetoed, vetoes, median, dispersion, penalty, windows,
                excluded, pureTiming, mode.name(), fingerprint);
    }

    /** Vetos du profil : plafond de drawdown lu dans les bornes admin ({@code null} pour CUSTOM) et mise finale maximale. */
    static Vetoes vetoes(ScorerInputs in) {
        BigDecimal cap = in.limits().drawdownCaps().get(in.profile());
        return new Vetoes(cap == null ? null : cap.doubleValue(), in.maxFinalStake());
    }

    /**
     * Motif d'inéligibilité de chaque lien pour l'ensemble des fenêtres demandées (clé = actif ; absent = éligible partout). Un lien
     * est jugé seul : il ne fait pas perdre une fenêtre aux autres.
     */
    public static Map<String, String> ineligibleLinks(ScorerInputs in, List<WindowSpec> windows) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (AssetInput a : in.assets()) {
            ScorerInputs single = in.withAssets(List.of(a));
            for (WindowSpec spec : windows) {
                String reason = plan(single, spec, new ArrayList<>());
                if (reason != null) {
                    out.put(a.slot().assetSymbol(), spec.name() + " : " + reason);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Liens à l'historique insuffisant ({@code HISTORIQUE_INSUFFISANT}) sur au moins une des fenêtres demandées : actif, fenêtre, bougies
     * disponibles avant le début de la fenêtre / bougies requises. Un lien est jugé seul ; premier motif par actif.
     */
    public static List<ScoreReport.HistoryStatus> historyStatuses(ScorerInputs in, List<WindowSpec> windows) {
        List<ScoreReport.HistoryStatus> out = new ArrayList<>();
        for (AssetInput a : in.assets()) {
            for (WindowSpec spec : windows) {
                ScoreReport.HistoryStatus s = history(in, a, spec);
                if (s != null) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    private static ScoreReport.HistoryStatus history(ScorerInputs in, AssetInput a, WindowSpec spec) {
        RainbowAtrDataset ds = a.template().ds();
        int required = requiredPoints(a);
        String asset = a.slot().assetSymbol();
        LocalDate from = spec.from() != null ? spec.from() : spec.months() == null ? null : in.endDate().minusMonths(spec.months());
        if (from == null) {
            return required >= ds.size() ? new ScoreReport.HistoryStatus(asset, OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY, spec.name(),
                    ds.size(), required) : null;
        }
        int startIdx = ReplayDays.firstIndexOnOrAfter(ds, from);
        if (ReplayDays.lastIndexOnOrBefore(ds, in.endDate()) < 0 || startIdx >= ds.size() || startIdx >= required) {
            return null;
        }
        return new ScoreReport.HistoryStatus(asset, OptimizerDefaults.STATUS_INSUFFICIENT_HISTORY, spec.name(), startIdx, required);
    }

    private static List<WindowSpec> windowsToRun(ScorerInputs in, boolean shortWindowOnly) {
        if (!shortWindowOnly) {
            return in.windows();
        }
        return in.windows().stream().filter(w -> w.months() != null).min(Comparator.comparingInt(WindowSpec::months)).stream().toList();
    }

    /** Résout la fenêtre ; retourne le motif d'exclusion, ou {@code null} (plan ajouté). */
    private static String plan(ScorerInputs in, WindowSpec spec, List<Plan> plans) {
        Map<String, Integer> start = new HashMap<>();
        Map<String, Integer> end = new HashMap<>();
        LocalDate from = spec.from() != null ? spec.from() : spec.months() == null ? null : in.endDate().minusMonths(spec.months());
        if (from == null) {
            for (AssetInput a : in.assets()) {
                RainbowAtrDataset ds = a.template().ds();
                int required = requiredPoints(a);
                if (required >= ds.size()) {
                    return a.slot().assetSymbol() + " : " + ds.size() + " bougies pour " + required + " requises";
                }
                LocalDate day = ReplayDays.dayOf(ds.time(required));
                from = from == null || day.isAfter(from) ? day : from;
            }
        }
        for (AssetInput a : in.assets()) {
            RainbowAtrDataset ds = a.template().ds();
            int required = requiredPoints(a);
            int endIdx = ReplayDays.lastIndexOnOrBefore(ds, in.endDate());
            int startIdx = ReplayDays.firstIndexOnOrAfter(ds, from);
            if (endIdx < 0 || startIdx >= ds.size() || startIdx > endIdx) {
                return a.slot().assetSymbol() + " : aucune bougie dans [" + from + ", " + in.endDate() + "]";
            }
            if (startIdx < required) {
                return a.slot().assetSymbol() + " : historique insuffisant (" + startIdx + " bougies avant " + from + ", "
                        + required + " requises)";
            }
            start.put(a.slot().assetSymbol(), startIdx);
            end.put(a.slot().assetSymbol(), endIdx);
        }
        plans.add(new Plan(spec, from, start, end));
        return null;
    }

    /** Bougies D1 nécessaires avant le début d'une fenêtre : max des warm-ups Trend / Rainbow (tous jeux) / fenêtre d'analyse. */
    static int requiredPoints(AssetInput a) {
        LinkSignalReplay.Config t = a.template();
        int required = 0;
        for (RainbowAtrParamSet set : t.sets()) {
            required = Math.max(required, SimulationEligibility.requiredPoints(t.trend(), set.tuning(), t.windowMonths()));
        }
        return required;
    }

    private static WindowReport runWindow(ScorerInputs in, ResolvedCandidate candidate, CostMode mode, Plan p) {
        List<MultiLinkReplayEngine.Link> links = new ArrayList<>();
        for (AssetInput a : in.assets()) {
            LinkSignalReplay.Config t = a.template();
            String asset = a.slot().assetSymbol();
            Double reference = referenceBase(a, candidate);
            Double base = reference == null ? t.linkBaseAmount() : (candidate.baseMultiplier() == null ? 1.0 : candidate.baseMultiplier()) * reference;
            links.add(new MultiLinkReplayEngine.Link(a.slot(), new LinkSignalReplay.Config(t.ds(), t.candles(), t.trend(), t.range(),
                    t.sets(), p.startIdx().get(asset), p.startIdx().get(asset), p.endIdx().get(asset), base, Map.of(), Map.of(),
                    Set.of(), t.windowMonths(), null, reference), 0.0));
        }
        MultiLinkReplayEngine.Result r = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(links, in.cash0(),
                in.baseSettings(), in.limits(), Map.of(), Map.of(), Map.of(), Map.of(), costs(in, mode, links), candidate.policy(),
                candidate.athCap()));
        return report(in, p, r, links);
    }

    /**
     * Montant de base de référence du lien (celui du DCA fixe) : explicite, sinon la base du lien, sinon celle du premier jeu quand le
     * candidat porte un multiple ; {@code null} = aucune référence propre (rejeu historique : le DCA fixe suit la base du lien).
     */
    private static Double referenceBase(AssetInput a, ResolvedCandidate candidate) {
        if (a.referenceBase() != null || candidate.baseMultiplier() == null) {
            return a.referenceBase();
        }
        LinkSignalReplay.Config t = a.template();
        return t.linkBaseAmount() != null ? t.linkBaseAmount() : t.sets()[0].globals().baseAmount();
    }

    private static MultiLinkReplayEngine.Costs costs(ScorerInputs in, CostMode mode, List<MultiLinkReplayEngine.Link> links) {
        Set<String> assets = in.assets().stream().map(a -> a.slot().assetSymbol()).collect(Collectors.toSet());
        if (mode == CostMode.SIMPLIFIED) {
            return OptimizerCosts.simplified(assets);
        }
        if (in.completeCosts() != null) {
            return in.completeCosts();
        }
        Map<Long, Double> minBuy = new HashMap<>();
        links.forEach(l -> minBuy.put(l.slot().bindingId(), OptimizerDefaults.COMPLETE_MIN_BUY_USD));
        return OptimizerCosts.complete(assets, minBuy);
    }

    /** @return {@code null} si la fenêtre n'a aucun jour rejouable */
    private static WindowReport report(ScorerInputs in, Plan p, MultiLinkReplayEngine.Result r,
                                       List<MultiLinkReplayEngine.Link> links) {
        if (r.walletDays().isEmpty()) {
            return null;
        }
        List<LocalDate> days = r.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::day).toList();
        List<Double> totals = r.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::totalValue).toList();
        double maxDd = DrawdownMeter.measure(days, totals).maxPct();
        double avgExposure = r.walletDays().stream().filter(w -> w.totalValue() > EPSILON)
                .mapToDouble(w -> 100.0 * w.assetsValue() / w.totalValue()).average().orElse(0.0);
        Gain split = EpisodeTimeline.compute(EpisodeSeries.build(r, links), days.getFirst(), days.getLast()).gain().total();
        long blocked = r.linkDays().stream().filter(x -> x.wantedAction() != RainbowLiveAction.NONE
                && x.action() != RainbowLiveAction.BUY && x.action() != RainbowLiveAction.SELL).count();
        long belowMin = r.costDays().stream().filter(x -> "BELOW_MIN".equals(x.outcome())).count();
        long buys = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.BUY).count();
        long sells = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.SELL).count();
        LocalDate firstSell = r.linkDays().stream().filter(x -> x.action() == RainbowLiveAction.SELL)
                .map(MultiLinkReplayEngine.LinkDay::day).min(Comparator.naturalOrder()).orElse(null);
        boolean neverRebuys = firstSell != null && r.linkDays().stream()
                .noneMatch(x -> x.action() == RainbowLiveAction.BUY && x.day().isAfter(firstSell));
        double minCash = r.walletDays().stream().mapToDouble(MultiLinkReplayEngine.WalletDay::cash).min().orElse(in.cash0());
        double fixedMinCash = r.walletDays().stream().mapToDouble(MultiLinkReplayEngine.WalletDay::fixedCash).min().orElse(in.cash0());
        double fin = r.finalValue();
        double fix = r.fixedFinalValue();
        return new WindowReport(p.spec().name(), p.spec().months(), p.spec().weight(), days.getFirst(), days.getLast(), fin, fix,
                fin - fix, 100.0 * (fin - fix) / in.cash0(), maxDd, avgExposure, split, blocked, belowMin, buys, sells,
                r.stakeStats().maxStakeMultiple(), r.stakeStats().maxFinalStakeMultiple(), r.stakeStats().cappedBuys(), sells == 0,
                neverRebuys, minCash, fixedMinCash, in.cash0(), 100.0 * split.timing() / in.cash0());
    }
}
