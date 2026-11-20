package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.AssetReport;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Band;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.BlockResult;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Calib;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Estimate;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Metrics;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * Étape B' : à exposition moyenne égale, le preset en place fait-il mieux qu'un DCA fixe ? Le DCA fixe est calé (dichotomie sur son
 * montant de base, même cash commun, même contrainte de cash : tout-ou-rien, jamais au-delà, sans recharge) pour que son exposition
 * moyenne dans le temps égale celle du preset sur le même bloc. Exposition = celle de l'étape A (valeur de la position / valeur totale du
 * portefeuille, moyenne sur les jours). Bande de bruit = le même DCA relancé avec {@code shiftCount} décalages de date de début.
 * Hors ligne : rien n'est appliqué ni persisté. Pur, déterministe, aucun tirage.
 */
public final class ExposureParity {

    private static final double EPS = 1e-9;

    private ExposureParity() {
    }

    /** Résultat d'un calibrage : le montant, le rejeu du DCA calé (réutilisé comme ligne « DCA égal ») et l'écart obtenu. */
    public record Calibration(double base, Parity parity, Calib calib) {
    }

    /**
     * Dichotomie sur le montant de base ({@code ]0 ; cash0]}) : l'exposition du DCA croît avec lui. S'arrête dès que l'écart est sous le
     * cinquième de la tolérance, sinon garde le meilleur essai ; {@code reached} dit si la tolérance est tenue.
     *
     * @param first rejeu du preset sur la plage ({@code ev.parity(sets, full, 0, null)}) : sa cible d'exposition
     */
    public static Calibration calibrate(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, boolean full, Parity first, double cash0,
                                        double tolerancePts, int maxIterations) {
        return calibrate(ev, sets, full, first, cash0, tolerancePts, maxIterations, null);
    }

    /** Comme {@link #calibrate(SensitivityEvaluator, RainbowAtrParamSet[], boolean, Parity, double, double, int)} avec une base de lien imposée (étape C1). */
    public static Calibration calibrate(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, boolean full, Parity first, double cash0,
                                        double tolerancePts, int maxIterations, Double linkBase) {
        double target = first.preset().avgExposurePct();
        double lo = 0.0;
        double hi = cash0;
        Parity best = null;
        double bestBase = 0.0;
        double bestErr = Double.MAX_VALUE;
        int n = 0;
        while (n < maxIterations) {
            double mid = (lo + hi) / 2.0;
            Parity p = ev.parity(sets, full, 0, mid, linkBase);
            n++;
            if (p == null) {
                break;
            }
            double err = p.dca().avgExposurePct() - target;
            if (Math.abs(err) < bestErr) {
                bestErr = Math.abs(err);
                best = p;
                bestBase = mid;
            }
            if (bestErr <= tolerancePts / 5.0) {
                break;
            }
            if (err < 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        if (best == null) {
            throw new IllegalStateException("Calibrage impossible : plage non rejouable");
        }
        return new Calibration(bestBase, best, new Calib(bestBase, best.dca().avgExposurePct(), target, best.dca().avgExposurePct() - target,
                bestErr <= tolerancePts, n));
    }

    /** Décalages de début, en jours : {@code round(i × max / (count − 1))}, i = 0..count−1 (0 inclus, max inclus). */
    public static List<Integer> shifts(int count, int maxDays) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add((int) Math.round(i * (double) maxDays / (count - 1)));
        }
        return List.copyOf(out);
    }

