package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport du diagnostic des switchs Bull → Bear (étape E0) : JSON et markdown, déterministes pour mêmes entrées. */
public record SwitchDiagnosticReport(String definition, SwitchDiagnosticConfig config, List<AssetDiagnostic> assets, List<String> notes) {

    public static final String DEFINITION = "E0 : au switch Bull → Bear de la Trend (régime du rejeu, confirmation et retard inclus), la baisse restante est-elle "
            + "pire que le hasard ? Prix et régime seuls : aucun preset, aucune modulation, aucun rejeu de stratégie. Gain de coupure = vendre à P_S, racheter "
            + "à P_R (switch Bear → Bull suivant) : P_S / P_R − 1, positif = couper aurait gagné.";

    /** Un switch Bull → Bear et ses mesures ; {@code null} = non défini (fenêtre incomplète en fin de données, switch ouvert). */
    public record SwitchRow(int index, LocalDate day, double price, Double consumedPct, Double remaining90Pct, Double remaining180Pct,
                            Double ret30Pct, Double ret90Pct, Double ret180Pct, Boolean falseSwitch, LocalDate returnDay, Double returnPrice,
                            Double cutGrossPct, Double cutNetPct, Integer bearDays, boolean open, String phase, Double rankRemaining90,
                            Double rankRemaining180, Double rankCutGross, Double rankCutNet) {
    }

    /** Distribution du hasard pour une mesure : effectif, médiane, percentiles 10 / 90. */
    public record NullStat(int n, Double median, Double p10, Double p90) {
    }

    /** Une mesure : médiane des switchs, hasard, rang de la médiane des switchs dans le hasard (part du hasard ≤ médiane, en %). */
    public record Summary(String measure, int nSwitches, Double switchMedian, NullStat random, Double rankOfMedian) {
    }

    public record PhaseStat(String phase, int closed, Double shareNetPositive, Double medianCutNetPct, Double medianRemaining90Pct) {
    }

    public record Landmark(String name, LocalDate from, LocalDate to, List<SwitchRow> switchesInWindow, String note) {
    }

    public record Verdict(String decision, int nClosed, Double shareNetPositive, boolean conditionA, Double medianCutNetPct,
                          Double randomMedianCutNetPct, boolean conditionB, Double medianRemaining90Pct, Double randomMedianRemaining90Pct,
                          boolean conditionC, List<String> causes, List<PhaseStat> phases, String note) {
    }

