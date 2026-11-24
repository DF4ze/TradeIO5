package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.AssetReport;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.BlockResult;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Étape D1a : un des 3 presets existants (BTC, ETH, PAXG), joué tel quel avec la Trend par défaut du live, bat-il le DCA fixe à exposition
 * moyenne égale sur un actif qui n'a servi à régler aucun preset ? Aucun réglage, rien appliqué ni persisté. Réutilise la mesure de B'
 * ({@link ExposureParity#run}, bande de bruit de 20 décalages) sur la ligne « preset pur » d'E1 (modulation ATH désactivée,
 * {@link SwitchCutStudy#withoutAth}). Pur et déterministe : aucun tirage ; l'ordre des paires est fixé (actif, puis preset).
 */
public final class TransferStudy {

    /** Presets sources, dans l'ordre fixe des paires. */
    public static final List<String> PRESET_ASSETS = List.of("BTC", "ETH", "PAXG");
    public static final String TRANSFERE = "TRANSFERE";
    public static final String NON_TRANSFERE = "NON_TRANSFERE";
    public static final String PROVISOIRE = "PROVISOIRE";
    public static final String NON_CONCLU = "NON_CONCLU";
    private static final double EPS = 1e-9;

    private TransferStudy() {
    }

    /**
     * Preset source : jeux Bear / Bull de l'actif source, fenêtre d'analyse de son preset et {@code baseRate} = taux de base du lien source
     * (montant de base du lien / capital de son wallet), invariant au capital : le preset est joué avec son propre niveau d'engagement, comme en B' / E1 (×1).
     */
    public record Source(String asset, RainbowAtrParamSet[] sets, int windowMonths, double baseRate) {
    }

    /** Actif cible : bougies D1 et étiquette de leur source (propagée jusqu'au rapport). */
    public record Target(String asset, String sourceLabel, List<MarketData> candles) {
        public LocalDate lastDay() {
            return candles.getLast().getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
        }
    }

    /** Un bloc d'une paire : Δ = gain preset − gain DCA égal (points de capital) et largeur de la bande de bruit (P80 de la bande − DCA égal, ≥ 0). */
    public record BlockDelta(int index, String label, boolean valid, double presetGain, double dcaGain, double delta, double noiseWidth,
                             boolean ddOk, double presetExposure) {
    }

    /**
     * Une paire (preset source, actif cible). {@code status} : {@code TRANSFERE} / {@code NON_TRANSFERE} (≥ {@code minBlocks} blocs),
     * {@code PROVISOIRE} (moins de blocs : signe rapporté, hors verdict), {@code NON_CONCLU} (plage non rejouable).
     * {@code provisionalSign} : signe du Δ du seul bloc (ou de l'historique complet à défaut), {@code null} hors provisoire.
     */
    public record PairResult(String target, String preset, boolean inSample, String sourceLabel, CandleFingerprint fingerprint, String status,
                             int validBlocks, int wonBlocks, Double medianDelta, Double medianNoise, Integer provisionalSign,
                             List<BlockDelta> blocks, BlockDelta full, long replays, String note) {
    }

    /** Verdicts. {@code genericPresets} : presets {@code GENERIQUE} ; {@code volumeShare} : rapports de la règle 3. */
    public record Verdict(String verdict, List<String> genericPresets, String bestPooledPreset, Map<String, Double> pooledMedianByPreset,
                          Map<String, Double> genericShareByPreset, Map<String, String> volumeChoice, int volumeAssets,
                          double volumeTransfereShare, double volumeBeatShare, boolean volumeRulePasses, int concludedAssets, String text) {
    }

    // ------------------------------------------------------------------ mesure d'une paire

    /** Entrée du rejeu : actif cible, Trend par défaut du live, jeux du preset source sans modulation ATH, montant de base = taux de base du lien source x capital. */
    static ScorerInputs inputs(Target t, Source s, TransferConfig cfg, RainbowModulationControl limits) {
        return inputs(t, s, cfg, limits, true);
    }

    /** {@code pureTiming} faux : le preset est joué tel que le live l'applique (modulation ATH comprise), comme le calage C3 de BTC / ETH. */
    static ScorerInputs inputs(Target t, Source s, TransferConfig cfg, RainbowModulationControl limits, boolean pureTiming) {
        RainbowAtrParamSet[] pure = pureTiming ? SwitchCutStudy.withoutAth(s.sets()) : s.sets();
        RainbowAtrGlobals g = pure[0].globals();
        double mean = (g.multX2() + g.multX1() + g.multX0_5()) / 3.0;
        double base = s.baseRate() * cfg.cash0();
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(t.candles());
        LinkSignalReplay.Config c = new LinkSignalReplay.Config(ds, t.candles(), TrendMixCalculator.Params.defaults(), RangeMapping.KEEP_PREVIOUS,
                pure, 0, 0, t.candles().size() - 1, base, Map.of(), Map.of(), Set.of(), s.windowMonths());
        ScorerInputs.AssetInput a = new ScorerInputs.AssetInput(new LiveSlot(1L, 1L, t.asset(), 1L, 1, 100.0, null), c, t.sourceLabel(), mean, base);
        return ScorerInputs.of(List.of(a), cfg.cash0(), t.lastDay(), StartingProfile.BALANCED, limits);
    }

    public static PairResult pair(Target t, Source s, TransferConfig cfg, RainbowModulationControl limits) {
        boolean inSample = t.asset().equals(s.asset());
        ScorerInputs in = inputs(t, s, cfg, limits);
        try {
            AssetReport r = ExposureParity.run(in, cfg.parity(t.lastDay()), null);
            return evaluate(t.asset(), s.asset(), inSample, t.sourceLabel(), r.fingerprint(), r.blocks().stream().map(TransferStudy::delta).toList(),
                    delta(r.full()), r.replays(), cfg);
        } catch (RuntimeException e) {
            return new PairResult(t.asset(), s.asset(), inSample, t.sourceLabel(), null, NON_CONCLU, 0, 0, null, null, null, List.of(), null, 0,
                    "plage non rejouable : " + e.getMessage());
        }
    }

    static BlockDelta delta(BlockResult b) {
        if (!b.valid()) {
            return new BlockDelta(b.index(), b.label(), false, 0, 0, 0, 0, false, 0);
        }
        Double p80 = b.band() == null ? null : b.band().gainP80();
        double width = p80 == null || p80.isNaN() ? 0.0 : Math.max(0.0, p80 - b.equalDca().gainPct());
        return new BlockDelta(b.index(), b.label(), true, b.preset().gainPct(), b.equalDca().gainPct(), b.deltaGain(), width, b.ddOk(), b.preset().exposurePct());
    }

    /**
     * Règle {@code TRANSFERE} (fixée avant lecture) : ≥ {@code minBlocks} blocs valides ; Δ &gt; 0 sur ≥ {@code blockWinShare} des blocs ET Δ médian &gt; 0
     * ET Δ médian au-dessus de la bande de bruit du DCA (médiane des largeurs de bande des blocs). Sinon {@code NON_TRANSFERE}. Moins de blocs :
     * {@code PROVISOIRE} (signe du Δ rapporté, jamais de verdict).
     */
    static PairResult evaluate(String target, String preset, boolean inSample, String label, CandleFingerprint fp, List<BlockDelta> blocks, BlockDelta full,
                               long replays, TransferConfig cfg) {
        List<BlockDelta> valid = blocks.stream().filter(BlockDelta::valid).toList();
        int n = valid.size();
        int wins = (int) valid.stream().filter(b -> b.delta() > EPS).count();
        Double med = n == 0 ? null : median(valid.stream().map(BlockDelta::delta).toList());
        Double noise = n == 0 ? null : median(valid.stream().map(BlockDelta::noiseWidth).toList());
        if (n < cfg.minBlocks()) {
            Integer sign = n == 1 ? signOf(valid.getFirst().delta()) : full != null && full.valid() ? signOf(full.delta()) : null;
            String note = n == 0 && sign == null ? "plage non rejouable" : "moins de " + cfg.minBlocks() + " blocs de " + cfg.blockMonths()
                    + " mois rejouables : provisoire, aucun verdict";
            return new PairResult(target, preset, inSample, label, fp, n == 0 && sign == null ? NON_CONCLU : PROVISOIRE, n, wins, med, noise, sign,
                    blocks, full, replays, note);
        }
        boolean ok = wins >= cfg.blockWinShare() * n - EPS && med > 0 && med > noise;
        return new PairResult(target, preset, inSample, label, fp, ok ? TRANSFERE : NON_TRANSFERE, n, wins, med, noise, null, blocks, full, replays, "");
    }

    private static Integer signOf(double d) {
        return d > EPS ? 1 : d < -EPS ? -1 : 0;
    }

    static Double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (a.length == 0) {
            return null;
        }
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2.0;
    }

    /** Les 3 paires de chaque cible, dans l'ordre fixe (cibles données, puis presets de {@link #PRESET_ASSETS}) ; en parallèle si {@code pool} non nul. */
    public static List<PairResult> runAll(List<Target> targets, List<Source> sources, TransferConfig cfg, RainbowModulationControl limits,
                                          ExecutorService pool) {
        List<Future<PairResult>> futures = new ArrayList<>();
        List<PairResult> out = new ArrayList<>();
        for (Target t : targets) {
            for (Source s : sources) {
                if (pool == null) {
                    out.add(pair(t, s, cfg, limits));
                } else {
                    futures.add(pool.submit(() -> pair(t, s, cfg, limits)));
                }
            }
        }
        for (Future<PairResult> f : futures) {
            try {
                out.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ volatilité (règle 3) : aucune donnée de test

    /** ATR (RMA, {@code period}) en % du cours de clôture, médiane sur tout l'historique de l'actif (préchauffage exclu). */
    public static double medianAtrPct(List<MarketData> candles, int period) {
        double[] atr = RainbowAtrDataset.fromMarketData(candles).atr(period);
        List<Double> v = new ArrayList<>();
        for (int i = period; i < candles.size() && i < atr.length; i++) {
            double close = candles.get(i).getClose().doubleValue();
            if (close > 0 && !Double.isNaN(atr[i])) {
                v.add(100.0 * atr[i] / close);
            }
        }
        Double m = median(v);
        return m == null ? Double.NaN : m;
    }

    /**
     * Preset de départ d'un actif : celui de l'actif source dont l'ATR % médian est le plus proche (écart en log du rapport des deux ATR %, sans
     * paramètre libre ; égalité : ordre de {@link #PRESET_ASSETS}). N'utilise AUCUN résultat de test : seulement les volatilités.
     */
    public static String chooseByVolatility(double assetAtrPct, Map<String, Double> sourceAtrPct) {
        String best = null;
        double bestGap = Double.MAX_VALUE;
        for (String p : PRESET_ASSETS) {
            double gap = Math.abs(Math.log(assetAtrPct / sourceAtrPct.get(p)));
            if (gap < bestGap - EPS) {
                bestGap = gap;
                best = p;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ verdicts (règles 2, 3 et 4)

    /**
     * Verdict global, dans cet ordre : {@code NON_CONCLUANT} si moins de {@code minConcludedAssets} actifs hors échantillon conclus ; sinon
     * {@code PRESET_GENERIQUE} (règle 2) ; sinon {@code CHOIX_PAR_VOLATILITE} (règle 3) ; sinon {@code RIEN_NE_TRANSFERE}.
     * Les paires dans l'échantillon (preset réglé pour cet actif) ne comptent jamais. La règle 3 porte sur les actifs hors des 3 sources
     * (pour une source, le preset le plus proche est le sien : dans l'échantillon).
     */
    public static Verdict verdict(List<PairResult> pairs, Map<String, Double> atrPct, TransferConfig cfg) {
        List<PairResult> oos = pairs.stream().filter(p -> !p.inSample()).toList();
        Set<String> concluded = new java.util.TreeSet<>();
        oos.stream().filter(p -> isConcluded(p)).forEach(p -> concluded.add(p.target()));
        Map<String, Double> share = new LinkedHashMap<>();
        Map<String, Double> pooled = new LinkedHashMap<>();
        List<String> generic = new ArrayList<>();
        for (String preset : PRESET_ASSETS) {
            List<PairResult> c = oos.stream().filter(p -> p.preset().equals(preset) && isConcluded(p)).toList();
            double s = c.isEmpty() ? 0.0 : (double) c.stream().filter(p -> TRANSFERE.equals(p.status())).count() / c.size();
            share.put(preset, s);
            List<Double> deltas = new ArrayList<>();
            c.forEach(p -> p.blocks().stream().filter(BlockDelta::valid).forEach(b -> deltas.add(b.delta())));
            Double m = median(deltas);
            pooled.put(preset, m == null ? Double.NaN : m);
            if (!c.isEmpty() && s >= cfg.genericShare() - EPS) {
                generic.add(preset);
            }
        }
        String bestPooled = PRESET_ASSETS.stream().filter(p -> !Double.isNaN(pooled.get(p))).max(Comparator.comparingDouble(pooled::get)).orElse(null);
        // règle 3 : actifs hors sources, conclus pour le preset choisi
        Map<String, String> choice = new LinkedHashMap<>();
        int volAssets = 0;
        int volTransfere = 0;
        int volBeat = 0;
        Map<String, Double> sourceAtr = new LinkedHashMap<>();
        PRESET_ASSETS.forEach(p -> sourceAtr.put(p, atrPct.get(p)));
        for (String a : new java.util.TreeSet<>(concluded)) {
            if (PRESET_ASSETS.contains(a) || atrPct.get(a) == null || atrPct.get(a).isNaN()) {
                continue;
            }
            String chosen = chooseByVolatility(atrPct.get(a), sourceAtr);
            choice.put(a, chosen);
            PairResult cp = oos.stream().filter(p -> p.target().equals(a) && p.preset().equals(chosen)).findFirst().orElse(null);
            if (cp == null || !isConcluded(cp)) {
                continue;
            }
            volAssets++;
            volTransfere += TRANSFERE.equals(cp.status()) ? 1 : 0;
            List<Double> others = oos.stream().filter(p -> p.target().equals(a) && !p.preset().equals(chosen)).map(PairResult::medianDelta).toList();
            if (others.size() == 2 && others.stream().allMatch(x -> x != null) && cp.medianDelta() > (others.get(0) + others.get(1)) / 2.0 + EPS) {
                volBeat++;
            }
        }
        double tShare = volAssets == 0 ? 0.0 : (double) volTransfere / volAssets;
        double bShare = volAssets == 0 ? 0.0 : (double) volBeat / volAssets;
        boolean volPass = volAssets > 0 && tShare >= cfg.genericShare() - EPS && bShare >= cfg.volWinShare() - EPS;
        String v;
        String text;
        if (concluded.size() < cfg.minConcludedAssets()) {
            v = "NON_CONCLUANT";
            text = String.format(Locale.ROOT, "%d actif(s) hors échantillon avec ≥ %d blocs (minimum fixé : %d) : trop peu pour conclure.", concluded.size(),
                    cfg.minBlocks(), cfg.minConcludedAssets());
        } else if (!generic.isEmpty()) {
            v = "PRESET_GENERIQUE";
            text = generic.size() == 1 ? "Un seul preset GENERIQUE : " + generic.getFirst() + " (Δ médian poolé " + fmt(pooled.get(generic.getFirst())) + " pts)."
                    : "Plusieurs presets GENERIQUE : " + String.join(", ", generic) + " ; aucun n'est choisi a posteriori (meilleur Δ médian poolé, rapporté seulement : "
                    + bestPooled + ").";
        } else if (volPass) {
            v = "CHOIX_PAR_VOLATILITE";
            text = String.format(Locale.ROOT, "Le preset choisi par volatilité TRANSFERE sur %.0f %% des %d actifs et bat la moyenne des deux autres sur %.0f %%.",
                    100 * tShare, volAssets, 100 * bShare);
        } else {
            v = "RIEN_NE_TRANSFERE";
            text = "Aucun preset GENERIQUE et la règle de choix par volatilité ne tient pas : le nouvel actif ne reçoit que DCA + calage d'exposition.";
        }
        return new Verdict(v, generic, bestPooled, pooled, share, choice, volAssets, tShare, bShare, volPass, concluded.size(), text);
    }

    static boolean isConcluded(PairResult p) {
        return TRANSFERE.equals(p.status()) || NON_TRANSFERE.equals(p.status());
    }

    static String fmt(Double d) {
        return d == null || d.isNaN() ? "n/d" : String.format(Locale.ROOT, "%+.2f", d);
    }
}
