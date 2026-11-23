package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AssetCut;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.AtCap;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.CapResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchCutReport.Point;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.Ablation;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.AssetRobustness;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.CapAblation;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.CapNoise;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.DdAtMultiplier;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.NoiseResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.Rule1;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.SizeResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.SizeTrend;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.SizeVerdict;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.TrendCap;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.Verdicts;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.WalletCap;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.WalletLevel;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.WalletResult;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchRobustnessReport.WalletSize;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

/**
 * Étape E2 : la coupure au switch (mécanisme d'E1, inchangé) est-elle robuste ? Trois lectures : (A) taille de ré-entrée et ablation d'un bloc à la
 * fois, (B) bande de bruit (décalages de la date de départ), (C) portefeuille BTC + ETH à cash commun. Hors ligne : rien n'est appliqué ni persisté.
 * Déterministe (aucun tirage ; le pool ne change que la durée).
 */
public final class SwitchRobustness {

    private static final double EPS = 1e-9;
    private static final double PUBLISHED_TOLERANCE = 0.06;

    private SwitchRobustness() {
    }

    // ------------------------------------------------------------------ mise en place (comme E1)

    /** Blocs rejouables, jeux sans modulation ATH et base de référence : identiques à {@link SwitchCutStudy#run}. */
    record Setup(List<SensitivityEvaluator> evs, List<Block> specs, RainbowAtrParamSet[] sets, double reference) {
        long replays() {
            return evs.stream().mapToLong(SensitivityEvaluator::replays).sum();
        }
    }

