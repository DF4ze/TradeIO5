package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Window;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposurePolicyStudy.ConstantPick;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.AssetProduction;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.CapitalRun;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.ConfigRun;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.Invariance;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.Level;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.Measure;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.ProfileProduction;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.WalletBand;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.WalletProduction;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureProductionReport.WalletProfile;
import fr.ses10doigts.tradeIO5.service.optimizer.ScorerInputs.AssetInput;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.LongSupplier;

/**
 * Étape C4 : rend le calage de C3 exploitable. Hors ligne : rien n'est appliqué ni persisté. Aucun nouveau levier : le niveau de chaque profil
 * (multiplicateur du montant de base, calé comme en C3 au plafond / k) est exprimé en TAUX de base (base par jour / cash), puis rejoué
 * (1) à d'autres capitaux, (2) avec la bande d'exposition du profil et avec la proposition d'onboarding actuelle (même rejeu multi-liens
 * que l'Expert : {@code SizingCore} + {@code LiveModulation}), (3) en wallet BTC + ETH à cash commun. Déterministe (le pool ne change que la durée).
 */
public final class ExposureProductionStudy {

    private static final double EPS = 1e-9;

    private ExposureProductionStudy() {
    }

    /** Une plage rejouable : montant(s) de base en USDC par lien → ligne de mesure (null si non rejouable). */
    @FunctionalInterface
    interface RangeEval {
        Line line(double[] bases);
    }

    /** Toutes les plages d'une configuration : fenêtres glissantes, historique complet, blocs de C1 ; compteur de rejeux. */
    record Rig(List<Window> windows, List<RangeEval> win, RangeEval full, List<RangeEval> blocks, List<LongSupplier> replays) {
        int ranges() {
            return win.size() + 1 + blocks.size();
        }

        long replayCount() {
            return replays.stream().mapToLong(LongSupplier::getAsLong).sum();
        }
    }

    // ------------------------------------------------------------------ plages