    /** Percentile (0..1), interpolation linéaire entre rangs ; {@code null} si aucune valeur. */
    public static Double percentile(List<Double> values, double q) {
        double[] v = values.stream().filter(x -> x != null && !x.isNaN()).mapToDouble(Double::doubleValue).sorted().toArray();
        if (v.length == 0) {
            return null;
        }
        double pos = q * (v.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return v[lo] + (v[hi] - v[lo]) * (pos - lo);
    }

    /** Gain / |drawdown| ; {@code null} sans drawdown (rapport non défini). */
    static Double gainOverDd(double gainPct, double ddPct) {
        return Math.abs(ddPct) < EPS ? null : gainPct / Math.abs(ddPct);
    }

    static Metrics metrics(Line l) {
        Double engaged = l.avgExposurePct() > EPS ? 100.0 * l.pnlPct() / l.avgExposurePct() : null;
        return new Metrics(l.pnlPct(), engaged, l.avgExposurePct(), l.maxDrawdownPct(), gainOverDd(l.pnlPct(), l.maxDrawdownPct()), l.buys(),
                l.sells());
    }

    private static boolean beats(Double a, Double b) {
        return a != null && b != null && a > b + EPS;
    }

    /** Rejeux au pire : par plage (blocs + historique complet), calibrage + décalages + preset ; le challenger ajoute 1 rejeu par plage. */
    public static Estimate estimate(ParityConfig cfg, int validBlocks) {
        long ranges = validBlocks + 1L;
        long total = ranges * (1L + cfg.maxIterations() + cfg.shiftCount() + 1L);
        return new Estimate(total, cfg.replayMs(), total * cfg.replayMs() / 60_000.0);
    }

    /** Mesure d'une plage ({@code from}-{@code to} d'un bloc, ou historique complet). */
    static BlockResult measure(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, RainbowAtrParamSet[] challenger, boolean full,
                               int index, String label, ParityConfig cfg, double cash0, List<Integer> shifts) {
        Parity first = ev.parity(sets, full, 0, null);
        if (first == null) {
            return BlockResult.invalid(index, label, "écarté : pas assez de bougies de préchauffage");
        }
        Calibration cal = calibrate(ev, sets, full, first, cash0, cfg.tolerancePts(), cfg.maxIterations());
        List<Double> gains = new ArrayList<>();
        List<Double> ratios = new ArrayList<>();
        for (int s : shifts) {
            Parity p = s == 0 ? cal.parity() : ev.parity(sets, full, s, cal.base());
            if (p == null) {
                gains.add(null);
                ratios.add(null);
                continue;
            }
            gains.add(p.dca().pnlPct());
            ratios.add(gainOverDd(p.dca().pnlPct(), p.dca().maxDrawdownPct()));
        }
        Band band = Band.of(shifts, gains, ratios, cfg.bandPercentile());
        Metrics preset = metrics(first.preset());
        Metrics equal = metrics(cal.parity().dca());
        Metrics usual = metrics(first.dca());
        Metrics chall = null;
        if (challenger != null) {
            Parity c = ev.parity(challenger, full, 0, null);
            chall = c == null ? null : metrics(c.preset());
        }
        double dGain = preset.gainPct() - equal.gainPct();
        Double dRatio = preset.gainOverDd() == null || equal.gainOverDd() == null ? null : preset.gainOverDd() - equal.gainOverDd();
        boolean ddOk = preset.maxDrawdownPct() - equal.maxDrawdownPct() >= -cfg.drawdownMargin();
        boolean gainWin = beats(preset.gainPct(), equal.gainPct());
        boolean gainOut = beats(preset.gainPct(), band.gainP80());
        boolean ratioWin = beats(preset.gainOverDd(), equal.gainOverDd());
        boolean ratioOut = beats(preset.gainOverDd(), band.ratioP80());
        boolean pass = ddOk && ((gainWin && gainOut) || (ratioWin && ratioOut));
        String failure = pass ? "" : !gainWin && !ratioWin ? "gain" : !ddOk ? "drawdown" : "bruit";
        return new BlockResult(index, label, first.from(), first.to(), true, "rejouable", preset, first.realized(), first.latent(), equal,
                cal.calib(), band, usual, chall, dGain, dRatio, ddOk, gainWin, gainOut, ratioWin, ratioOut, pass, failure);
    }

    /**
     * Mesure un actif : 4 blocs de 24 mois (mêmes que l'étape B) puis l'historique complet (lecture seule, hors verdict).
     *
     * @param challenger jeux du challenger de l'étape B (colonne de repère) ; {@code null} si non disponible pour cet actif
     */
    public static AssetReport run(ScorerInputs in, ParityConfig cfg, RainbowAtrParamSet[] challenger) {
        long t0 = System.nanoTime();
        double cash0 = in.cash0();
        List<Block> specs = TuneBlocks.build(cfg.lastEnd(), cfg.blockMonths(), cfg.blockCount());
        List<Integer> shifts = shifts(cfg.shiftCount(), cfg.shiftMaxDays());
        RainbowAtrParamSet[] sets = null;
        List<BlockResult> blocks = new ArrayList<>();
        long replays = 0;
        SensitivityEvaluator head = null;
        for (Block b : specs) {
            SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(b.to()), cfg.blockMonths(), b.from());
            if (sets == null) {
                sets = ev.template().sets();
                head = ev;
            }
            blocks.add(measure(ev, sets, challenger, false, b.index(), b.label(), cfg, cash0, shifts));
            replays += ev.replays();
        }
        SensitivityEvaluator fullEv = new SensitivityEvaluator(in.withEndDate(cfg.lastEnd()), cfg.blockMonths());
        BlockResult full = measure(fullEv, sets, challenger, true, -1, "historique complet", cfg, cash0, shifts);
        replays += fullEv.replays();
        Verdict verdict = verdict(blocks, cfg);
        return new AssetReport(head.asset(), in.assets().getFirst().source(), cash0, CandleFingerprint.of(head.asset(),
                head.template().candles(), cfg.lastEnd()), blocks, full, verdict, challenger != null, shifts, replays,
                (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Règle fixée avant de regarder : « timing utile » si, sur au moins {@code minBlocksPass} blocs, le preset bat le DCA égal en gain %
     * du capital OU en gain / |drawdown|, sans drawdown pire de plus de {@code drawdownMargin} pts, et au-dessus du percentile de la bande
     * de bruit (sur la même mesure) ; ET si la médiane des Δ gain (preset − DCA égal, % du capital) sur les blocs rejouables est > 0.
     * Sinon « non démontré », avec le côté de l'échec (gain, drawdown, bruit : cause la plus fréquente parmi les blocs en échec).
     */
    public static Verdict verdict(List<BlockResult> blocks, ParityConfig cfg) {
        List<BlockResult> valid = blocks.stream().filter(BlockResult::valid).toList();
        int passes = (int) valid.stream().filter(BlockResult::pass).count();
        double[] deltas = valid.stream().mapToDouble(BlockResult::deltaGain).sorted().toArray();
        double median = deltas.length == 0 ? 0.0 : deltas.length % 2 == 1 ? deltas[deltas.length / 2]
                : (deltas[deltas.length / 2 - 1] + deltas[deltas.length / 2]) / 2.0;
        int gain = (int) valid.stream().filter(b -> "gain".equals(b.failure())).count();
        int dd = (int) valid.stream().filter(b -> "drawdown".equals(b.failure())).count();
        int noise = (int) valid.stream().filter(b -> "bruit".equals(b.failure())).count();
        boolean useful = passes >= cfg.minBlocksPass() && median > 0;
        String side = "-";
        if (!useful) {
            int max = Math.max(gain, Math.max(dd, noise));
            side = passes >= cfg.minBlocksPass() ? "gain (médiane Δ ≤ 0)" : max == 0 ? "gain" : max == gain ? "gain" : max == dd ? "drawdown" : "bruit";
        }
        return new Verdict(useful ? "TIMING_UTILE" : "NON_DEMONTRE", passes, valid.size(), median, gain, dd, noise, side);
    }
}
