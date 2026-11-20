package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneBlocks.Block;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Aggregate;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.BlockInfo;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Challenger;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Change;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Compare;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Estimate;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Inversion;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.TuneReport.Rotation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Étape B : optimisation « façon main » validée par blocs. Pour chaque rotation, règle Bull puis Bear sur les blocs restants
 * ({@link BlockTuner}) et mesure UNE fois sur le bloc écarté ; puis règle un challenger unique sur tous les blocs valides (non testé).
 * Une seule configuration, un seul run : aucune relance de variantes. Hors ligne, rien n'est appliqué ni persisté.
 */
public final class BlockTuning {

    private static final double EPS = 1e-9;

    private BlockTuning() {
    }

    /** Blocs (valides ou écartés faute de préchauffage) et estimation de coût, avant tout réglage. */
    public record Plan(List<BlockInfo> blocks, List<SensitivityEvaluator> evaluators, Reading[] presetReadings, Estimate estimate) {
        public List<Integer> validIndexes() {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i).valid()) {
                    out.add(i);
                }
            }
            return out;
        }
    }

    public static Plan plan(ScorerInputs in, TuneConfig cfg) {
        List<Block> specs = TuneBlocks.build(cfg.lastEnd(), cfg.blockMonths(), cfg.blockCount());
        List<SensitivityEvaluator> evs = new ArrayList<>();
        List<BlockInfo> infos = new ArrayList<>();
        Reading[] readings = new Reading[specs.size()];
        RainbowAtrParamSet[] preset = null;
        for (Block b : specs) {
            SensitivityEvaluator ev = new SensitivityEvaluator(in.withEndDate(b.to()), cfg.blockMonths(), b.from());
            evs.add(ev);
            if (preset == null) {
                preset = ev.template().sets();
            }
            Reading r = ev.cycle(preset, false);
            readings[b.index()] = r;
            infos.add(new BlockInfo(b.index(), b.from(), b.to(), r != null,
                    r == null ? "écarté : pas assez de bougies de préchauffage avant " + b.from() : "rejouable"));
        }
        int valid = (int) infos.stream().filter(BlockInfo::valid).count();
        return new Plan(infos, evs, readings, estimate(cfg, valid));
    }

    /** Rejeux au pire (toutes les passes, tous les élargissements) × durée supposée d'un rejeu ; borne haute, pas une prévision. */
    public static Estimate estimate(TuneConfig cfg, int validBlocks) {
        long perPass = 0;
        for (String key : concat(BlockTuner.BULL_KEYS, BlockTuner.BEAR_KEYS)) {
            SensitivityParam p = BlockTuner.params(List.of(key)).getFirst();
            switch (p.kind()) {
                case BOOLEAN, ENUM -> perPass += 2;
                default -> {
                    int widen = p.sweepCapped() ? cfg.maxWidenSteps() : 0;
                    perPass += (long) SensitivityParam.GRID * (1 + widen) + cfg.refinePoints() + 1;
                }
            }
        }
        long values = perPass * cfg.maxPasses();
        long rotations = (long) validBlocks * values * Math.max(validBlocks - 1, 1);
        long challenger = values * validBlocks;
        long measures = validBlocks * 2L + 2 + validBlocks * 2L + validBlocks;
        long total = rotations + challenger + measures;
        return new Estimate(total, cfg.replayMs(), total * cfg.replayMs() / 60_000.0);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** @param pool pool dédié aux rejeux ({@code null} = série) */
    public static TuneReport run(ScorerInputs in, TuneConfig cfg, ExecutorService pool) {
        return run(in, cfg, pool, BlockTuner.BULL_KEYS, BlockTuner.BEAR_KEYS);
    }

    /** Idem sur un sous-ensemble de réglages (tests). */
    static TuneReport run(ScorerInputs in, TuneConfig cfg, ExecutorService pool, List<String> bullKeys, List<String> bearKeys) {
        long t0 = System.nanoTime();
        Plan plan = plan(in, cfg);
        List<Integer> valid = plan.validIndexes();
        if (valid.size() < 3) {
            throw new IllegalStateException("Moins de 3 blocs rejouables : réglage impossible (" + valid.size() + ")");
        }
        List<SensitivityEvaluator> evs = plan.evaluators();
        SensitivityEvaluator head = evs.get(valid.getFirst());
        RainbowAtrParamSet[] preset = head.template().sets();
        double cash0 = in.cash0();

        List<Rotation> rotations = new ArrayList<>();
        int passesRun = 0;
        int valuesTested = 0;
        int ignored = 0;
        int guards = 0;
        for (int test : valid) {
            List<SensitivityEvaluator> tuneEvs = new ArrayList<>();
            List<Integer> tuneIdx = new ArrayList<>();
            for (int i : valid) {
                if (i != test) {
                    tuneEvs.add(evs.get(i));
                    tuneIdx.add(i);
                }
            }
            long before = tuneEvs.stream().mapToLong(SensitivityEvaluator::replays).sum();
            BlockTuner.Result res = new BlockTuner(cfg, pool).tune(tuneEvs, preset, bullKeys, bearKeys);
            long replays = tuneEvs.stream().mapToLong(SensitivityEvaluator::replays).sum() - before;
            Reading tuned = evs.get(test).cycle(res.asArray(), false);
            if (tuned == null) {
                throw new IllegalStateException("Bloc de test non rejouable avec le preset réglé : " + test);
            }
            Compare c = compare(test, plan.presetReadings()[test], tuned, cash0, cfg);
            boolean beatsPreset = c.deltaVsPreset() > EPS;
            boolean beatsDca = c.deltaVsDca() > EPS;
            rotations.add(new Rotation(test, tuneIdx, changes(preset, res.asArray(), bullKeys, bearKeys), res.decisions(), res.passes(), c, beatsPreset, beatsDca,
                    beatsPreset && beatsDca && c.exposureOk(), replays, res.valuesTested()));
            passesRun += res.passes();
            valuesTested += res.valuesTested();
            ignored += res.ignoredValues();
            guards += res.guardRejections();
        }

        List<SensitivityEvaluator> all = new ArrayList<>();
        valid.forEach(i -> all.add(evs.get(i)));
        BlockTuner.Result ch = new BlockTuner(cfg, pool).tune(all, preset, bullKeys, bearKeys);
        passesRun += ch.passes();
        valuesTested += ch.valuesTested();
        ignored += ch.ignoredValues();
        guards += ch.guardRejections();
        List<Compare> inSample = new ArrayList<>();
        for (int i : valid) {
            Reading r = evs.get(i).cycle(ch.asArray(), false);
            inSample.add(compare(i, plan.presetReadings()[i], r, cash0, cfg));
        }
        Reading presetFull = head.cycle(preset, true);
        Reading challengerFull = head.cycle(ch.asArray(), true);
        String source = in.assets().getFirst().source();
        Challenger challenger = new Challenger(changes(preset, ch.asArray(), bullKeys, bearKeys), ch.decisions(), ch.passes(), inSample, presetFull, challengerFull,
                presetFull.pnlPct(), challengerFull.pnlPct(), 100.0 * presetFull.fixedPnl() / cash0,
                export(preset[RainbowAtrPresets.BULL], ch.asArray()[RainbowAtrPresets.BULL], head.asset(), source, head.template().windowMonths(), "Bull"),
                export(preset[RainbowAtrPresets.BEAR], ch.asArray()[RainbowAtrPresets.BEAR], head.asset(), source, head.template().windowMonths(), "Bear"));

        long replaysTotal = evs.stream().mapToLong(SensitivityEvaluator::replays).sum();
        Aggregate aggregate = aggregate(rotations, cfg, bullKeys, bearKeys);
        Multiplicity multiplicity = new Multiplicity(bullKeys.size() + bearKeys.size(), rotations.size(), passesRun,
                valuesTested, replaysTotal, ignored, guards);
        TuneReport.Inputs inputs = new TuneReport.Inputs(head.asset(), source, cash0, cfg.lastEnd(), cfg.blockMonths(),
                CandleFingerprint.of(head.asset(), head.template().candles(), cfg.lastEnd()), cfg.maxPasses(), cfg.minGainPoints(), cfg.plateauRatio(),
                cfg.exposureGuardFactor(), cfg.refinePoints(), cfg.widenCeiling(), bullKeys, bearKeys, BlockTuner.OUT_OF_SCOPE,
                BlockTuner.SILENT, plan.estimate());
        return new TuneReport(inputs, plan.blocks(), rotations, aggregate, challenger, multiplicity, notes(cfg),
                new TuneReport.Timings((System.nanoTime() - t0) / 1_000_000));
    }

    private static Compare compare(int block, Reading preset, Reading tuned, double cash0, TuneConfig cfg) {
        double dca = 100.0 * tuned.fixedPnl() / cash0;
        return new Compare(block, preset, tuned, preset.pnlPct(), tuned.pnlPct(), dca, tuned.pnlPct() - preset.pnlPct(), tuned.pnlPct() - dca,
                !BlockTuner.rejects(tuned.avgExposurePct(), preset.avgExposurePct(), cfg.exposureGuardFactor()));
    }

    /** Réglages modifiés (avant → après), Bull puis Bear. */
    static List<Change> changes(RainbowAtrParamSet[] preset, RainbowAtrParamSet[] tuned, List<String> bullKeys, List<String> bearKeys) {
        List<Change> out = new ArrayList<>();
        for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
            String role = idx == RainbowAtrPresets.BULL ? "BULL" : "BEAR";
            for (String key : idx == RainbowAtrPresets.BULL ? bullKeys : bearKeys) {
                SensitivityParam p = BlockTuner.params(List.of(key)).getFirst();
                String a = SensitivityParam.label(p.of(preset[idx]));
                String b = SensitivityParam.label(p.of(tuned[idx]));
                if (!a.equals(b)) {
                    out.add(new Change(role, key, a, b));
                }
            }
        }
        return out;
    }

    /** Corps au format de l'Expert ({@code ExpertDtos.RainbowPresetRequest}) : se charge tel quel dans le simulateur. */
    static Map<String, Object> export(RainbowAtrParamSet before, RainbowAtrParamSet after, String asset, String source, int window,
                                      String role) {
        return export(before, after, asset, source, window, role, "challenger étape B");
    }

    /** Idem avec le libellé du preset exporté (étape D0 : « réglage récent »). */
    static Map<String, Object> export(RainbowAtrParamSet before, RainbowAtrParamSet after, String asset, String source, int window,
                                      String role, String label) {
        String name = (asset + " " + role + " " + label);
        ExpertDtos.RainbowPresetRequest req = new ExpertDtos.RainbowPresetRequest(name.substring(0, Math.min(name.length(), 64)), source, window,
                after.tuning(), after.globals(), null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", req.name());
        out.put("referenceProvider", req.referenceProvider());
        out.put("analysisWindowMonths", req.analysisWindowMonths());
        out.put("tuning", req.tuning());
        out.put("globals", req.globals());
        out.put("revision", req.revision());
        return out;
    }

    static double median(List<Double> v) {
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static Aggregate aggregate(List<Rotation> rotations, TuneConfig cfg, List<String> bullKeys, List<String> bearKeys) {
        int n = rotations.size();
        int bp = (int) rotations.stream().filter(Rotation::beatsPreset).count();
        int bd = (int) rotations.stream().filter(Rotation::beatsDca).count();
        int both = (int) rotations.stream().filter(Rotation::adoptable).count();
        List<Double> dp = rotations.stream().map(r -> r.test().deltaVsPreset()).toList();
        List<Double> dd = rotations.stream().map(r -> r.test().deltaVsDca()).toList();
        double mp = median(dp);
        double md = median(dd);
        boolean expo = rotations.stream().allMatch(r -> r.test().exposureOk());
        List<String> reasons = new ArrayList<>();
        String verdict;
        if (n < cfg.blockCount()) {
            verdict = "NON_EVALUABLE";
            reasons.add("Seulement " + n + " rotation(s) sur " + cfg.blockCount() + " : blocs écartés faute de préchauffage, la porte « ≥ 3 rotations sur 4 » ne s'applique pas.");
        } else {
            boolean ok = both >= 3 && mp > 0 && md > 0 && expo;
            verdict = ok ? "ADOPTABLE" : "NUL";
            reasons.add((both >= 3 ? "OK" : "KO") + " : rotations gagnantes (bat preset ET DCA fixe, exposition dans la borne) " + both + "/" + n + " (≥ 3 requis).");
            reasons.add((mp > 0 ? "OK" : "KO") + " : médiane de Δ vs preset en place " + String.format(java.util.Locale.ROOT, "%+.2f", mp) + " pt (> 0 requis).");
            reasons.add((md > 0 ? "OK" : "KO") + " : médiane de Δ vs DCA fixe " + String.format(java.util.Locale.ROOT, "%+.2f", md) + " pt (> 0 requis).");
            reasons.add((expo ? "OK" : "KO") + " : exposition moyenne ≤ " + cfg.exposureGuardFactor() + " × celle du preset en place sur tous les blocs de test.");
        }
        return new Aggregate(n, bp, bd, both, mp, md, dp.stream().mapToDouble(Double::doubleValue).average().orElse(0),
                dd.stream().mapToDouble(Double::doubleValue).average().orElse(0), expo, verdict, reasons, inversions(rotations, bullKeys, bearKeys));
    }

    /** Réglages dont le sens s'inverse d'une rotation à l'autre, ou qui ne sont retenus que dans certaines. */
    static List<Inversion> inversions(List<Rotation> rotations, List<String> bullKeys, List<String> bearKeys) {
        List<Inversion> out = new ArrayList<>();
        for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
            String role = idx == RainbowAtrPresets.BULL ? "BULL" : "BEAR";
            for (String key : idx == RainbowAtrPresets.BULL ? bullKeys : bearKeys) {
                List<String> per = new ArrayList<>();
                int up = 0;
                int down = 0;
                int kept = 0;
                for (Rotation r : rotations) {
                    Change c = r.changes().stream().filter(x -> x.role().equals(role) && x.key().equals(key)).findFirst().orElse(null);
                    if (c == null) {
                        per.add("bloc " + r.testBlock() + " : inchangé");
                        continue;
                    }
                    kept++;
                    per.add("bloc " + r.testBlock() + " : " + c.before() + " → " + c.after());
                    try {
                        double d = Double.parseDouble(c.after()) - Double.parseDouble(c.before());
                        if (d > 0) {
                            up++;
                        } else if (d < 0) {
                            down++;
                        }
                    } catch (NumberFormatException e) {
                        // interrupteur / mode : pas de sens numérique
                    }
                }
                if (up > 0 && down > 0) {
                    out.add(new Inversion(role, key, per, "sens opposé"));
                } else if (kept > 0 && kept < rotations.size()) {
                    out.add(new Inversion(role, key, per, "retenu dans " + kept + "/" + rotations.size() + " rotations seulement"));
                }
            }
        }
        return out;
    }

    private static List<String> notes(TuneConfig cfg) {
        return Arrays.asList(
                "Mesure = PnL réel du cycle de l'étape A (valeur finale, les deux jeux vivent, capital fixe sans apport, coûts simplifiés, modulation neutre, pas de plafond ATH), en % du capital ; DCA fixe = celui du moteur, même cash.",
                "Un bloc rejeté par EXPOSURE_GUARD ou non rejouable compte comme le réglage courant pour le score ; un réglage n'est retenu que s'il passe le garde-fou sur TOUS les blocs de réglage (plus strict que « rejeté pour ce bloc »).",
                "L'interface Expert ne borne pas le haut des facteurs ATH ni des bornes ATR : le balayage est élargi par doublements (jusqu'à " + cfg.widenCeiling()
                        + " au plus, " + cfg.maxWidenSteps() + " doublements) ; un bord qui reste un plafond est signalé, pas conclu.",
                "Plateau = plage contiguë autour du meilleur dont le score est ≥ " + cfg.plateauRatio() + " du meilleur ; valeur retenue = sa moyenne, re-mesurée avant d'être retenue.",
                "Les blocs se touchent sans se chevaucher (début = lendemain de la fin du bloc précédent) ; chaque bloc démarre à froid (Trend / Rainbow préchauffés sur l'historique antérieur, position initiale nulle).",
                "Le challenger est réglé sur tous les blocs valides, donc n'a aucun bloc hors échantillon : seule la série de rotations peut l'accréditer.",
                "L'historique complet rappelé en fin de rapport contient les blocs de réglage : lecture seule, jamais un critère.");
    }
}