    /** Un actif seul : même évaluateur et mêmes plages que C3 ({@link SensitivityEvaluator}). */
    static Rig singleRig(ScorerInputs in, ExposureCalibrationConfig cal, List<Window> wins, List<Block> blocks) {
        RainbowAtrParamSet[] sets = new SensitivityEvaluator(in, cal.windowMonths()).template().sets();
        List<RangeEval> win = new ArrayList<>();
        List<LongSupplier> counters = new ArrayList<>();
        for (Window w : wins) {
            SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(w.to()), cal.windowMonths(), w.from());
            counters.add(ev::replays);
            win.add(b -> line(ev, sets, false, b[0]));
        }
        SensitivityEvaluator fullEv = new SensitivityEvaluator(in.withEndDate(cal.lastEnd()), cal.windowMonths());
        counters.add(fullEv::replays);
        List<RangeEval> bl = new ArrayList<>();
        for (Block b : blocks) {
            SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(b.to()), cal.parity().blockMonths(), b.from());
            counters.add(ev::replays);
            bl.add(x -> line(ev, sets, false, x[0]));
        }
        return new Rig(wins, win, b -> line(fullEv, sets, true, b[0]), bl, counters);
    }

    private static Line line(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, boolean full, double base) {
        SensitivityEvaluator.Parity p = ev.parity(sets, full, 0, null, base);
        return p == null ? null : p.preset();
    }

    /** Wallet à plusieurs liens : mêmes plages (fenêtres communes aux liens), un {@link WalletEvaluator} par plage. */
    static Rig walletRig(ScorerInputs in, ExposureCalibrationConfig cal, List<Window> wins, List<Block> blocks) {
        List<RangeEval> win = new ArrayList<>();
        List<LongSupplier> counters = new ArrayList<>();
        for (Window w : wins) {
            WalletEvaluator ev = new WalletEvaluator(in.withEndDate(w.to()), w.from());
            counters.add(ev::replays);
            win.add(ev::line);
        }
        WalletEvaluator fullEv = new WalletEvaluator(in.withEndDate(cal.lastEnd()), null);
        counters.add(fullEv::replays);
        List<RangeEval> bl = new ArrayList<>();
        for (Block b : blocks) {
            WalletEvaluator ev = new WalletEvaluator(in.withEndDate(b.to()), b.from());
            counters.add(ev::replays);
            bl.add(ev::line);
        }
        return new Rig(wins, win, fullEv::line, bl, counters);
    }

    // ------------------------------------------------------------------ mesures (pures sur les lignes)

    /** Mesure d'une configuration sur toutes les plages du rig ; {@code null} si aucune plage n'est rejouable. */
    static Measure measure(Rig rig, double[] bases, ExecutorService pool) {
        List<Callable<Line>> tasks = new ArrayList<>();
        rig.win().forEach(r -> tasks.add(() -> r.line(bases)));
        tasks.add(() -> rig.full().line(bases));
        rig.blocks().forEach(r -> tasks.add(() -> r.line(bases)));
        return fold(rig, ExposurePolicyStudy.ordered(tasks, pool));
    }

    static Measure fold(Rig rig, List<Line> lines) {
        int nw = rig.win().size();
        double worst = 0.0;
        String where = "n/d";
        int ranges = 0;
        for (int i = 0; i <= nw; i++) {
            Line l = lines.get(i);
            if (l == null) {
                continue;
            }
            ranges++;
            if (l.maxDrawdownPct() < worst - EPS) {
                worst = l.maxDrawdownPct();
                where = i == nw ? "historique complet" : "fenêtre " + rig.windows().get(i).label();
            }
        }
        Line full = lines.get(nw);
        List<Double> gains = new ArrayList<>();
        List<Double> expo = new ArrayList<>();
        for (int i = nw + 1; i < lines.size(); i++) {
            if (lines.get(i) != null) {
                gains.add(lines.get(i).pnlPct());
                expo.add(lines.get(i).avgExposurePct());
            }
        }
        if (ranges == 0) {
            return null;
        }
        return new Measure(worst, where, full == null ? Double.NaN : full.maxDrawdownPct(),
                expo.isEmpty() ? Double.NaN : expo.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                full == null ? Double.NaN : full.avgExposurePct(), gains.isEmpty() ? Double.NaN : ExposurePolicyStudy.median(gains), ranges);
    }

    /** Drawdown par niveau de la grille, par plage (fenêtres puis historique) : NaN si non rejouable. */
    static double[][] drawdownTable(Rig rig, List<Double> grid, double ref, ExecutorService pool) {
        List<Callable<double[]>> tasks = new ArrayList<>();
        List<RangeEval> all = new ArrayList<>(rig.win());
        all.add(rig.full());
        for (RangeEval r : all) {
            tasks.add(() -> {
                double[] dd = new double[grid.size()];
                for (int l = 0; l < dd.length; l++) {
                    Line x = r.line(new double[]{grid.get(l) * ref});
                    dd[l] = x == null ? Double.NaN : x.maxDrawdownPct();
                }
                return dd;
            });
        }
        return ExposurePolicyStudy.ordered(tasks, pool).toArray(new double[0][]);
    }

    static ModulationSettings band(StartingProfile p) {
        return new ModulationSettings(p.exposureMin(), p.exposureMax(), ModulationDefaults.NEUTRAL_POWER, ModulationDefaults.NEUTRAL_BIAS);
    }

    static boolean holds(Measure m, double cap) {
        return m != null && m.worstDdPct() >= cap - EPS;
    }

    /** Facteur de réduction : plus grand f ≤ 1 (dichotomie à une dimension, {@code iterations} pas) tel que {@code worstDd(f)} tienne le plafond. */
    record FSearch(double f, Measure atF, boolean holdsAtOne, Measure atOne, int iterations) {
    }

    static FSearch searchF(java.util.function.DoubleFunction<Measure> at, double cap, int iterations) {
        Measure one = at.apply(1.0);
        if (holds(one, cap)) {
            return new FSearch(1.0, one, true, one, 0);
        }
        double lo = 0.0;
        double hi = 1.0;
        Measure atLo = null;
        for (int i = 0; i < iterations; i++) {
            double mid = (lo + hi) / 2.0;
            Measure m = at.apply(mid);
            if (holds(m, cap)) {
                lo = mid;
                atLo = m;
            } else {
                hi = mid;
            }
        }
        return new FSearch(lo, atLo, false, one, iterations);
    }

    /** Rejeux au pire (un wallet à 2 liens compte double) : niveaux, invariance, configurations B et C par actif ; dichotomie de f par profil et bande. */
    public static ExposureCalibrationReport.Estimate estimate(ExposureProductionConfig cfg, int windows, int assets) {
        ExposureCalibrationConfig cal = cfg.calibration();
        long ranges = windows + 1L + cal.parity().blockCount();
        long profiles = FrontierConfig.profileCaps().size();
        long perAsset = cal.grid().size() * ranges + profiles * ranges * (1L + cfg.capitals().size() + 2L);
        long wallet = assets >= 2 ? profiles * 2L * (1L + cfg.fIterations()) * ranges * 2L : 0L;
        long total = perAsset * assets + wallet;
        return new ExposureCalibrationReport.Estimate(total, cal.parity().replayMs(), total * cal.parity().replayMs() / 60_000.0);
    }

    // ------------------------------------------------------------------ un actif

    public static AssetProduction runAsset(ScorerInputs in, ExposureProductionConfig cfg, ExecutorService pool) {
        long t0 = System.nanoTime();
        ExposureCalibrationConfig cal = cfg.calibration();
        AssetInput link = in.assets().getFirst();
        SensitivityEvaluator head = new SensitivityEvaluator(in.withEndDate(cal.lastEnd()), cal.windowMonths());
        RainbowAtrParamSet[] sets = head.template().sets();
        double ref = head.referenceBase(sets);
        double cash0 = in.cash0();
        double mean = link.meanMultiplier() == null ? Double.NaN : link.meanMultiplier();
        List<Window> wins = ExposureCalibrationStudy.windowsFor(in, cal);
        List<Block> blocks = TuneBlocks.build(cal.lastEnd(), cal.parity().blockMonths(), cal.parity().blockCount());
        ScorerInputs neutral = in.withBaseSettings(ModulationSettings.neutral());
        List<StartingProfile> profiles = new ArrayList<>(FrontierConfig.profileCaps().keySet());
        List<Double> grid = cal.grid();
        long replays = 0;

        // 1. niveaux : pire drawdown par niveau (comme C3), niveau au plafond / k = 1 et / k
        Rig base = singleRig(neutral, cal, wins, blocks);
        double[][] dd = drawdownTable(base, grid, ref, pool);
        double[][] windowDd = Arrays.copyOfRange(dd, 0, wins.size());
        double[] fullDd = dd[wins.size()];
        double[] worst = ExposureCalibrationStudy.worstByLevel(wins, windowDd, fullDd, cal.lastEnd().plusDays(1));

        double onboarding = RainbowLiveOnboardingService.referenceBaseAmount(cash0, cfg.autonomyDays(), mean).doubleValue();
        List<ProfileProduction> out = new ArrayList<>();
        for (StartingProfile p : profiles) {
            double cap = FrontierConfig.profileCaps().get(p);
            ConstantPick ref1 = ExposureCalibrationStudy.pick(grid, worst, cap, cfg.kRef());
            ConstantPick pk = ExposureCalibrationStudy.pick(grid, worst, cap, cfg.k());
            Level levelRef = level(ref1, ref, cash0, mean);
            Level level = level(pk, ref, cash0, mean);

            // 2. invariance : même taux de base, capital de C3 (= A), puis les autres capitaux
            List<CapitalRun> runs = new ArrayList<>();
            Measure atC3 = measure(base, new double[]{level.baseRate() * cash0}, pool);
            runs.add(new CapitalRun(cash0, level.baseRate() * cash0, atC3));
            for (double capital : cfg.capitals()) {
                Rig rig = singleRig(neutral.withCash0(capital), cal, wins, blocks);
                double b = level.baseRate() * capital;
                runs.add(new CapitalRun(capital, b, measure(rig, new double[]{b}, pool)));
                replays += rig.replayCount();
            }
            double maxDd = 0.0;
            double maxGain = 0.0;
            List<String> broken = new ArrayList<>();
            for (CapitalRun r : runs.subList(1, runs.size())) {
                double gDd = Math.abs(r.measure().worstDdPct() - atC3.worstDdPct());
                double gGain = Math.abs(r.measure().medianGainPct() - atC3.medianGainPct());
                maxDd = Math.max(maxDd, gDd);
                maxGain = Math.max(maxGain, gGain);
                if (gDd > cfg.ddTolerancePts() + EPS || gGain > cfg.gainTolerancePts() + EPS) {
                    broken.add(String.format(Locale.ROOT, "capital %.0f : ΔDD %.2f pt, Δgain %.2f pt (base %.4f USDC/j)", r.capital(), gDd, gGain, r.base()));
                }
            }
            boolean ok = broken.isEmpty();
            String reading = ok ? String.format(Locale.ROOT, "invariance OK (écart max DD %.2f pt ≤ %.1f, gain %.2f pt ≤ %.1f)", maxDd, cfg.ddTolerancePts(),
                    maxGain, cfg.gainTolerancePts()) : "invariance NON : " + String.join(" ; ", broken);

            // 3. configurations A (neutre, = capital de C3), B (bande du profil), C (onboarding + bande du profil)
            ScorerInputs banded = in.withBaseSettings(band(p));
            Rig bandRig = singleRig(banded, cal, wins, blocks);
            ConfigRun a = run("A base recommandée, bande neutre", level.baseRate() * cash0, atC3, cap);
            Measure mb = measure(bandRig, new double[]{level.baseRate() * cash0}, pool);
            ConfigRun b = run("B base recommandée + bande " + p.exposureMin() + "–" + p.exposureMax(), level.baseRate() * cash0, mb, cap);
            Measure mc = measure(bandRig, new double[]{onboarding}, pool);
            ConfigRun c = run("C onboarding " + cfg.autonomyDays() + " j + bande " + p.exposureMin() + "–" + p.exposureMax(), onboarding, mc, cap);
            replays += bandRig.replayCount();
            boolean matters = Math.abs(mb.worstDdPct() - atC3.worstDdPct()) >= cfg.bandMattersDdPts() - EPS
                    || Math.abs(mb.medianGainPct() - atC3.medianGainPct()) >= cfg.bandMattersGainPts() - EPS;
            out.add(new ProfileProduction(p.name(), cap, levelRef, level, new Invariance(runs, maxDd, maxGain, ok, reading), a, b, c, matters));
        }
        replays += base.replayCount();
        Multiplicity mult = new Multiplicity(grid.size(), wins.size(), base.ranges(), profiles.size(), replays);
        return new AssetProduction(head.asset(), link.source(), cash0, ref, CandleFingerprint.of(head.asset(), head.template().candles(), cal.lastEnd()),
                wins.size(), mean, onboarding, out, mult, (System.nanoTime() - t0) / 1_000_000);
    }

    private static ConfigRun run(String name, double base, Measure m, double cap) {
        return new ConfigRun(name, base, m, holds(m, cap), m == null ? Double.NaN : m.worstDdPct() - cap);
    }

    /** Niveau : multiplicateur du ×1 (base de référence {@code ref} USDC/j), taux = multiplicateur × ref / cash, autonomie = 1 / (taux × mult. moyen). */
    static Level level(ConstantPick pk, double ref, double cash0, double mean) {
        double rate = pk.multiplier() * ref / cash0;
        double aut = 1.0 / (rate * mean);
        ModulationDefaults.Limit lim = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS);
        return new Level(pk.multiplier(), pk.status(), rate, aut, aut >= lim.min().doubleValue() - EPS && aut <= lim.max().doubleValue() + EPS);
    }

    // ------------------------------------------------------------------ wallet

    /**
     * Wallet BTC + ETH à cash commun. Capital = somme des capitaux de C3, partage {@code walletShare} par lien ; base de chaque lien =
     * f × taux de son actif × capital × part. Bande neutre puis bande du profil ; f = 1 si le wallet tient déjà le plafond.
     */
    public static WalletProduction runWallet(ScorerInputs btc, ScorerInputs eth, AssetProduction btcRes, AssetProduction ethRes, ExposureProductionConfig cfg,
                                             ExecutorService pool) {
        long t0 = System.nanoTime();
        ExposureCalibrationConfig cal = cfg.calibration();
        double cash = btc.cash0() + eth.cash0();
        ScorerInputs wallet = new ScorerInputs(List.of(btc.assets().getFirst(), eth.assets().getFirst()), cash, cal.lastEnd(), ModulationSettings.neutral(),
                btc.limits(), btc.profile(), btc.windows(), btc.dispersionPenalty(), btc.maxFinalStake(), btc.completeCosts());
        LocalDate first = WalletEvaluator.firstReplayableDay(wallet);
        List<Window> wins = ExposureCalibrationStudy.windows(cal.lastEnd(), cal.windowMonths(), cal.windowStepDays(), first);
        List<Block> blocks = TuneBlocks.build(cal.lastEnd(), cal.parity().blockMonths(), cal.parity().blockCount());
        long replays = 0;
        List<WalletProfile> out = new ArrayList<>();
        Rig neutral = walletRig(wallet, cal, wins, blocks);
        for (int i = 0; i < btcRes.profiles().size(); i++) {
            ProfileProduction pb = btcRes.profiles().get(i);
            ProfileProduction pe = ethRes.profiles().get(i);
            StartingProfile p = StartingProfile.valueOf(pb.profile());
            double cap = pb.capPct();
            double allocated = cash * cfg.walletShare();
            Rig banded = walletRig(wallet.withBaseSettings(band(p)), cal, wins, blocks);
            WalletBand wn = walletBand("neutre", neutral, pb, pe, allocated, cap, cfg, pool);
            WalletBand wb = walletBand("profil " + p.exposureMin() + "–" + p.exposureMax(), banded, pb, pe, allocated, cap, cfg, pool);
            out.add(new WalletProfile(pb.profile(), cap, pb.level().baseRate(), pe.level().baseRate(), wn, wb));
            replays += banded.replayCount();
        }
        replays += neutral.replayCount();
        return new WalletProduction(cash, cfg.walletShare(), wins.size(), out, replays, (System.nanoTime() - t0) / 1_000_000);
    }

    private static WalletBand walletBand(String name, Rig rig, ProfileProduction pb, ProfileProduction pe, double allocated, double cap,
                                         ExposureProductionConfig cfg, ExecutorService pool) {
        double rb = pb.level().baseRate();
        double re = pe.level().baseRate();
        FSearch s = searchF(f -> measure(rig, new double[]{f * rb * allocated, f * re * allocated}, pool), cap, cfg.fIterations());
        return new WalletBand(name, s.holdsAtOne(), s.atOne(), s.f(), s.atF(), holds(s.atF(), cap), s.iterations(), s.f() * rb * allocated,
                s.f() * re * allocated);
    }
}