    public record AssetDiagnostic(String asset, String source, CandleFingerprint fingerprint, LocalDate historyStart, int bullBars, int bearBars,
                                  int nSwitches, int nClosed, int nOpen, Double falseSwitchShare, int falseSwitchKnown, List<Summary> summaries,
                                  List<SwitchRow> switches, List<Landmark> landmarks, Verdict verdict, boolean inVerdict) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de diagnostic de switchs non sérialisable", e);
        }
    }

    static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%+.1f", v);
    }

    static String p(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.0f", v);
    }

    /** Rapport complet : une section par actif, résumé puis tableau des switchs, repères de Clem. */
    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("**" + definition + "**");
        md.add("");
        md.add(String.format(Locale.ROOT, "Graine %d ; %d dates tirées ; frais %.2f %% par ordre (barème simplifié du simulateur) ; fin des bougies %s.",
                config.seed(), config.nullDraws(), config.feePct(), config.end()));
        for (AssetDiagnostic a : assets) {
            md.add("");
            md.add("## " + a.asset() + (a.inVerdict() ? " (référence du verdict)" : " (rapporté, hors verdict)"));
            md.add(String.format(Locale.ROOT, "Source %s ; bougies %s → %s (%d, empreinte %s) ; historique rejouable dès %s (%d j Bull, %d j Bear). "
                            + "Switchs Bull → Bear : %d (%d fermés, %d ouverts).", a.source(), a.fingerprint().firstDay(), a.fingerprint().lastDay(),
                    a.fingerprint().count(), a.fingerprint().checksum(), a.historyStart(), a.bullBars(), a.bearBars(), a.nSwitches(), a.nClosed(), a.nOpen()));
            md.add(a.falseSwitchShare() == null ? "Faux switchs : n/d."
                    : String.format(Locale.ROOT, "Faux switchs (nouveau plus haut de clôture depuis le dernier retour Bull, avant S + %d j) : %.0f %% (sur %d switchs connus).",
                            config.falseSwitchDays(), a.falseSwitchShare(), a.falseSwitchKnown()));
            md.add("");
            md.add("| Mesure | N switchs | Médiane switchs % | Hasard N | Hasard médiane % | Hasard P10 % | Hasard P90 % | Rang médiane (%) |");
            md.add("|---|---|---|---|---|---|---|---|");
            for (Summary s : a.summaries()) {
                md.add(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s |", s.measure(), s.nSwitches(), f(s.switchMedian()),
                        s.random() == null ? "n/d" : s.random().n(), s.random() == null ? "n/d" : f(s.random().median()),
                        s.random() == null ? "n/d" : f(s.random().p10()), s.random() == null ? "n/d" : f(s.random().p90()), p(s.rankOfMedian())));
            }
            if (a.verdict() != null) {
                Verdict v = a.verdict();
                md.add("");
                md.add((a.inVerdict() ? "**Verdict : " : "Lecture (hors verdict) : ") + v.decision() + "**" + (v.causes().isEmpty() ? ""
                        : " — causes : " + String.join(", ", v.causes())));
                md.add(String.format(Locale.ROOT, "N fermés %d ; (a) part net > 0 = %s %% (seuil %.0f %%) : %s ; (b) médiane gain net %s %% vs hasard %s %% : %s ; "
                                + "(c) baisse restante 90 j %s %% vs hasard %s %% : %s.", v.nClosed(), p(v.shareNetPositive()), config.minNetPositiveShare() * 100,
                        v.conditionA(), f(v.medianCutNetPct()), f(v.randomMedianCutNetPct()), v.conditionB(), f(v.medianRemaining90Pct()),
                        f(v.randomMedianRemaining90Pct()), v.conditionC()));
                if (v.note() != null) {
                    md.add(v.note());
                }
                if (!v.phases().isEmpty()) {
                    md.add("Par phase de marché (rendement de P_S sur " + config.phaseLookbackDays() + " j, switchs fermés) : "
                            + String.join(" ; ", v.phases().stream().map(x -> String.format(Locale.ROOT, "%s : %d switchs, part net > 0 = %s %%, "
                                    + "médiane gain net %s %%, médiane baisse restante 90 j %s %%", x.phase(), x.closed(), p(x.shareNetPositive()),
                            f(x.medianCutNetPct()), f(x.medianRemaining90Pct()))).toList()));
                }
            }
            md.add("");
            md.add("Repères de Clem :");
            for (Landmark l : a.landmarks()) {
                md.add("- **" + l.name() + "** (" + l.from() + " → " + l.to() + ") : " + l.note());
            }
            md.add("");
            md.add("Switchs (tous les % sont des rapports à P_S ; rangs = part du hasard ≤ valeur du switch) :");
            md.add("");
            md.add("| # | Jour S | P_S | Consommée % | Reste 90 j % | Reste 180 j % | +30 j % | +90 j % | +180 j % | Faux | Retour R | Gain brut % | Gain net % | Phase Bear (j) | Marché | Rang reste 90 | Rang reste 180 | Rang gain brut | Rang gain net |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (SwitchRow r : a.switches()) {
                md.add(row(r));
            }
        }
        md.add("");
        md.add("## Notes");
        notes.forEach(n -> md.add("- " + n));
        return String.join("\n", md) + "\n";
    }

    /** Ligne de tableau d'un switch. */
    public static String row(SwitchRow r) {
        return String.format(Locale.ROOT, "| %d | %s | %.2f | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |", r.index(),
                r.day(), r.price(), f(r.consumedPct()), f(r.remaining90Pct()), f(r.remaining180Pct()), f(r.ret30Pct()), f(r.ret90Pct()),
                f(r.ret180Pct()), r.falseSwitch() == null ? "n/d" : r.falseSwitch() ? "oui" : "non", r.open() ? "ouvert" : r.returnDay(),
                f(r.cutGrossPct()), f(r.cutNetPct()), r.bearDays() == null ? "n/d" : r.bearDays(), r.phase(), p(r.rankRemaining90()),
                p(r.rankRemaining180()), p(r.rankCutGross()), p(r.rankCutNet()));
    }
}
