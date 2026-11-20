package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Descente de coordonnée « façon main » sur des BLOCS DE RÉGLAGE (étape B). Un paramètre à la fois : 9 valeurs sur sa plage, élargissement
 * si le meilleur est contre un plafond de balayage que nous avons choisi, affinage fin autour du meilleur, valeur retenue = plateau (jamais
 * un pic isolé). Un réglage n'est conservé que si le gain moyen sur les blocs de réglage dépasse le seuil de configuration ET progresse sur
 * la majorité de ces blocs ET si le garde-fou d'exposition tient sur chacun. Bull puis Bear en alternance, {@code maxPasses} passes.
 * <p>
 * Ne voit que les évaluateurs de réglage qu'on lui donne : le bloc de test n'est jamais fourni, donc jamais rejoué ici. Déterministe : aucun
 * tirage, ordre des paramètres fixe, résultats de rejeux consommés dans l'ordre de soumission (le pool ne change pas le résultat).
 */
public final class BlockTuner {

    /** Réglages de VENTE de Bull (quand vendre), dans l'ordre de la descente. Moon côté Bull inclus. */
    public static final List<String> BULL_KEYS = List.of("sellFraction", "athSellMin", "athSellMax", "athRefDdSellPct", "cooldownDays",
            "cooldownAfterSellOn", "allowSellDuringCooldown", "trailingStopSellPct", "atrMultUp3", "moonOn", "moonReservePct",
            "moonReserveRatchet", "moonTrailingStopPct", "moonStopSellPct");
    /** Réglages de VENTE de Bear. */
    public static final List<String> BEAR_KEYS = List.of("sellFraction", "athSellMin", "athSellMax", "athRefDdSellPct", "atrMultDown2",
            "athRefDdBuyPct", "trailingStopBuyPct");
    /** Hors B1 : taille d'achat = exposition = L2. Ignorés (et dit dans le rapport). */
    public static final List<String> OUT_OF_SCOPE = List.of("multX2", "multTriggered", "athBuyMin", "athBuyMax");
    /** Réglages muets par construction : jamais touchés. */
    public static final List<String> SILENT = List.of("atrMultDown1", "atrMultUp1", "atrMultUp2", "multX1", "multX0_5", "fixedDelayDays",
            "Moon côté Bear");

    private static final double SAME = 1e-9;

    /** Une décision de la descente (une par paramètre et par passe). */
    public record Decision(int pass, String role, String key, String before, String after, boolean kept, String reason, double meanDelta,
                           int blocksImproved, int blocks, String plateau, String edge, int valuesTested) {
    }

    public record Result(List<RainbowAtrParamSet> sets, List<Decision> decisions, int passes, int valuesTested, int ignoredValues,
                         int guardRejections) {
        public RainbowAtrParamSet[] asArray() {
            return sets.toArray(new RainbowAtrParamSet[0]);
        }
    }

    /** Résultat de mesure d'un candidat sur les blocs de réglage (un rejeu rejeté ou non rejouable compte pour neutre sur son bloc). */
    private record Eval(double score, double meanDelta, int improved, boolean guardOk, Reading[] readings) {
    }

    private record Pt(SensitivityParam.Value value, double num, Eval eval) {
    }

    private final TuneConfig cfg;
    private final ExecutorService pool;
    private final AtomicInteger valuesTested = new AtomicInteger();
    private final AtomicInteger ignored = new AtomicInteger();
    private final AtomicInteger guardRejections = new AtomicInteger();

    private List<SensitivityEvaluator> evs;
    private double[] presetExposure;
    private double[] currentPnl;
    private int windowMonths;

    /** @param pool {@code null} = rejeux en série dans le thread appelant */
    public BlockTuner(TuneConfig cfg, ExecutorService pool) {
        this.cfg = cfg;
        this.pool = pool;
    }

