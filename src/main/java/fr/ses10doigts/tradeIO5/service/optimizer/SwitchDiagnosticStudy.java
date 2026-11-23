package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.expert.simulation.LinkSignalReplay;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.AssetDiagnostic;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.Landmark;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.NullStat;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.PhaseStat;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.Summary;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.SwitchRow;
import fr.ses10doigts.tradeIO5.service.optimizer.SwitchDiagnosticReport.Verdict;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Étape E0 : diagnostic descriptif des switchs Bull → Bear de la Trend (régime du rejeu Rainbow, {@link LinkSignalReplay#regimes}). Mesure sur
 * les PRIX D1 et le RÉGIME seulement : aucun preset, aucune modulation, aucun rejeu de stratégie. Un switch = jour S où le jeu actif passe de
 * Bull à Bear ; il ne lit que les jours ≤ S (testé). Référence nulle : dates tirées au hasard (graine fixe). Hors ligne : rien appliqué ni
 * persisté. Pur et déterministe.
 */
@Slf4j
public final class SwitchDiagnosticStudy {

    private static final int BEAR = RainbowAtrPresets.BEAR;
    private static final int BULL = RainbowAtrPresets.BULL;
    private static final String UP_PHASE = "HAUSSIERE";
    private static final String DOWN_PHASE = "BAISSIERE";
    private static final String UNKNOWN_PHASE = "INDETERMINEE";
    private static final String CLOSED_OFF = "PISTE_FERMÉE";

    /** Repères de Clem (fenêtres fixées avant lecture). */
    static final LocalDate MARCH_2023_FROM = LocalDate.of(2023, 3, 11);
    static final LocalDate MARCH_2023_TO = LocalDate.of(2023, 3, 23);
    static final LocalDate AUTUMN_2025_FROM = LocalDate.of(2025, 8, 1);
    static final LocalDate AUTUMN_2025_TO = LocalDate.of(2025, 10, 31);

    private SwitchDiagnosticStudy() {
    }

    /** Série d'un actif : jours, clôtures, jeu actif du rejeu (Bear / Bull) ; {@code histStart} = première bougie à Trend définie. */
    public record Series(String asset, String source, LocalDate[] days, double[] close, int[] set, int histStart) {
        int last() {
            return close.length - 1;
        }
    }

    /** Série tirée du lien chargé pour le rejeu : même code de régime que le rejeu ({@link LinkSignalReplay#regimes}). */
    public static Series series(ScorerInputs.AssetInput link) {
        LinkSignalReplay.Config cfg = link.template();
        TrendRegime[] regime = LinkSignalReplay.regimes(cfg);
        int[] set = LinkSignalReplay.setOfBar(cfg, regime);
        RainbowAtrDataset ds = cfg.ds();
        int last = Math.min(cfg.endIdx(), ds.size() - 1);
        LocalDate[] days = new LocalDate[last + 1];
        double[] close = new double[last + 1];
        int hist = last;
        for (int i = 0; i <= last; i++) {
            days[i] = Instant.ofEpochMilli(ds.time(i)).atOffset(ZoneOffset.UTC).toLocalDate();
            close[i] = ds.close(i);
            if (hist == last && regime[i] != null) {
                hist = i;
            }
        }
        return new Series(link.slot().assetSymbol(), link.source(), days, close, java.util.Arrays.copyOf(set, last + 1), hist);
    }

    /** Jours S (strictement après {@code histStart}) où le jeu passe de Bull à Bear : ne lit que {@code set[S-1]} et {@code set[S]}. */
    public static List<Integer> bullToBear(int[] set, int histStart, int last) {
        List<Integer> out = new ArrayList<>();
        for (int i = histStart + 1; i <= last; i++) {
            if (set[i] == BEAR && set[i - 1] == BULL) {
                out.add(i);
            }
        }
        return out;
    }

    /** Premier retour Bear → Bull strictement après {@code from} ; {@code -1} si aucun avant {@code last}. */
    static int nextBull(int[] set, int from, int last) {
        for (int i = from + 1; i <= last; i++) {
            if (set[i] == BULL && set[i - 1] == BEAR) {
                return i;
            }
        }
        return -1;
    }

    /** Dernier retour Bear → Bull avant {@code s} (inclus) ; {@code histStart} sinon. */
    static int previousBull(int[] set, int histStart, int s) {
        for (int i = s; i > histStart; i--) {
            if (set[i] == BULL && set[i - 1] == BEAR) {
                return i;
            }
        }
        return histStart;
    }

    /** Gain de coupure en % : vendre à {@code ps}, racheter à {@code pr} ; net de frais aller-retour si {@code fee} (fraction par ordre) > 0. */
    static double cutPct(double ps, double pr, double fee) {
        return ((1 - fee) / (1 + fee) * ps / pr - 1) * 100;
    }

    /** Pire clôture sur {@code [from, from + h]} rapportée à {@code close[from]}, en % ; {@code null} si la fenêtre dépasse les données. */
    static Double remaining(double[] close, int from, int h, int last) {
        if (from + h > last) {
            return null;
        }
        double min = close[from];
        for (int j = from; j <= from + h; j++) {
            min = Math.min(min, close[j]);
        }
        return (min / close[from] - 1) * 100;
    }

    static Double forward(double[] close, int from, int h, int last) {
        return from + h > last ? null : (close[from + h] / close[from] - 1) * 100;
    }

    /**
     * Un nouveau plus haut de clôture avant S + n : clôture > plus haut de {@code [peakFrom, S]}, où {@code peakFrom} = dernier retour Bear → Bull
     * avant S (même référence que la baisse consommée, mesure 1) ; {@code null} si inconnu (fenêtre incomplète, rien vu).
     */
    static Boolean falseSwitch(double[] close, int peakFrom, int s, int n, int last) {
        double peak = Double.NEGATIVE_INFINITY;
        for (int j = peakFrom; j <= s; j++) {
            peak = Math.max(peak, close[j]);
        }
        for (int j = s + 1; j <= Math.min(s + n, last); j++) {
            if (close[j] > peak) {
                return true;
            }
        }
        return s + n <= last ? Boolean.FALSE : null;
    }

    /** Rang en % : part des valeurs du hasard ≤ {@code x} ; {@code null} sans valeur. */
    static Double rank(List<Double> random, Double x) {
        if (x == null || random.isEmpty()) {
            return null;
        }
        long le = random.stream().filter(v -> v <= x).count();
        return 100.0 * le / random.size();
    }

    private static NullStat stat(List<Double> v) {
        return v.isEmpty() ? null : new NullStat(v.size(), ExposureParity.percentile(v, 0.5), ExposureParity.percentile(v, 0.1),
                ExposureParity.percentile(v, 0.9));
    }

    private static List<Double> nonNull(List<SwitchRow> rows, Function<SwitchRow, Double> f) {
        return rows.stream().map(f).filter(x -> x != null).toList();
    }

    private static Double median(List<Double> v) {
        return ExposureParity.percentile(v, 0.5);
    }

    /** Hasard : mêmes tirages pour toutes les mesures (graine fixe) ; valeurs hors fenêtre exclues mesure par mesure. */
    private record Randoms(List<Double> rem90, List<Double> rem180, List<List<Double>> returns, List<Double> cutGross, List<Double> cutNet) {
    }

    private static Randoms draw(Series s, SwitchDiagnosticConfig c, List<Integer> closedDurations) {
        Random rng = new Random(c.seed());
        int last = s.last();
        int span = last - s.histStart() + 1;
        double fee = c.feePct() / 100.0;
        List<Double> r90 = new ArrayList<>();
        List<Double> r180 = new ArrayList<>();
        List<List<Double>> ret = new ArrayList<>();
        for (int i = 0; i < c.returnHorizons().length; i++) {
            ret.add(new ArrayList<>());
        }
        List<Double> gross = new ArrayList<>();
        List<Double> net = new ArrayList<>();
        for (int k = 0; k < c.nullDraws(); k++) {
            int d = s.histStart() + rng.nextInt(span);
            int dur = closedDurations.isEmpty() ? -1 : closedDurations.get(rng.nextInt(closedDurations.size()));
            addIfPresent(r90, remaining(s.close(), d, c.shortHorizon(), last));
            addIfPresent(r180, remaining(s.close(), d, c.longHorizon(), last));
            for (int i = 0; i < c.returnHorizons().length; i++) {
                addIfPresent(ret.get(i), forward(s.close(), d, c.returnHorizons()[i], last));
            }
            if (dur > 0 && d + dur <= last) {
                gross.add(cutPct(s.close()[d], s.close()[d + dur], 0.0));
                net.add(cutPct(s.close()[d], s.close()[d + dur], fee));
            }
        }
        return new Randoms(sorted(r90), sorted(r180), ret.stream().map(SwitchDiagnosticStudy::sorted).toList(), sorted(gross), sorted(net));
    }

    private static void addIfPresent(List<Double> l, Double v) {
        if (v != null) {
            l.add(v);
        }
    }

    private static List<Double> sorted(List<Double> l) {
        List<Double> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    private static String phase(Series s, int sw, int lookback) {
        if (sw - lookback < s.histStart()) {
            return UNKNOWN_PHASE;
        }
        return s.close()[sw] >= s.close()[sw - lookback] ? UP_PHASE : DOWN_PHASE;
    }

    /** Diagnostic complet d'un actif ; {@code inVerdict} = actif de référence du verdict (BTC). */
    public static AssetDiagnostic analyze(Series s, SwitchDiagnosticConfig c, CandleFingerprint fingerprint, boolean inVerdict) {
        int last = s.last();
        double fee = c.feePct() / 100.0;
        List<Integer> switches = bullToBear(s.set(), s.histStart(), last);
        List<Integer> returns = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        for (int sw : switches) {
            int r = nextBull(s.set(), sw, last);
            returns.add(r);
            if (r > 0) {
                durations.add(r - sw);
            }
        }
        Randoms rnd = draw(s, c, durations);
        int[] h = c.returnHorizons();
        List<SwitchRow> rows = new ArrayList<>();
        for (int k = 0; k < switches.size(); k++) {
            int sw = switches.get(k);
            int r = returns.get(k);
            double ps = s.close()[sw];
            int prev = previousBull(s.set(), s.histStart(), sw);
            double peak = 0;
            for (int j = prev; j <= sw; j++) {
                peak = Math.max(peak, s.close()[j]);
            }
            Double r90 = remaining(s.close(), sw, c.shortHorizon(), last);
            Double r180 = remaining(s.close(), sw, c.longHorizon(), last);
            Double gross = r > 0 ? cutPct(ps, s.close()[r], 0.0) : null;
            Double net = r > 0 ? cutPct(ps, s.close()[r], fee) : null;
            rows.add(new SwitchRow(k + 1, s.days()[sw], ps, (ps / peak - 1) * 100, r90, r180, forward(s.close(), sw, h[0], last),
                    forward(s.close(), sw, h[1], last), forward(s.close(), sw, h[2], last), falseSwitch(s.close(), prev, sw,
                    c.falseSwitchDays(), last), r > 0 ? s.days()[r] : null, r > 0 ? s.close()[r] : null, gross, net,
                    r > 0 ? (int) ChronoUnit.DAYS.between(s.days()[sw], s.days()[r]) : null, r < 0, phase(s, sw, c.phaseLookbackDays()),
                    rank(rnd.rem90(), r90), rank(rnd.rem180(), r180), rank(rnd.cutGross(), gross), rank(rnd.cutNet(), net)));
        }
        List<SwitchRow> closed = rows.stream().filter(x -> !x.open()).toList();
        List<Summary> summaries = new ArrayList<>();
        summaries.add(new Summary("Baisse déjà consommée (mesure 1)", rows.size(), median(nonNull(rows, SwitchRow::consumedPct)), null, null));
        addSummary(summaries, "Baisse restante " + c.shortHorizon() + " j (mesure 2)", nonNull(rows, SwitchRow::remaining90Pct), rnd.rem90());
        addSummary(summaries, "Baisse restante " + c.longHorizon() + " j (mesure 2)", nonNull(rows, SwitchRow::remaining180Pct), rnd.rem180());
        addSummary(summaries, "Rendement +" + h[0] + " j (mesure 3)", nonNull(rows, SwitchRow::ret30Pct), rnd.returns().get(0));
        addSummary(summaries, "Rendement +" + h[1] + " j (mesure 3)", nonNull(rows, SwitchRow::ret90Pct), rnd.returns().get(1));
        addSummary(summaries, "Rendement +" + h[2] + " j (mesure 3)", nonNull(rows, SwitchRow::ret180Pct), rnd.returns().get(2));
        addSummary(summaries, "Gain de coupure brut, switchs fermés (mesure 5)", nonNull(closed, SwitchRow::cutGrossPct), rnd.cutGross());
        addSummary(summaries, "Gain de coupure net de frais, switchs fermés (mesure 5)", nonNull(closed, SwitchRow::cutNetPct), rnd.cutNet());
        summaries.add(new Summary("Durée de la phase Bear, switchs fermés, jours (mesure 6)", closed.size(),
                median(closed.stream().map(x -> x.bearDays().doubleValue()).toList()), null, null));
        List<Boolean> falses = rows.stream().map(SwitchRow::falseSwitch).filter(x -> x != null).toList();
        Double falseShare = falses.isEmpty() ? null : 100.0 * falses.stream().filter(x -> x).count() / falses.size();
        Verdict verdict = verdict(closed, rnd, c);
        int bull = 0;
        int bear = 0;
        for (int i = s.histStart(); i <= last; i++) {
            if (s.set()[i] == BULL) {
                bull++;
            } else {
                bear++;
            }
        }
        List<Landmark> landmarks = List.of(landmark("Faux switch de mars 2023", MARCH_2023_FROM, MARCH_2023_TO, rows, s),
                landmark("Switch d'août à octobre 2025", AUTUMN_2025_FROM, AUTUMN_2025_TO, rows, s));
        log.info("E0 {} : {} switchs Bull -> Bear ({} fermes), verdict {}", s.asset(), rows.size(), closed.size(), verdict.decision());
        return new AssetDiagnostic(s.asset(), s.source(), fingerprint, s.days()[s.histStart()], bull, bear, rows.size(), closed.size(),
                rows.size() - closed.size(), falseShare, falses.size(), summaries, rows, landmarks, verdict, inVerdict);
    }

    private static void addSummary(List<Summary> out, String name, List<Double> switchValues, List<Double> random) {
        Double med = median(switchValues);
        out.add(new Summary(name, switchValues.size(), med, stat(random), med == null ? null : rank(random, med)));
    }

    /** Règle fixée avant lecture (BTC, switchs fermés) : {@code NON_CONCLUANT} sous {@code minSwitches}, sinon (a) ET (b) ET (c). */
    static Verdict verdict(List<SwitchRow> closed, Randoms rnd, SwitchDiagnosticConfig c) {
        int n = closed.size();
        List<Double> nets = nonNull(closed, SwitchRow::cutNetPct);
        List<Double> rem = nonNull(closed, SwitchRow::remaining90Pct);
        Double share = nets.isEmpty() ? null : (double) nets.stream().filter(x -> x > 0).count() / nets.size();
        Double medNet = median(nets);
        Double rndNet = median(rnd.cutNet());
        Double medRem = median(rem);
        Double rndRem = median(rnd.rem90());
        boolean a = share != null && share >= c.minNetPositiveShare();
        boolean b = medNet != null && rndNet != null && medNet > rndNet;
        boolean cond = medRem != null && rndRem != null && medRem < rndRem;
        List<PhaseStat> phases = new ArrayList<>();
        for (String ph : List.of(UP_PHASE, DOWN_PHASE)) {
            List<SwitchRow> sub = closed.stream().filter(x -> ph.equals(x.phase())).toList();
            if (!sub.isEmpty()) {
                List<Double> pn = nonNull(sub, SwitchRow::cutNetPct);
                phases.add(new PhaseStat(ph, sub.size(), pn.isEmpty() ? null : 100.0 * pn.stream().filter(x -> x > 0).count() / pn.size(),
                        median(pn), median(nonNull(sub, SwitchRow::remaining90Pct))));
            }
        }
        if (n < c.minSwitches()) {
            return new Verdict("NON_CONCLUANT", n, share == null ? null : share * 100, a, medNet, rndNet, b, medRem, rndRem, cond, List.of(), phases,
                    "N = " + n + " < " + c.minSwitches() + " : puissance insuffisante, descriptif seul ; ne pas conclure « piste fermée ».");
        }
        List<String> causes = new ArrayList<>();
        if (!a) {
            causes.add("a");
        }
        if (!b) {
            causes.add("b");
        }
        if (!cond) {
            causes.add("c");
        }
        if (causes.isEmpty()) {
            return new Verdict("PISTE_OUVERTE", n, share * 100, true, medNet, rndNet, true, medRem, rndRem, true, causes, phases, null);
        }
        String note = null;
        for (PhaseStat p : phases) {
            boolean pa = p.shareNetPositive() != null && p.shareNetPositive() >= c.minNetPositiveShare() * 100;
            boolean pc = p.medianRemaining90Pct() != null && rndRem != null && p.medianRemaining90Pct() < rndRem;
            if (pa && pc) {
                note = (note == null ? "" : note + " ; ") + "l'effet existe seulement en phase " + p.phase() + " (" + p.closed() + " switchs)";
            }
        }
        return new Verdict(CLOSED_OFF, n, share == null ? null : share * 100, a, medNet, rndNet, b, medRem, rndRem, cond, causes, phases,
                note == null ? "Aucune phase de marché ne satisfait seule (a) et (c)." : note);
    }

    private static Landmark landmark(String name, LocalDate from, LocalDate to, List<SwitchRow> rows, Series s) {
        List<SwitchRow> in = rows.stream().filter(r -> !r.day().isBefore(from) && !r.day().isAfter(to)).toList();
        if (!in.isEmpty()) {
            return new Landmark(name, from, to, in, in.size() + " switch(s) Bull → Bear dans la fenêtre : "
                    + String.join(" ; ", in.stream().map(SwitchDiagnosticStudy::describe).toList()));
        }
        int bull = 0;
        int bear = 0;
        for (int i = s.histStart(); i <= s.last(); i++) {
            if (!s.days()[i].isBefore(from) && !s.days()[i].isAfter(to)) {
                if (s.set()[i] == BULL) {
                    bull++;
                } else {
                    bear++;
                }
            }
        }
        SwitchRow nearest = rows.stream().min(java.util.Comparator.comparingLong(r -> Math.min(Math.abs(ChronoUnit.DAYS.between(r.day(), from)),
                Math.abs(ChronoUnit.DAYS.between(r.day(), to))))).orElse(null);
        String near = nearest == null ? "aucun switch Bull → Bear sur l'historique" : "switch le plus proche : " + describe(nearest);
        return new Landmark(name, from, to, List.of(), "aucun switch Bull → Bear du rejeu dans la fenêtre (" + bull + " j Bull, " + bear + " j Bear) ; " + near);
    }

    private static String describe(SwitchRow r) {
        return String.format(java.util.Locale.ROOT, "S = %s (P %.2f), consommée %s %%, reste 90 j %s %%, 180 j %s %%, +30 j %s %%, +90 j %s %%, +180 j %s %%, "
                        + "faux switch %s, retour %s, gain brut %s %% net %s %%, phase Bear %s j, rangs reste 90 / 180 : %s / %s, gain brut / net : %s / %s",
                r.day(), r.price(), SwitchDiagnosticReport.f(r.consumedPct()), SwitchDiagnosticReport.f(r.remaining90Pct()),
                SwitchDiagnosticReport.f(r.remaining180Pct()), SwitchDiagnosticReport.f(r.ret30Pct()), SwitchDiagnosticReport.f(r.ret90Pct()),
                SwitchDiagnosticReport.f(r.ret180Pct()), r.falseSwitch() == null ? "n/d" : r.falseSwitch() ? "oui" : "non",
                r.open() ? "ouvert" : r.returnDay(), SwitchDiagnosticReport.f(r.cutGrossPct()), SwitchDiagnosticReport.f(r.cutNetPct()),
                r.bearDays() == null ? "n/d" : r.bearDays(), SwitchDiagnosticReport.p(r.rankRemaining90()), SwitchDiagnosticReport.p(r.rankRemaining180()),
                SwitchDiagnosticReport.p(r.rankCutGross()), SwitchDiagnosticReport.p(r.rankCutNet()));
    }

    /** Un actif chargé pour le rejeu : bougies et régime du rejeu, rien d'autre. */
    public static AssetDiagnostic run(ScorerInputs in, SwitchDiagnosticConfig c, boolean inVerdict) {
        ScorerInputs.AssetInput link = in.assets().getFirst();
        Series s = series(link);
        CandleFingerprint fp = CandleFingerprint.of(s.asset(), link.template().candles(), c.end());
        return analyze(s, c, fp, inVerdict);
    }
}
