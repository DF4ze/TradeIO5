package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ReplayDays;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.AComparison;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Change;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Estimate;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Forward;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.HorizonVerdict;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.InSample;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Inputs;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Latest;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.ParamStat;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.PhaseStat;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.SearchInfo;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.TPoint;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.Verdict;
import fr.ses10doigts.tradeIO5.service.optimizer.RecentTuneReport.ZoneStat;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Parity;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Étape D0 : le réglage fait sur les 24 mois récents tient-il dans les 3 et 6 mois suivants ? Pour chaque T : réglage sur {@code ≤ T}
 * ({@link RecentTune}), puis preset en place, preset réglé, DCA à exposition égale (calage de B') et perturbations aléatoires du preset en
 * place de même amplitude, joués sur [T, T + horizon] (même capital, cash, coûts, bougies que A à C4). Une configuration, un run.
 */
public final class RecentTuneStudy {

    private static final double EPS = 1e-9;

    static final String RULE = "Règle fixée avant lecture (horizon le plus long) : RÉGLAGE_RÉCENT_PERSISTANT si Δ > 0 contre le preset en place sur ≥ 60 % des T, "
            + "médiane du Δ > 0 ET le preset réglé bat la médiane des 20 perturbations aléatoires sur ≥ 60 % des T ; idem à l'horizon court, "
            + "rapporté à part. Sinon NON_PERSISTANT, avec la cause : gain (part ou médiane du Δ), bruit (pas mieux que l'aléatoire), phase "
            + "(la règle ne tient que dans un type de marché).";

    private RecentTuneStudy() {
    }

    // ------------------------------------------------------------------------------------------------------------------- coût

    /** Durée mesurée (ms) d'un candidat de recherche (cycle + gel sur une vue de réglage), en série, après préchauffage. */
    public static double measureEvalMs(ScorerInputs in, LocalDate t, RecentTuneConfig cfg) {
        SensitivityEvaluator ev = new SensitivityEvaluator(RecentTune.truncated(in, t), cfg.tuneMonths(), RecentTune.tuneFrom(t, cfg));
        RainbowAtrParamSet[] sets = ev.template().sets();
        for (int i = 0; i < 3; i++) {
            ev.cycle(sets, false);
            ev.frozen(sets, RainbowAtrPresets.BULL);
        }
        int n = 12;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            ev.cycle(sets, false);
            ev.frozen(sets, i % 2 == 0 ? RainbowAtrPresets.BULL : RainbowAtrPresets.BEAR);
        }
        return (System.nanoTime() - t0) / 1e6 / n;
    }

    /** Rejeux au pire × durée : recherches en parallèle sur le pool, tirages de perturbation et tests en série. */
    public static Estimate estimate(RecentTuneConfig cfg, int dates, double measuredMs, int pool) {
        int searches = dates * cfg.rounds() * 2;
        long searchReplays = (long) searches * cfg.trialsPerSearch();
        double forwardPerDate = 0.0;
        for (int h : cfg.horizons()) {
            forwardPerDate += (2.0 + cfg.calibrationIterations() + cfg.perturbations()) * h / cfg.tuneMonths();
        }
        long serial = Math.round((double) dates * (cfg.maxPerturbationDraws() + forwardPerDate));
        double ms = measuredMs > 0 ? measuredMs : cfg.replayMs();
        double minutes = (searchReplays * ms / Math.max(pool, 1) + serial * ms) / 60_000.0;
        double minutesConfigured = (searchReplays * cfg.replayMs() / Math.max(pool, 1) + serial * cfg.replayMs()) / 60_000.0;
        return new Estimate(dates, searches, searchReplays, serial, cfg.replayMs(), measuredMs, pool, minutes, minutesConfigured);
    }

    // ------------------------------------------------------------------------------------------------------------------- étude

    /**
     * @param stepA directions de l'étape A (clé {@code ROLE.réglage} → {@code ↑}, {@code ↓}, {@code =}, {@code ≠}) ; vide = pas de comparaison
     */
    public static RecentTuneReport run(ScorerInputs in, RecentTuneConfig cfg, ExecutorService pool, Map<String, String> stepA, double measuredMs,
                                       int poolSize) {
        long t0 = System.nanoTime();
        List<LocalDate> dates = cfg.dates();
        String asset = in.assets().getFirst().slot().assetSymbol();
        List<TPoint> points = new ArrayList<>();
        RecentTune.Tuned lastTuned = null;
        long searchTrials = 0;
        long replays = 0;
        int searches = 0;
        int draws = 0;
        for (LocalDate t : dates) {
            RecentTune.Tuned tuned = RecentTune.tune(in, t, cfg, pool);
            if (tuned == null) {
                points.add(new TPoint(t, RecentTune.tuneFrom(t, cfg), false, "écarté : préchauffage insuffisant", List.of(), List.of(), null,
                        List.of(), false, false, false, false, 0, 0));
                continue;
            }
            RecentTune.Perturbations pert = RecentTune.perturbations(tuned, cfg);
            draws += pert.draws();
            List<Forward> forward = new ArrayList<>();
            double cash0 = in.cash0();
            for (int h : cfg.horizons()) {
                Forward fw = forward(in, tuned, pert, h, cfg, cash0);
                if (fw != null) {
                    forward.add(fw);
                }
            }
            InSample inSample = new InSample(tuned.presetGel()[RainbowAtrPresets.BULL].totalGain(), tuned.tunedGel()[RainbowAtrPresets.BULL].totalGain(),
                    tuned.presetGel()[RainbowAtrPresets.BEAR].totalGain(), tuned.tunedGel()[RainbowAtrPresets.BEAR].totalGain(),
                    tuned.presetCycle().pnlPct(), tuned.tunedCycle().pnlPct(), tuned.presetCycle().avgExposurePct(),
                    tuned.tunedCycle().avgExposurePct(), tuned.presetCycle().maxDrawdownPct(), tuned.tunedCycle().maxDrawdownPct());
            RainbowAtrParamSet[] ts = tuned.tuned();
            points.add(new TPoint(t, tuned.from(), true, "réglé", tuned.changes(), tuned.searches(), inSample, forward,
                    RecentTuneSpace.zoneX1(ts[RainbowAtrPresets.BULL]), RecentTuneSpace.zoneX05(ts[RainbowAtrPresets.BULL]),
                    RecentTuneSpace.zoneX1(ts[RainbowAtrPresets.BEAR]), RecentTuneSpace.zoneX05(ts[RainbowAtrPresets.BEAR]),
                    tuned.replays(), tuned.guardRejections()));
            searches += tuned.searches().size();
            searchTrials += tuned.searches().stream().mapToLong(SearchInfo::trials).sum();
            replays += tuned.searches().stream().mapToLong(SearchInfo::replays).sum();
            lastTuned = tuned;
        }
        Verdict verdict = verdict(points, cfg);
        List<ParamStat> frequency = frequency(points);
        ZoneStat zones = zones(points, cfg);
        List<AComparison> comparison = compare(stepA, frequency, points);
        Latest latest = lastTuned == null ? null : latest(in, lastTuned, asset);
        SensitivityEvaluator head = lastTuned == null ? null : lastTuned.ev();
        Inputs inputs = new Inputs(asset, in.assets().getFirst().source(), in.cash0(), cfg.firstT(), dates.getLast(), cfg.stepMonths(),
                cfg.tuneMonths(), cfg.horizons(), cfg.rounds(), cfg.trialsPerSearch(), cfg.exposureGuardFactor(), cfg.perturbations(), cfg.seed(),
                CandleFingerprint.of(asset, in.assets().getFirst().template().candles(), cfg.lastCandle()), SensitivityParam.ALL.size(),
                cfg.minShare());
        Multiplicity multiplicity = new Multiplicity(dates.size(), searches, searchTrials, replays, draws, cfg.horizons().size(), 1);
        return new RecentTuneReport(inputs, estimate(cfg, dates.size(), measuredMs, poolSize), points, verdict, frequency, zones, comparison,
                latest, multiplicity, notes(cfg), new RecentTuneReport.Timings((System.nanoTime() - t0) / 1_000_000));
    }

    /** Test en avançant à un horizon ; {@code null} si la plage n'est pas rejouable. */
    static Forward forward(ScorerInputs in, RecentTune.Tuned tuned, RecentTune.Perturbations pert, int horizon, RecentTuneConfig cfg, double cash0) {
        LocalDate t = tuned.t();
        SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(t.plusMonths(horizon)), horizon, t);
        Parity preset = ev.parity(tuned.preset(), false, 0, null);
        Parity tunedParity = ev.parity(tuned.tuned(), false, 0, null);
        if (preset == null || tunedParity == null) {
            return null;
        }
        ExposureParity.Calibration cal = ExposureParity.calibrate(ev, tuned.tuned(), false, tunedParity, cash0, cfg.calibrationTolerancePts(),
                cfg.calibrationIterations());
        List<Double> random = new ArrayList<>();
        for (RainbowAtrParamSet[] sets : pert.sets()) {
            Reading r = ev.cycle(sets, false);
            if (r != null) {
                random.add(r.pnlPct());
            }
        }
        double median = random.isEmpty() ? Double.NaN : median(random);
        double min = random.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
        double max = random.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        RainbowAtrDataset ds = ev.template().ds();
        int i0 = ReplayDays.firstIndexOnOrAfter(ds, t);
        int i1 = ReplayDays.lastIndexOnOrBefore(ds, t.plusMonths(horizon));
        double change = i0 < 0 || i0 >= ds.size() || i1 < i0 ? 0.0 : 100.0 * (ds.close(i1) / ds.close(i0) - 1.0);
        double delta = tunedParity.preset().pnlPct() - preset.preset().pnlPct();
        double presetPnlUsd = preset.preset().pnlPct() * cash0 / 100.0;
        Double deltaPct = presetPnlUsd > EPS ? 100.0 * delta / preset.preset().pnlPct() : null;
        double vsDca = tunedParity.preset().pnlPct() - cal.parity().dca().pnlPct();
        return new Forward(horizon, change > 0 ? "haussière" : "baissière", change, preset.preset(), tunedParity.preset(), cal.parity().dca(), cal.base(),
                cal.calib().reached(), delta, deltaPct, vsDca, median, min, max, random.size(), pert.draws(), delta > EPS,
                !random.isEmpty() && tunedParity.preset().pnlPct() > median + EPS, vsDca > EPS);
    }

    static double median(List<Double> values) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2.0;
    }

    // ------------------------------------------------------------------------------------------------------------------- verdict

    static Verdict verdict(List<TPoint> points, RecentTuneConfig cfg) {
        HorizonVerdict main = null;
        List<HorizonVerdict> others = new ArrayList<>();
        for (int h : cfg.horizons()) {
            HorizonVerdict hv = horizonVerdict(points, h, cfg);
            if (h == cfg.verdictHorizon()) {
                main = hv;
            } else {
                others.add(hv);
            }
        }
        return new Verdict(main.pass() ? "RÉGLAGE_RÉCENT_PERSISTANT" : "NON_PERSISTANT", main, others, RULE);
    }

    static HorizonVerdict horizonVerdict(List<TPoint> points, int horizon, RecentTuneConfig cfg) {
        List<Forward> fws = new ArrayList<>();
        for (TPoint p : points) {
            p.forward().stream().filter(f -> f.horizonMonths() == horizon).findFirst().ifPresent(fws::add);
        }
        int n = fws.size();
        List<PhaseStat> phases = new ArrayList<>();
        for (String phase : List.of("haussière", "baissière")) {
            List<Forward> sub = fws.stream().filter(f -> phase.equals(f.phase())).toList();
            phases.add(new PhaseStat(phase, sub.size(), (int) sub.stream().filter(Forward::beatsPreset).count(),
                    sub.isEmpty() ? Double.NaN : median(sub.stream().map(Forward::deltaPts).toList()),
                    (int) sub.stream().filter(Forward::beatsRandom).count()));
        }
        if (n == 0) {
            return new HorizonVerdict(horizon, 0, 0, 0, Double.NaN, null, 0, 0, 0, false, List.of("aucun T évaluable"), 0, phases);
        }
        int pos = (int) fws.stream().filter(Forward::beatsPreset).count();
        int rnd = (int) fws.stream().filter(Forward::beatsRandom).count();
        int dca = (int) fws.stream().filter(Forward::beatsDcaEqual).count();
        double median = median(fws.stream().map(Forward::deltaPts).toList());
        List<Double> pctList = fws.stream().map(Forward::deltaPctOfPreset).filter(x -> x != null).toList();
        Double medianPct = pctList.isEmpty() ? null : median(pctList);
        double share = (double) pos / n;
        double shareRnd = (double) rnd / n;
        boolean gainOk = share >= cfg.minShare() && median > 0;
        boolean noiseOk = shareRnd >= cfg.minShare();
        boolean pass = gainOk && noiseOk;
        List<String> causes = new ArrayList<>();
        if (!pass) {
            if (!gainOk) {
                causes.add("gain");
            }
            if (!noiseOk) {
                causes.add("bruit (pas mieux que l'aléatoire)");
            }
            long phasePass = phases.stream().filter(ph -> ph.n() >= 3 && (double) ph.deltaPositive() / ph.n() >= cfg.minShare()
                    && ph.medianDeltaPts() > 0 && (double) ph.beatsRandom() / ph.n() >= cfg.minShare()).count();
            if (phasePass == 1) {
                causes.add("phase de marché (la règle ne tient que dans un type de marché)");
            }
        }
        double overlap = Math.max(1.0, (double) horizon / cfg.stepMonths());
        return new HorizonVerdict(horizon, n, pos, share, median, medianPct, rnd, shareRnd, dca, pass, causes, n / overlap, phases);
    }

    // ------------------------------------------------------------------------------------------------------------------- compléments

    static List<ParamStat> frequency(List<TPoint> points) {
        Map<String, List<Change>> byKey = new LinkedHashMap<>();
        int total = (int) points.stream().filter(TPoint::valid).count();
        for (TPoint p : points) {
            for (Change c : p.changes()) {
                byKey.computeIfAbsent(c.role() + "|" + c.key(), k -> new ArrayList<>()).add(c);
            }
        }
        List<ParamStat> out = new ArrayList<>();
        for (Map.Entry<String, List<Change>> e : byKey.entrySet()) {
            List<Change> cs = e.getValue();
            int up = (int) cs.stream().filter(c -> c.move() > 0).count();
            int down = (int) cs.stream().filter(c -> c.move() < 0).count();
            List<Double> moves = cs.stream().map(Change::move).filter(m -> !Double.isNaN(m)).toList();
            out.add(new ParamStat(cs.getFirst().role(), cs.getFirst().key(), cs.size(), total, up, down, moves.isEmpty() ? null : median(moves)));
        }
        out.sort((a, b) -> a.changed() != b.changed() ? Integer.compare(b.changed(), a.changed())
                : (a.role() + a.key()).compareTo(b.role() + b.key()));
        return out;
    }

    static ZoneStat zones(List<TPoint> points, RecentTuneConfig cfg) {
        List<TPoint> valid = points.stream().filter(TPoint::valid).toList();
        List<Double> active = new ArrayList<>();
        List<Double> inactive = new ArrayList<>();
        for (TPoint p : valid) {
            Forward fw = p.forward().stream().filter(f -> f.horizonMonths() == cfg.verdictHorizon()).findFirst().orElse(null);
            if (fw == null) {
                continue;
            }
            (p.x1Bull() || p.x05Bull() || p.x1Bear() || p.x05Bear() ? active : inactive).add(fw.deltaPts());
        }
        return new ZoneStat(valid.size(), (int) valid.stream().filter(TPoint::x1Bull).count(), (int) valid.stream().filter(TPoint::x05Bull).count(),
                (int) valid.stream().filter(TPoint::x1Bear).count(), (int) valid.stream().filter(TPoint::x05Bear).count(),
                active.isEmpty() ? null : active.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                inactive.isEmpty() ? null : inactive.stream().mapToDouble(Double::doubleValue).average().orElse(0), active.size(), inactive.size());
    }

    static List<AComparison> compare(Map<String, String> stepA, List<ParamStat> frequency, List<TPoint> points) {
        List<AComparison> out = new ArrayList<>();
        TPoint last = points.stream().filter(TPoint::valid).reduce((a, b) -> b).orElse(null);
        int total = (int) points.stream().filter(TPoint::valid).count();
        for (Map.Entry<String, String> e : stepA.entrySet()) {
            String[] parts = e.getKey().split("\\.", 2);
            ParamStat s = frequency.stream().filter(x -> x.role().equals(parts[0]) && x.key().equals(parts[1])).findFirst().orElse(null);
            int up = s == null ? 0 : s.up();
            int down = s == null ? 0 : s.down();
            int changed = s == null ? 0 : s.changed();
            String latest = last == null ? "—" : last.changes().stream().filter(c -> c.role().equals(parts[0]) && c.key().equals(parts[1]))
                    .map(c -> Double.isNaN(c.move()) ? c.before() + " → " + c.after() : (c.move() > 0 ? "↑ " : "↓ ") + c.before() + " → " + c.after())
                    .findFirst().orElse("=");
            boolean categorical = s != null && up == 0 && down == 0 && changed > 0;
            String majority = up > down ? "↑" : down > up ? "↓" : changed * 2 >= total && changed > 0 ? "≠" : "=";
            boolean same = categorical || "≠".equals(e.getValue()) ? ("≠".equals(e.getValue()) == (changed * 2 >= total && changed > 0))
                    : e.getValue().equals(majority);
            out.add(new AComparison(parts[0], parts[1], e.getValue(), up, down, changed, latest, same));
        }
        return out;
    }

    static Latest latest(ScorerInputs in, RecentTune.Tuned tuned, String asset) {
        String source = in.assets().getFirst().source();
        int window = tuned.ev().template().windowMonths();
        return new Latest(tuned.t(), tuned.changes(),
                BlockTuning.export(tuned.preset()[RainbowAtrPresets.BULL], tuned.tuned()[RainbowAtrPresets.BULL], asset, source, window, "Bull", "réglage récent " + tuned.t()),
                BlockTuning.export(tuned.preset()[RainbowAtrPresets.BEAR], tuned.tuned()[RainbowAtrPresets.BEAR], asset, source, window, "Bear", "réglage récent " + tuned.t()));
    }

    static List<String> notes(RecentTuneConfig cfg) {
        return List.of(
                "Critère de recherche = PnL total du régime en lecture « gel » (USDC) : ventes encaissées + position (latent), moins l'investi, comme à l'étape A. Les écarts sont rapportés en points de capital ET en % du PnL du preset en place (quand il est > 0).",
                "Recherche = SearchEngine (Sobol + halving + plateau) sur 31 dimensions par jeu ; SobolSequence ne fournit que 12 dimensions quasi-aléatoires, les suivantes sont tirées pseudo-aléatoirement à graine fixe (limite du moteur existant).",
                "Aucun gel de paramètre par sensibilité (seuil 0) : un réglage muet seul (zones X1 / X0,5 à bornes 0) doit pouvoir s'activer avec d'autres ; la phase de sensibilité du moteur est réduite à 2 points par réglage (elle ne sert plus qu'à l'ordre d'évaluation).",
                "Bornes ATR : chaque tirage est trié (Down1 ≤ Down2 ; Up1 ≤ Up2 ≤ Up3) ; athBuyMin ≤ athBuyMax idem ; Down1 / Up1 / Up2 peuvent donc être > 0 (zones X1 / X0,5).",
                "Garde-fou unique : exposition moyenne du cycle réel (deux jeux actifs) sur la vue de réglage ≤ " + cfg.exposureGuardFactor()
                        + " × celle du preset en place ; le garde-fou est un veto (score −1000), jamais une pénalité. Aucun plafond de drawdown, aucune fenêtre multiple.",
                "Un jeu choisi par le moteur n'est retenu que s'il bat STRICTEMENT le jeu de départ dans l'échantillon (critère de recherche) ; sinon le jeu reste inchangé (Δ = 0 au test).",
                "Test en avançant : joué sur [T, T + horizon] (le jour T appartient à la vue de réglage ET au test ; les deux ne partagent pas de décision future). La Trend du test est calculée sur toutes les bougies disponibles comme aux étapes A à C4 ; le réglage, lui, ne voit que les bougies ≤ T (dataset reconstruit).",
                "DCA à exposition égale : calé (dichotomie de B') sur l'exposition moyenne du preset RÉGLÉ sur la même période de test.",
                "Perturbations aléatoires : mêmes réglages déplacés que le réglage, même amplitude normalisée dans la plage, sens aléatoire (réfléchi au bord), option tirée pour un mode / interrupteur ; mêmes tris de bornes ; garde-fou d'exposition appliqué sur la vue de réglage (tirages refusés redessinés, au plus " + cfg.maxPerturbationDraws() + "). Sans réglage retenu, la perturbation est le preset en place (Δ = 0, ne bat pas l'aléatoire).",
                "Périodes de test chevauchées : à 6 mois avec un pas de " + cfg.stepMonths() + " mois, chaque jour est couvert par 3 périodes (événements effectifs ≈ nombre de T / 3) ; à 3 mois ≈ nombre de T / 1,5. Les pourcentages de T ne sont pas des fréquences indépendantes.",
                "BTC seul (ETH seulement après validation) ; un seul run, une seule configuration, aucune variante, aucun preset modifié.");
    }
}
