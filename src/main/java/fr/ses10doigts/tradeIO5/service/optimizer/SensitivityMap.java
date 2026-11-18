package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.expert.ExpertPresetValidator;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Frozen;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.Baseline;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.Multiplicity;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.ParamRange;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.ParamReport;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.Point;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityReport.SetReport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Carte de sensibilité Rainbow (étape A) : pour Bull seul puis Bear seul (l'autre jeu = preset en place), balaie UN réglage à la fois
 * ({@link SensitivityParam#ALL}, {@value SensitivityParam#GRID} valeurs sur toute la plage permise, valeur en place incluse ; une
 * variante par option pour interrupteurs et modes) et mesure chaque variante avec {@link SensitivityEvaluator} (PnL réel, lecture gel).
 * Variante invalide (règles de {@link ExpertPresetValidator}, ex. ordre des bornes ATR) : ignorée et listée. Déterministe, aucun tirage.
 */
public final class SensitivityMap {

    /** Nombre de courbes détaillées par jeu (et d'historiques complets rejoués, en lecture seule). */
    public static final int TOP_CURVES = 5;
    public static final int VIEW_MONTHS = 24;
    private static final double EPS = 0.01;
    private static final double TIE = 1e-9;
    private static final ExpertPresetValidator VALIDATOR = new ExpertPresetValidator();

    private SensitivityMap() {
    }

    /** Réglage invalide : motif, sinon {@code null}. */
    static String violation(RainbowAtrParamSet set, int analysisWindowMonths) {
        try {
            VALIDATOR.rainbow(analysisWindowMonths, set.tuning(), set.globals());
            return null;
        } catch (ExpertValidationException e) {
            return e.getFields().entrySet().stream().map(x -> x.getKey() + " " + x.getValue()).reduce((a, b) -> a + ", " + b).orElse("invalide");
        }
    }

    /** @param in un seul lien ; {@code endDate} = J-1 */
    public static SensitivityReport run(ScorerInputs in, int months) {
        return run(in, months, SensitivityParam.ALL);
    }

    /** Idem sur un sous-ensemble de réglages (tests, relances ciblées). */
    public static SensitivityReport run(ScorerInputs in, int months, List<SensitivityParam> params) {
        long t0 = System.nanoTime();
        SensitivityEvaluator ev = new SensitivityEvaluator(in, months);
        RainbowAtrParamSet[] sets = ev.template().sets();
        if (sets.length != 2) {
            throw new IllegalArgumentException("Jeux Bear / Bull attendus");
        }
        Reading preset = ev.cycle(sets, false);
        if (preset == null) {
            throw new IllegalStateException("Vue de " + months + " mois non rejouable (historique insuffisant)");
        }
        Baseline baseline = new Baseline(preset, ev.cycle(sets, true), ev.frozen(sets, RainbowAtrPresets.BULL),
                ev.frozen(sets, RainbowAtrPresets.BEAR));
        int window = ev.template().windowMonths();
        List<SetReport> reports = new ArrayList<>();
        int[] counters = new int[3];
        for (int idx : new int[]{RainbowAtrPresets.BULL, RainbowAtrPresets.BEAR}) {
            reports.add(runSet(ev, sets, idx, window, counters, params));
        }
        long full = reports.stream().flatMap(s -> s.params().stream()).flatMap(p -> p.curve().stream()).filter(p -> p.fullHistory() != null).count();
        int values = counters[0];
        int ignored = counters[1];
        Multiplicity multiplicity = new Multiplicity(params.size(), values, ev.replays(), counters[2], values - ignored, full,
                ignored);
        return new SensitivityReport(inputs(ev, in, months), baseline, reports, multiplicity, notes(),
                new SensitivityReport.Timings((System.nanoTime() - t0) / 1_000_000));
    }

    private static SetReport runSet(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, int idx, int window, int[] counters,
                                    List<SensitivityParam> params) {
        String role = idx == RainbowAtrPresets.BULL ? "BULL" : "BEAR";
        List<String> skipped = new ArrayList<>();
        List<Draft> drafts = new ArrayList<>();
        for (SensitivityParam p : params) {
            List<Point> curve = new ArrayList<>();
            for (SensitivityParam.Value v : p.values(sets[idx])) {
                counters[0]++;
                RainbowAtrParamSet changed = p.apply(sets[idx], v.raw());
                String reason = violation(changed, window);
                if (reason != null) {
                    counters[1]++;
                    skipped.add(role + "." + p.key() + "=" + v.label() + " (" + reason + ")");
                    curve.add(new Point(v.label(), v.inPlace(), reason, null, null, null));
                    continue;
                }
                RainbowAtrParamSet[] variant = sets.clone();
                variant[idx] = changed;
                Reading r = ev.cycle(variant, false);
                if (r == null) {
                    counters[1]++;
                    skipped.add(role + "." + p.key() + "=" + v.label() + " (non rejouable)");
                    curve.add(new Point(v.label(), v.inPlace(), "non rejouable", null, null, null));
                    continue;
                }
                counters[2]++;
                curve.add(new Point(v.label(), v.inPlace(), null, r, ev.frozen(variant, idx), null));
            }
            drafts.add(new Draft(p, curve, p.values(sets[idx])));
        }
        List<ParamReport> ranked = new ArrayList<>();
        for (Draft d : drafts) {
            ranked.add(summary(d, 0));
        }
        ranked.sort(Comparator.comparingDouble(ParamReport::spreadPnl).reversed().thenComparing(ParamReport::key));
        List<ParamReport> out = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            ParamReport r = ranked.get(i);
            List<Point> curve = r.curve();
            if (i < TOP_CURVES) {
                curve = withFullHistory(ev, sets, idx, params.stream().filter(p -> p.key().equals(r.key())).findFirst().orElseThrow(),
                        r.curve());
            }
            out.add(new ParamReport(i + 1, r.key(), r.group(), r.kind(), r.inPlace(), r.best(), r.worst(), r.spreadPnl(), r.bestVsPresetPnl(),
                    r.direction(), r.beatsPreset(), r.beatsDca(), r.frozenBest(), r.frozenSpread(), r.frozenBestVsPreset(), curve));
        }
        return new SensitivityReport.SetReport(role, out, skipped);
    }

    private record Draft(SensitivityParam param, List<Point> curve, List<SensitivityParam.Value> values) {
    }

    /** Historique complet (lecture seule) de chaque valeur rejouable de la courbe. */
    private static List<Point> withFullHistory(SensitivityEvaluator ev, RainbowAtrParamSet[] sets, int idx, SensitivityParam p,
                                               List<Point> curve) {
        List<SensitivityParam.Value> values = p.values(sets[idx]);
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < curve.size(); i++) {
            Point pt = curve.get(i);
            if (pt.reading() == null) {
                out.add(pt);
                continue;
            }
            RainbowAtrParamSet[] variant = sets.clone();
            variant[idx] = p.apply(sets[idx], values.get(i).raw());
            out.add(new Point(pt.value(), pt.inPlace(), null, pt.reading(), pt.frozen(), ev.cycle(variant, true)));
        }
        return out;
    }

    private static ParamReport summary(Draft d, int rank) {
        SensitivityParam p = d.param();
        List<Point> ok = d.curve().stream().filter(x -> x.reading() != null).toList();
        Point inPlace = ok.stream().filter(Point::inPlace).findFirst().orElseThrow();
        Point best = inPlace;
        Point worst = inPlace;
        for (Point x : ok) {
            if (x.reading().pnl() > best.reading().pnl() + TIE) {
                best = x;
            }
            if (x.reading().pnl() < worst.reading().pnl() - TIE) {
                worst = x;
            }
        }
        double vsPreset = best.reading().pnl() - inPlace.reading().pnl();
        Point fBest = inPlace;
        double fMin = inPlace.frozen() == null ? 0 : inPlace.frozen().totalGain();
        double fMax = fMin;
        for (Point x : ok) {
            if (x.frozen() == null) {
                continue;
            }
            double g = x.frozen().totalGain();
            if (inPlace.frozen() != null && g > fBest.frozen().totalGain() + TIE) {
                fBest = x;
            }
            fMax = Math.max(fMax, g);
            fMin = Math.min(fMin, g);
        }
        double fVs = inPlace.frozen() == null || fBest.frozen() == null ? 0 : fBest.frozen().totalGain() - inPlace.frozen().totalGain();
        return new ParamReport(rank, p.key(), p.group(), p.kind().name(), inPlace.value(), best.value(), worst.value(),
                best.reading().pnl() - worst.reading().pnl(), vsPreset, direction(p, d.values(), inPlace, best), vsPreset > EPS,
                best.reading().finalValue() > best.reading().fixedFinalValue() + EPS, fBest.value(), fMax - fMin, fVs, d.curve());
    }

    private static String direction(SensitivityParam p, List<SensitivityParam.Value> values, Point inPlace, Point best) {
        if (best == inPlace) {
            return "en place";
        }
        if (p.kind() == SensitivityParam.Kind.CONTINUOUS || p.kind() == SensitivityParam.Kind.INTEGER) {
            double a = Double.parseDouble(best.value());
            double b = Double.parseDouble(inPlace.value());
            return a > b ? "monter" : "baisser";
        }
        return "→ " + best.value();
    }

    private static SensitivityReport.Inputs inputs(SensitivityEvaluator ev, ScorerInputs in, int months) {
        RainbowAtrParamSet[] sets = ev.template().sets();
        List<ParamRange> ranges = SensitivityParam.ALL.stream().map(p -> new ParamRange(p.key(), p.group(), p.kind().name(), p.min(), p.max())).toList();
        return new SensitivityReport.Inputs(ev.asset(), in.assets().getFirst().source(), in.endDate(), months, in.cash0(), ev.template().linkBaseAmount(),
                CandleFingerprint.of(ev.asset(), ev.template().candles(), in.endDate()), SensitivityParam.GRID, values(sets[RainbowAtrPresets.BULL]),
                values(sets[RainbowAtrPresets.BEAR]), ranges, List.of("smaPeriod", "atrPeriod", "baseAmount", "analysisWindowMonths", "Trend"),
                "PnL réel = valeur finale du portefeuille (cash + position) − capital : wallet à un lien, capital fixe sans apport, coûts simplifiés, "
                        + "Trend → Bull / Bear, un seul jeu varie. « Valeur fin bull » = valeur du portefeuille au dernier jour de la dernière phase Bull "
                        + "terminée par un Bear dans la plage (dont position = latent non encaissé).",
                "Même logique que l'écran Expert (type RAINBOW) : seul le jeu testé tourne, barres hors de son régime inactives (aucune entrée, DCA fixe "
                        + "arrêté, prix de valorisation figé) ; gain total = ventes + position − investi ; DCA fixe mis à budget égal comme le front.");
    }

    private static Map<String, Object> values(RainbowAtrParamSet set) {
        Map<String, Object> out = new LinkedHashMap<>();
        SensitivityParam.ALL.forEach(p -> out.put(p.key(), p.of(set)));
        return out;
    }

    private static List<String> notes() {
        return List.of(
                "Plage de balayage = règles de ExpertPresetValidator ; l'interface ne borne pas le haut des facteurs ATH (plafond 5) ni des "
                        + "multiplicateurs de zones (plafond 6) ni des bornes ATR (20) : ces plafonds sont des choix de balayage, pas des règles métier.",
                "Plafond de mise ATH utilisateur (L2) non appliqué ; modulation utilisateur neutre ; coûts simplifiés (taux constant, sans minimum d'ordre).",
                "Un paramètre à la fois : aucune interaction entre réglages n'est mesurée dans cette étape.");
    }
}