    static Setup setup(ScorerInputs in, ParityConfig pc) {
        List<Block> specs = TuneBlocks.build(pc.lastEnd(), pc.blockMonths(), pc.blockCount());
        List<SensitivityEvaluator> all = new ArrayList<>();
        for (Block b : specs) {
            all.add(new SensitivityEvaluator(in.withEndDate(b.to()), pc.blockMonths(), b.from()));
        }
        RainbowAtrParamSet[] sets = SwitchCutStudy.withoutAth(all.getFirst().template().sets());
        double reference = all.getFirst().referenceBase(sets);
        List<SensitivityEvaluator> evs = new ArrayList<>();
        List<Block> used = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).parityCut(sets, false, 0, null, null, null, null) != null) {
                evs.add(all.get(i));
                used.add(specs.get(i));
            }
        }
        return new Setup(evs, used, sets, reference);
    }

    /** Un niveau, une ligne, tous les blocs, départ décalé de {@code shift} jours ; {@code null} si un bloc n'est pas rejouable. */
    static List<Point> points(Setup su, double multiplier, int shift, MultiLinkReplayEngine.SwitchCut cut) {
        Double lb = ExposureFrontier.linkBase(multiplier, su.reference());
        List<Point> out = new ArrayList<>();
        for (SensitivityEvaluator ev : su.evs()) {
            SensitivityEvaluator.CutParity p = ev.parityCut(su.sets(), false, shift, null, lb, null, cut);
            if (p == null) {
                return null;
            }
            out.add(SwitchCutStudy.point(p.parity().preset()));
        }
        return out;
    }

    static List<List<Point>> lines(Setup su, List<Double> multipliers, int shift, MultiLinkReplayEngine.SwitchCut cut) {
        List<List<Point>> out = new ArrayList<>();
        for (double m : su.evs().isEmpty() ? List.<Double>of() : multipliers) {
            out.add(points(su, m, shift, cut));
        }
        return out;
    }

    /** Niveaux où les deux lignes sont rejouables sur tous les blocs. */
    static List<Level> assemble(List<Double> multipliers, List<List<Point>> pure, List<List<Point>> cut) {
        List<Level> out = new ArrayList<>();
        for (int i = 0; i < pure.size(); i++) {
            if (pure.get(i) != null && cut.get(i) != null) {
                out.add(new Level(multipliers.get(i), pure.get(i), cut.get(i), 0, 0, 0.0));
            }
        }
        return out;
    }

    /** Verdicts par plafond de profil (règle d'E1 : {@link SwitchCutStudy#compare}). */
    static List<CapResult> caps(List<Level> levels, ParityConfig pc) {
        List<CapResult> out = new ArrayList<>();
        for (Map.Entry<StartingProfile, Double> e : FrontierConfig.profileCaps().entrySet()) {
            AtCap pure = SwitchCutStudy.atCap(levels, e.getValue(), Level::pure);
            AtCap cut = SwitchCutStudy.atCap(levels, e.getValue(), Level::cut);
            out.add(SwitchCutStudy.compare(e.getKey().name(), e.getValue(), pure, cut, pc));
        }
        return out;
    }

    // ------------------------------------------------------------------ règle 1 : ablation d'un bloc

    static List<Point> without(List<Point> ps, int block) {
        List<Point> out = new ArrayList<>(ps);
        out.remove(block);
        return out;
    }

    /** Les niveaux recalculés sans le bloc {@code block} (le plafond est ré-interpolé sur le pire drawdown des blocs restants). */
    static List<Level> drop(List<Level> levels, int block) {
        List<Level> out = new ArrayList<>();
        for (Level l : levels) {
            out.add(new Level(l.multiplier(), without(l.pure(), block), without(l.cut(), block), l.cutSells(), l.cutBuys(), l.cutCostUsdc()));
        }
        return out;
    }

    static boolean positive(CapResult r) {
        return r.medianDelta() != null && r.medianDelta() > EPS;
    }

    /**
     * Règle 1 (fixée avant lecture) : pour chaque plafond, retirer UN bloc à la fois (recalcul complet sans ce bloc) ; le plafond est « tous positifs » si le
     * Δ gain médian (coupure − pur à plafond égal) est &gt; 0 dans toutes les ablations (un plafond non encadré compte comme non positif). ROBUSTE si au
     * moins {@code minCaps} plafonds sur 4 sont « tous positifs » ; sinon FRAGILE, avec le bloc dont le retrait renverse le plus souvent un Δ positif.
     */
    static Rule1 rule1(AssetCut cut, SwitchRobustnessConfig cfg) {
        ParityConfig pc = cfg.cut().parity();
        int n = cut.blockLabels().size();
        if (n - 1 < pc.minBlocksPass()) {
            return new Rule1("NON_CONCLU", 0, List.of(), null);
        }
        List<CapAblation> caps = new ArrayList<>();
        int[] flips = new int[n];
        int allPositive = 0;
        for (Map.Entry<StartingProfile, Double> e : FrontierConfig.profileCaps().entrySet()) {
            CapResult full = SwitchCutStudy.compare(e.getKey().name(), e.getValue(), SwitchCutStudy.atCap(cut.levels(), e.getValue(), Level::pure),
                    SwitchCutStudy.atCap(cut.levels(), e.getValue(), Level::cut), pc);
            List<Ablation> abl = new ArrayList<>();
            boolean all = true;
            for (int b = 0; b < n; b++) {
                List<Level> sub = drop(cut.levels(), b);
                CapResult r = SwitchCutStudy.compare(e.getKey().name(), e.getValue(), SwitchCutStudy.atCap(sub, e.getValue(), Level::pure),
                        SwitchCutStudy.atCap(sub, e.getValue(), Level::cut), pc);
                boolean pos = positive(r);
                all &= pos;
                if (!pos && positive(full)) {
                    flips[b]++;
                }
                abl.add(new Ablation(cut.blockLabels().get(b), r.cut().status().equals("INTERPOLE") && r.pure().status().equals("INTERPOLE") ? "INTERPOLE" : "NON_CONCLU",
                        r.medianDelta(), pos));
            }
            allPositive += all ? 1 : 0;
            caps.add(new CapAblation(e.getKey().name(), e.getValue(), full.medianDelta(), abl, all));
        }
        if (allPositive >= cfg.minCaps()) {
            return new Rule1("ROBUSTE", allPositive, caps, null);
        }
        int best = -1;
        int max = 0;
        for (int b = 0; b < n; b++) {
            if (flips[b] > max) {
                max = flips[b];
                best = b;
            }
        }
        return new Rule1("FRAGILE", allPositive, caps, best < 0 ? null : cut.blockLabels().get(best));
    }

    // ------------------------------------------------------------------ règle 2 : taille

    /**
     * Règle 2 : sur les plafonds conclus pour toutes les tailles, le gain médian de la coupure croît avec la taille si la suite est strictement croissante à
     * au moins {@code minCaps} plafonds ; sous 3 plafonds évaluables, NON_EVALUABLE. Meilleure taille = la plus souvent la plus haute (ex aequo : aucune).
     */
    static SizeTrend trend(List<SizeResult> sizes, SwitchRobustnessConfig cfg) {
        List<Double> pcts = sizes.stream().map(SizeResult::reentryPct).toList();
        List<TrendCap> caps = new ArrayList<>();
        int evaluated = 0;
        int increasing = 0;
        int[] best = new int[pcts.size()];
        int nCaps = sizes.getFirst().cut().caps().size();
        for (int c = 0; c < nCaps; c++) {
            CapResult first = sizes.getFirst().cut().caps().get(c);
            List<Double> gains = new ArrayList<>();
            boolean ok = true;
            for (SizeResult s : sizes) {
                AtCap a = s.cut().caps().get(c).cut();
                Double g = "INTERPOLE".equals(a.status()) ? a.medianGain() : null;
                gains.add(g);
                ok &= g != null;
            }
            caps.add(new TrendCap(first.profile(), first.capPct(), "INTERPOLE".equals(first.pure().status()) ? first.pure().medianGain() : null, gains));
            if (!ok) {
                continue;
            }
            evaluated++;
            boolean inc = true;
            int arg = 0;
            boolean tie = false;
            for (int i = 1; i < gains.size(); i++) {
                inc &= gains.get(i) > gains.get(i - 1) + EPS;
            }
            for (int i = 1; i < gains.size(); i++) {
                if (gains.get(i) > gains.get(arg) + EPS) {
                    arg = i;
                    tie = false;
                } else if (Math.abs(gains.get(i) - gains.get(arg)) <= EPS) {
                    tie = true;
                }
            }
            increasing += inc ? 1 : 0;
            if (!tie) {
                best[arg]++;
            }
        }
        String growth = evaluated < cfg.minCaps() ? "NON_EVALUABLE" : increasing >= cfg.minCaps() ? "CROIT" : "NE_CROIT_PAS";
        int bi = -1;
        int bmax = 0;
        boolean unique = true;
        for (int i = 0; i < best.length; i++) {
            if (best[i] > bmax) {
                bmax = best[i];
                bi = i;
                unique = true;
            } else if (best[i] == bmax && bmax > 0) {
                unique = false;
            }
        }
        List<Integer> counts = new ArrayList<>();
        for (int v : best) {
            counts.add(v);
        }
        return new SizeTrend(pcts, caps, evaluated, increasing, growth, bi >= 0 && unique ? pcts.get(bi) : null, counts);
    }

    // ------------------------------------------------------------------ règle 3 : bande de bruit

    private record ShiftOut(int shift, List<List<CapResult>> bySize) {
    }

    static List<NoiseResult> noise(Setup su, SwitchRobustnessConfig cfg, ExecutorService pool) {
        ParityConfig pc = cfg.cut().parity();
        List<Double> mults = cfg.cut().multipliers();
        List<Callable<ShiftOut>> tasks = new ArrayList<>();
        for (int s : cfg.shifts()) {
            tasks.add(() -> {
                List<List<Point>> pure = lines(su, mults, s, null);
                List<List<CapResult>> bySize = new ArrayList<>();
                for (double size : cfg.noiseSizes()) {
                    List<List<Point>> cut = lines(su, mults, s, new MultiLinkReplayEngine.SwitchCut(cfg.cut().sellFraction(), size));
                    bySize.add(caps(assemble(mults, pure, cut), pc));
                }
                return new ShiftOut(s, bySize);
            });
        }
        List<ShiftOut> outs = ExposurePolicyStudy.ordered(tasks, pool);
        List<NoiseResult> res = new ArrayList<>();
        for (int z = 0; z < cfg.noiseSizes().size(); z++) {
            List<CapNoise> caps = new ArrayList<>();
            int pass = 0;
            int nCaps = outs.getFirst().bySize().get(z).size();
            for (int c = 0; c < nCaps; c++) {
                int concluded = 0;
                int beats = 0;
                List<Double> deltas = new ArrayList<>();
                CapResult ref = outs.getFirst().bySize().get(z).get(c);
                for (ShiftOut o : outs) {
                    CapResult r = o.bySize().get(z).get(c);
                    if (r.medianDelta() != null) {
                        concluded++;
                        deltas.add(r.medianDelta());
                    }
                    beats += positive(r) ? 1 : 0;
                }
                double share = (double) beats / outs.size();
                boolean ok = share >= cfg.stableShare() - EPS;
                pass += ok ? 1 : 0;
                caps.add(new CapNoise(ref.profile(), ref.capPct(), outs.size(), concluded, beats, share, deltas.isEmpty() ? null : SwitchCutReport.median(deltas),
                        ExposureParity.percentile(deltas, 0.1), ExposureParity.percentile(deltas, 0.9), ok));
            }
            res.add(new NoiseResult(cfg.noiseSizes().get(z), caps, pass, pass >= cfg.minCaps() ? "STABLE" : "INSTABLE"));
        }
        return res;
    }

    // ------------------------------------------------------------------ un actif

    /** Valeurs publiées d'E1 (gain médian des blocs à plafond égal, ordre PRUDENT, BALANCED, DYNAMIC, FULL ; NaN = non conclu). */
    private static final Map<String, double[]> PUBLISHED = Map.of(
            "BTC|pure", new double[]{11.1, 19.4, 33.5, 67.3}, "BTC|20", new double[]{13.8, 19.9, 32.7, 54.1}, "BTC|0", new double[]{6.5, 12.0, 23.9, 47.9},
            "ETH|pure", new double[]{6.8, 12.7, 24.6, Double.NaN}, "ETH|20", new double[]{18.6, 29.8, 50.8, Double.NaN}, "ETH|0", new double[]{11.2, 20.7, 29.7, Double.NaN});

    static List<String> drift(String asset, List<SizeResult> sizes) {
        List<String> out = new ArrayList<>();
        for (SizeResult s : sizes) {
            if (!s.reconstituted()) {
                continue;
            }
            double[] cut = PUBLISHED.get(asset + "|" + (int) s.reentryPct());
            double[] pure = PUBLISHED.get(asset + "|pure");
            if (cut == null) {
                continue;
            }
            for (int c = 0; c < cut.length; c++) {
                CapResult r = s.cut().caps().get(c);
                boolean cutOk = "INTERPOLE".equals(r.cut().status());
                if (Double.isNaN(cut[c])) {
                    if (cutOk) {
                        out.add(String.format(Locale.ROOT, "%s %.0f %% plafond %s : publié non conclu, recalculé %s", asset, s.reentryPct(), r.profile(), SwitchCutReport.f(r.cut().medianGain())));
                    }
                    continue;
                }
                if (!cutOk || Math.abs(r.cut().medianGain() - cut[c]) > PUBLISHED_TOLERANCE) {
                    out.add(String.format(Locale.ROOT, "%s %.0f %% plafond %s coupure : publié %.1f, recalculé %s", asset, s.reentryPct(), r.profile(), cut[c],
                            cutOk ? SwitchCutReport.f(r.cut().medianGain()) : r.cut().status()));
                }
                if ("INTERPOLE".equals(r.pure().status()) && Math.abs(r.pure().medianGain() - pure[c]) > PUBLISHED_TOLERANCE) {
                    out.add(String.format(Locale.ROOT, "%s plafond %s pur : publié %.1f, recalculé %s", asset, r.profile(), pure[c], SwitchCutReport.f(r.pure().medianGain())));
                }
            }
        }
        return out;
    }

    static String fingerprint(ScorerInputs in, java.time.LocalDate last) {
        AssetInput a = in.assets().getFirst();
        CandleFingerprint f = CandleFingerprint.of(a.slot().assetSymbol(), a.template().candles(), last);
        return f.firstDay() + " → " + f.lastDay() + ", " + f.count() + " bougies, " + f.checksum();
    }

    public static AssetRobustness runAsset(ScorerInputs in, SwitchRobustnessConfig cfg, ExecutorService pool) {
        List<Callable<AssetCut>> sizeTasks = new ArrayList<>();
        for (double pct : cfg.sizes()) {
            sizeTasks.add(() -> SwitchCutStudy.run(in, cfg.cut().withReentry(pct)));
        }
        List<AssetCut> cuts = ExposurePolicyStudy.ordered(sizeTasks, pool);
        List<SizeResult> sizes = new ArrayList<>();
        for (int i = 0; i < cfg.sizes().size(); i++) {
            double pct = cfg.sizes().get(i);
            sizes.add(new SizeResult(pct, !cfg.newSizes().contains(pct), cuts.get(i), rule1(cuts.get(i), cfg)));
        }
        Setup su = setup(in, cfg.cut().parity());
        List<NoiseResult> noise = su.evs().isEmpty() ? List.of() : noise(su, cfg, pool);
        long replays = cuts.stream().mapToLong(AssetCut::replays).sum() + su.replays();
        return new AssetRobustness(in.assets().getFirst().slot().assetSymbol(), in.cash0(), su.reference(), fingerprint(in, cfg.cut().parity().lastEnd()), cuts.getFirst().blockLabels(),
                sizes, trend(sizes, cfg), noise, replays);
    }

    // ------------------------------------------------------------------ règle 4 : portefeuille

    private record LevelData(double multiplier, List<List<Point>> pts, int[] sells, int[] buys) {
    }

    /** Wallet BTC + ETH à cash commun (somme des capitaux d'E1), même multiplicateur sur les deux liens (base de référence de chaque actif), ATH désactivée. */
    public static WalletResult runWallet(ScorerInputs btc, ScorerInputs eth, SwitchRobustnessConfig cfg, ExecutorService pool) {
        ParityConfig pc = cfg.cut().parity();
        RainbowAtrParamSet[] offBtc = SwitchCutStudy.withoutAth(new SensitivityEvaluator(btc.withEndDate(pc.lastEnd()), pc.blockMonths()).template().sets());
        RainbowAtrParamSet[] offEth = SwitchCutStudy.withoutAth(new SensitivityEvaluator(eth.withEndDate(pc.lastEnd()), pc.blockMonths()).template().sets());
        double refBtc = new SensitivityEvaluator(btc.withEndDate(pc.lastEnd()), pc.blockMonths()).referenceBase(offBtc);
        double refEth = new SensitivityEvaluator(eth.withEndDate(pc.lastEnd()), pc.blockMonths()).referenceBase(offEth);
        AssetInput ab = btc.assets().getFirst();
        AssetInput ae = eth.assets().getFirst();
        double cash = btc.cash0() + eth.cash0();
        ScorerInputs wallet = new ScorerInputs(List.of(ab.withTemplate(ab.template().withSets(offBtc)), ae.withTemplate(ae.template().withSets(offEth))), cash, pc.lastEnd(),
                btc.baseSettings(), btc.limits(), btc.profile(), btc.windows(), btc.dispersionPenalty(), btc.maxFinalStake(), btc.completeCosts());
        List<WalletEvaluator> evs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Block b : TuneBlocks.build(pc.lastEnd(), pc.blockMonths(), pc.blockCount())) {
            WalletEvaluator ev = new WalletEvaluator(wallet.withEndDate(b.to()), b.from());
            if (ev.line(new double[]{refBtc, refEth}) != null) {
                evs.add(ev);
                labels.add(b.label());
            }
        }
        List<Double> mults = cfg.cut().multipliers();
        List<MultiLinkReplayEngine.SwitchCut> variants = new ArrayList<>();
        variants.add(null);
        for (double w : cfg.noiseSizes()) {
            variants.add(new MultiLinkReplayEngine.SwitchCut(cfg.cut().sellFraction(), w * cfg.walletShare()));
        }
        List<Callable<LevelData>> tasks = new ArrayList<>();
        for (double m : evs.isEmpty() ? List.<Double>of() : mults) {
            tasks.add(() -> {
                List<List<Point>> pts = new ArrayList<>();
                int[] sells = new int[variants.size()];
                int[] buys = new int[variants.size()];
                for (int v = 0; v < variants.size(); v++) {
                    List<Point> ps = new ArrayList<>();
                    for (WalletEvaluator ev : evs) {
                        WalletEvaluator.CutLine l = ev.lineCut(new double[]{m * refBtc, m * refEth}, variants.get(v));
                        if (l == null) {
                            return null;
                        }
                        ps.add(SwitchCutStudy.point(l.line()));
                        sells[v] += l.cutSells();
                        buys[v] += l.cutBuys();
                    }
                    pts.add(ps);
                }
                return new LevelData(m, pts, sells, buys);
            });
        }
        List<LevelData> grid = ExposurePolicyStudy.ordered(tasks, pool).stream().filter(x -> x != null).toList();
        List<WalletSize> sizes = new ArrayList<>();
        for (int v = 1; v < variants.size(); v++) {
            int vv = v;
            List<Level> levels = grid.stream().map(g -> new Level(g.multiplier(), g.pts().getFirst(), g.pts().get(vv), g.sells()[vv], g.buys()[vv], 0.0)).toList();
            List<WalletCap> caps = new ArrayList<>();
            int ok = 0;
            for (CapResult r : caps(levels, pc)) {
                Double dPure = verify(evs, r.pure(), refBtc, refEth, null);
                Double dCut = verify(evs, r.cut(), refBtc, refEth, variants.get(v));
                boolean pureHolds = dPure != null && dPure >= r.capPct() - cfg.capTolerancePts() - EPS;
                boolean cutHolds = dCut != null && dCut >= r.capPct() - cfg.capTolerancePts() - EPS;
                boolean good = positive(r) && cutHolds;
                ok += good ? 1 : 0;
                caps.add(new WalletCap(r.profile(), r.capPct(), r, dPure, pureHolds, dCut, cutHolds, good));
            }
            List<DdAtMultiplier> dd = new ArrayList<>();
            for (double m : cfg.reportMultipliers()) {
                Level l = levels.stream().filter(x -> Math.abs(x.multiplier() - m) < EPS).findFirst().orElse(null);
                Double p = l == null ? null : SwitchCutReport.worst(l.pure());
                Double c = l == null ? null : SwitchCutReport.worst(l.cut());
                dd.add(new DdAtMultiplier(m, p, c, p == null ? null : c - p));
            }
            List<WalletLevel> wl = levels.stream().map(l -> new WalletLevel(l.multiplier(), SwitchCutReport.worst(l.pure()),
                    SwitchCutReport.median(l.pure().stream().map(Point::gainPct).toList()), SwitchCutReport.worst(l.cut()),
                    SwitchCutReport.median(l.cut().stream().map(Point::gainPct).toList()), l.cutSells(), l.cutBuys())).toList();
            double w = cfg.noiseSizes().get(v - 1);
            sizes.add(new WalletSize(w, w * cfg.walletShare(), caps, ok, ok >= cfg.minCaps() ? "TIENT" : "NE_TIENT_PAS", dd, wl));
        }
        long replays = 2L * evs.stream().mapToLong(WalletEvaluator::replays).sum();
        return new WalletResult(cash, labels, refBtc, refEth, fingerprint(btc, pc.lastEnd()), fingerprint(eth, pc.lastEnd()), sizes, replays);
    }

    /** Rejoue le wallet au multiplicateur interpolé : pire drawdown des blocs ; {@code null} si le plafond n'est pas encadré. */
    private static Double verify(List<WalletEvaluator> evs, AtCap at, double refBtc, double refEth, MultiLinkReplayEngine.SwitchCut cut) {
        if (!"INTERPOLE".equals(at.status())) {
            return null;
        }
        double worst = 0.0;
        for (WalletEvaluator ev : evs) {
            WalletEvaluator.CutLine l = ev.lineCut(new double[]{at.multiplier() * refBtc, at.multiplier() * refEth}, cut);
            if (l == null) {
                return null;
            }
            worst = Math.min(worst, l.line().maxDrawdownPct());
        }
        return worst;
    }

    // ------------------------------------------------------------------ verdicts

    private static String joinSizes(List<AssetRobustness> assets, java.util.function.Function<SizeResult, String> f) {
        List<String> parts = new ArrayList<>();
        for (AssetRobustness a : assets) {
            parts.add(a.asset() + " " + a.sizes().stream().map(s -> String.format(Locale.ROOT, "%.0f %% %s", s.reentryPct(), f.apply(s))).toList());
        }
        return String.join(" ; ", parts);
    }

    static boolean sizeRule1(List<AssetRobustness> assets, double pct) {
        return assets.stream().allMatch(a -> a.sizes().stream().anyMatch(s -> s.reentryPct() == pct && "ROBUSTE".equals(s.rule1().verdict())));
    }

    static boolean sizeRule3(List<AssetRobustness> assets, double pct) {
        return assets.stream().allMatch(a -> a.noise().stream().anyMatch(n -> n.reentryPct() == pct && "STABLE".equals(n.verdict())));
    }

    public static Verdicts verdicts(List<AssetRobustness> assets, WalletResult wallet, SwitchRobustnessConfig cfg) {
        String r1 = joinSizes(assets, s -> s.rule1().verdict() + (s.rule1().flippingBlock() == null ? "" : " (" + s.rule1().flippingBlock() + ")"));
        StringBuilder r2 = new StringBuilder();
        Set<Double> bests = new LinkedHashSet<>();
        for (AssetRobustness a : assets) {
            r2.append(a.asset()).append(" : ").append(a.trend().growth()).append(", meilleure taille ")
                    .append(a.trend().bestSize() == null ? "n/d" : String.format(Locale.ROOT, "%.0f %%", a.trend().bestSize())).append(" ; ");
            bests.add(a.trend().bestSize());
        }
        boolean common = bests.size() == 1 && bests.iterator().next() != null;
        r2.append(common ? String.format(Locale.ROOT, "meilleure taille commune : %.0f %% (règle générique)", bests.iterator().next()) : "pas de meilleure taille commune (dépendante de l'actif)");
        StringBuilder r3 = new StringBuilder();
        for (AssetRobustness a : assets) {
            r3.append(a.asset()).append(' ').append(a.noise().stream().map(n -> String.format(Locale.ROOT, "%.0f %% %s (%d/4)", n.reentryPct(), n.verdict(), n.capsPass())).toList()).append(" ; ");
        }
        String r4 = wallet.sizes().stream().map(s -> String.format(Locale.ROOT, "%.0f %% %s (%d/4)", s.reentryPct(), s.verdict(), s.capsOk())).toList().toString();
        List<SizeVerdict> bySize = new ArrayList<>();
        for (double pct : cfg.noiseSizes()) {
            boolean a1 = sizeRule1(assets, pct);
            boolean a3 = sizeRule3(assets, pct);
            boolean a4 = wallet.sizes().stream().anyMatch(s -> s.reentryPct() == pct && "TIENT".equals(s.verdict()));
            List<String> causes = new ArrayList<>();
            if (!a1) {
                causes.add("cycle dominant");
            }
            if (!a3) {
                causes.add("bruit");
            }
            if (!a4) {
                causes.add("portefeuille");
            }
            bySize.add(new SizeVerdict(pct, a1, a3, a4, a1 && a3 && a4, causes.isEmpty() ? "-" : String.join(", ", causes)));
        }
        boolean robust = bySize.stream().anyMatch(SizeVerdict::robust);
        String cause = robust ? "règles 1, 3 et 4 positives pour " + bySize.stream().filter(SizeVerdict::robust).map(v -> String.format(Locale.ROOT, "%.0f %%", v.reentryPct())).toList()
                : bySize.stream().map(v -> String.format(Locale.ROOT, "taille %.0f %% : %s", v.reentryPct(), v.causes())).reduce((x, y) -> x + " ; " + y).orElse("n/d");
        return new Verdicts(r1, r2.toString(), r3.toString(), r4, bySize, robust ? "COUPURE_ROBUSTE" : "COUPURE_NON_ROBUSTE", cause);
    }
}