    public static List<SensitivityParam> params(List<String> keys) {
        List<SensitivityParam> out = new ArrayList<>();
        for (String key : keys) {
            out.add(SensitivityParam.ALL.stream().filter(p -> p.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Réglage inconnu : " + key)));
        }
        return out;
    }

    /**
     * @param tuneBlocks évaluateurs des SEULS blocs de réglage
     * @param preset     jeux {@code [BEAR, BULL]} du preset en place
     * @param bullKeys   réglages de Bull à balayer (normalement {@link #BULL_KEYS}) ; liste vide ⇒ jeu inchangé
     * @param bearKeys   réglages de Bear à balayer
     */
    public Result tune(List<SensitivityEvaluator> tuneBlocks, RainbowAtrParamSet[] preset, List<String> bullKeys, List<String> bearKeys) {
        this.evs = tuneBlocks;
        this.windowMonths = tuneBlocks.getFirst().template().windowMonths();
        RainbowAtrParamSet[] current = preset.clone();
        Reading[] base = evaluate(java.util.Collections.singletonList(current)).getFirst();
        presetExposure = new double[evs.size()];
        currentPnl = new double[evs.size()];
        for (int b = 0; b < evs.size(); b++) {
            if (base[b] == null) {
                throw new IllegalStateException("Bloc de réglage non rejouable : " + b);
            }
            presetExposure[b] = base[b].avgExposurePct();
            currentPnl[b] = base[b].pnlPct();
        }
        List<Decision> decisions = new ArrayList<>();
        int passes = 0;
        for (int pass = 1; pass <= cfg.maxPasses(); pass++) {
            boolean changed = false;
            passes = pass;
            for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
                for (SensitivityParam p : params(idx == RainbowAtrPresets.BULL ? bullKeys : bearKeys)) {
                    Decision d = tuneParam(pass, idx, p, current);
                    decisions.add(d);
                    changed |= d.kept();
                }
            }
            if (!changed) {
                break;
            }
        }
        return new Result(List.of(current), decisions, passes, valuesTested.get(), ignored.get(), guardRejections.get());
    }

    // ------------------------------------------------------------------------------------------------------------------ un paramètre

    private Decision tuneParam(int pass, int idx, SensitivityParam original, RainbowAtrParamSet[] current) {
        String role = idx == RainbowAtrPresets.BULL ? "BULL" : "BEAR";
        RainbowAtrParamSet base = current[idx];
        String before = SensitivityParam.label(original.of(base));
        int testedBefore = valuesTested.get();
        boolean numeric = original.kind() == SensitivityParam.Kind.CONTINUOUS || original.kind() == SensitivityParam.Kind.INTEGER;
        SensitivityParam p = original;
        List<Pt> pts = evalValues(idx, p, p.values(base), current);
        String edge = "";
        String plateauText = "";
        Object chosen = null;
        Eval chosenEval = null;
        String reason;
        if (numeric) {
            int steps = 0;
            while (atMax(pts, p) && p.sweepCapped() && steps < cfg.maxWidenSteps() && p.max() < cfg.widenCeiling() - SAME) {
                p = p.withMax(Math.min(cfg.widenCeiling(), p.max() * 2));
                pts = evalValues(idx, p, p.values(base), current);
                steps++;
            }
            int best = bestIndex(pts);
            if (best < 0) {
                return decision(pass, role, original.key(), before, before, false, "aucune valeur rejouable", 0, 0, "", "", testedBefore);
            }
            edge = edgeOf(pts.get(best), p, steps);
            pts = refine(idx, p, pts, best, current);
            List<Double> values = pts.stream().map(Pt::num).toList();
            List<Double> scores = pts.stream().map(x -> x.eval() == null ? Double.NaN : x.eval().score()).toList();
            TunePlateau.Result plateau = TunePlateau.pick(values, scores, cfg.plateauRatio());
            double v = original.kind() == SensitivityParam.Kind.INTEGER ? Math.rint(plateau.value()) : plateau.value();
            plateauText = SensitivityParam.label(values.get(plateau.from())) + " … " + SensitivityParam.label(values.get(plateau.to())) + " ("
                    + plateau.size() + " valeur" + (plateau.size() > 1 ? "s" : "") + ", pic " + SensitivityParam.label(values.get(plateau.bestIndex()))
                    + ")";
            double inPlace = ((Number) original.of(base)).doubleValue();
            if (Math.abs(v - inPlace) < SAME) {
                return decision(pass, role, original.key(), before, before, false, "le plateau contient la valeur en place", 0, 0, plateauText, edge,
                        testedBefore);
            }
            chosen = original.kind() == SensitivityParam.Kind.INTEGER ? (Object) (int) v : (Object) v;
            RainbowAtrParamSet[] variant = current.clone();
            variant[idx] = original.apply(base, chosen);
            String violation = SensitivityMap.violation(variant[idx], windowMonths);
            if (violation != null) {
                ignored.incrementAndGet();
                return decision(pass, role, original.key(), before, SensitivityParam.label(chosen), false, "plateau invalide (" + violation + ")", 0, 0,
                        plateauText, edge, testedBefore);
            }
            valuesTested.incrementAndGet();
            chosenEval = evaluateOne(variant);
        } else {
            int best = bestIndex(pts);
            if (best < 0 || pts.get(best).value().inPlace()) {
                return decision(pass, role, original.key(), before, before, false, "la valeur en place est la meilleure", 0, 0, "", "", testedBefore);
            }
            chosen = pts.get(best).value().raw();
            chosenEval = pts.get(best).eval();
        }
        String after = SensitivityParam.label(chosen);
        int majority = evs.size() / 2 + 1;
        if (!chosenEval.guardOk()) {
            reason = "EXPOSURE_GUARD : rejeté sur au moins un bloc de réglage";
        } else if (chosenEval.meanDelta() < cfg.minGainPoints()) {
            reason = "gain moyen " + fmt(chosenEval.meanDelta()) + " pt < seuil " + fmt(cfg.minGainPoints()) + " pt";
        } else if (chosenEval.improved() < majority) {
            reason = "progresse sur " + chosenEval.improved() + "/" + evs.size() + " blocs (majorité requise)";
        } else {
            current[idx] = original.apply(base, chosen);
            for (int b = 0; b < evs.size(); b++) {
                currentPnl[b] = chosenEval.readings()[b].pnlPct();
            }
            return decision(pass, role, original.key(), before, after, true, "retenu", chosenEval.meanDelta(), chosenEval.improved(), plateauText, edge,
                    testedBefore);
        }
        return decision(pass, role, original.key(), before, after, false, reason, chosenEval.meanDelta(), chosenEval.improved(), plateauText, edge,
                testedBefore);
    }

    private Decision decision(int pass, String role, String key, String before, String after, boolean kept, String reason, double meanDelta,
                              int improved, String plateau, String edge, int testedBefore) {
        return new Decision(pass, role, key, before, after, kept, reason, meanDelta, improved, evs.size(), plateau, edge,
                valuesTested.get() - testedBefore);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%+.2f", v);
    }

    private static boolean atMax(List<Pt> pts, SensitivityParam p) {
        int best = bestIndex(pts);
        return best >= 0 && pts.get(best).num() >= p.max() - SAME;
    }

    private static int bestIndex(List<Pt> pts) {
        int best = -1;
        for (int i = 0; i < pts.size(); i++) {
            Eval e = pts.get(i).eval();
            if (e != null && (best < 0 || e.score() > pts.get(best).eval().score())) {
                best = i;
            }
        }
        return best;
    }

    private String edgeOf(Pt best, SensitivityParam p, int widenSteps) {
        if (best.num() <= p.min() + SAME) {
            return "min de plage (" + SensitivityParam.label(p.min()) + ")";
        }
        if (best.num() >= p.max() - SAME) {
            String note = p.sweepCapped() ? "plafond de balayage" + (widenSteps > 0 ? " élargi ×" + (1 << widenSteps) + " et" : "") + " non levé"
                    : "borne du domaine";
            return "max de plage (" + SensitivityParam.label(p.max()) + ", " + note + ")";
        }
        return "";
    }

    /** Affinage fin entre les voisins du meilleur ; les valeurs déjà mesurées ne sont pas rejouées. */
    private List<Pt> refine(int idx, SensitivityParam p, List<Pt> pts, int best, RainbowAtrParamSet[] current) {
        double lo = best > 0 ? pts.get(best - 1).num() : pts.get(best).num();
        double hi = best < pts.size() - 1 ? pts.get(best + 1).num() : pts.get(best).num();
        if (hi - lo < SAME) {
            return pts;
        }
        List<SensitivityParam.Value> fine = new ArrayList<>();
        double previous = Double.NaN;
        for (int i = 0; i < cfg.refinePoints(); i++) {
            double x = lo + (hi - lo) * i / (cfg.refinePoints() - 1);
            if (p.kind() == SensitivityParam.Kind.INTEGER) {
                x = Math.rint(x);
            }
            final double xv = x;
            if (!Double.isNaN(previous) && Math.abs(xv - previous) < SAME) {
                continue;
            }
            previous = xv;
            if (pts.stream().anyMatch(q -> Math.abs(q.num() - xv) < SAME)) {
                continue;
            }
            Object raw = p.kind() == SensitivityParam.Kind.INTEGER ? (Object) (int) xv : (Object) xv;
            fine.add(new SensitivityParam.Value(raw, SensitivityParam.label(raw), false));
        }
        if (fine.isEmpty()) {
            return pts;
        }
        List<Pt> merged = new ArrayList<>(pts);
        merged.addAll(evalValues(idx, p, fine, current));
        merged.sort(Comparator.comparingDouble(Pt::num));
        return merged;
    }

    // ------------------------------------------------------------------------------------------------------------------ mesure

    private List<Pt> evalValues(int idx, SensitivityParam p, List<SensitivityParam.Value> values, RainbowAtrParamSet[] current) {
        List<RainbowAtrParamSet[]> variants = new ArrayList<>();
        List<Integer> slot = new ArrayList<>();
        Pt[] out = new Pt[values.size()];
        for (int i = 0; i < values.size(); i++) {
            SensitivityParam.Value v = values.get(i);
            double num = v.raw() instanceof Number n ? n.doubleValue() : Double.NaN;
            valuesTested.incrementAndGet();
            RainbowAtrParamSet changed = p.apply(current[idx], v.raw());
            if (SensitivityMap.violation(changed, windowMonths) != null) {
                ignored.incrementAndGet();
                out[i] = new Pt(v, num, null);
                continue;
            }
            RainbowAtrParamSet[] variant = current.clone();
            variant[idx] = changed;
            variants.add(variant);
            slot.add(i);
            out[i] = new Pt(v, num, null);
        }
        List<Reading[]> readings = evaluate(variants);
        for (int k = 0; k < variants.size(); k++) {
            Pt old = out[slot.get(k)];
            out[slot.get(k)] = new Pt(old.value(), old.num(), score(readings.get(k)));
        }
        return new ArrayList<>(List.of(out));
    }

    private Eval evaluateOne(RainbowAtrParamSet[] variant) {
        return score(evaluate(java.util.Collections.singletonList(variant)).getFirst());
    }

    /** Score d'un candidat : PnL moyen en % du capital sur les blocs de réglage ; un bloc rejeté (garde-fou) ou non rejouable compte comme le réglage courant. */
    private Eval score(Reading[] r) {
        double sum = 0;
        double delta = 0;
        int improved = 0;
        boolean guardOk = true;
        for (int b = 0; b < r.length; b++) {
            boolean rejected = r[b] == null || rejects(r[b].avgExposurePct(), presetExposure[b], cfg.exposureGuardFactor());
            if (rejected) {
                guardOk = false;
                guardRejections.incrementAndGet();
                sum += currentPnl[b];
                continue;
            }
            double d = r[b].pnlPct() - currentPnl[b];
            sum += r[b].pnlPct();
            delta += d;
            if (d > SAME) {
                improved++;
            }
        }
        return new Eval(sum / r.length, delta / r.length, improved, guardOk, r);
    }

    /** EXPOSURE_GUARD : exposition moyenne du candidat au-delà de {@code factor} × celle du preset en place. */
    public static boolean rejects(double candidateExposurePct, double presetExposurePct, double factor) {
        return candidateExposurePct > factor * presetExposurePct + SAME;
    }

    /** Rejeux de chaque variante sur chaque bloc de réglage, dans l'ordre de soumission. */
    private List<Reading[]> evaluate(List<RainbowAtrParamSet[]> variants) {
        List<Callable<Reading>> tasks = new ArrayList<>();
        for (RainbowAtrParamSet[] v : variants) {
            for (SensitivityEvaluator ev : evs) {
                tasks.add(() -> ev.cycle(v, false));
            }
        }
        List<Reading> flat = new ArrayList<>();
        try {
            if (pool == null) {
                for (Callable<Reading> t : tasks) {
                    flat.add(t.call());
                }
            } else {
                for (Future<Reading> f : pool.invokeAll(tasks)) {
                    flat.add(f.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Réglage interrompu", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Rejeu en échec", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Rejeu en échec", e);
        }
        List<Reading[]> out = new ArrayList<>();
        int n = evs.size();
        for (int i = 0; i < variants.size(); i++) {
            out.add(flat.subList(i * n, (i + 1) * n).toArray(new Reading[0]));
        }
        return out;
    }

    /** Pour le rapport : valeurs en place d'un jeu, par clé. */
    public static Map<String, Object> values(RainbowAtrParamSet set, List<String> keys) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        params(keys).forEach(p -> out.put(p.key(), p.of(set)));
        return out;
    }
}
