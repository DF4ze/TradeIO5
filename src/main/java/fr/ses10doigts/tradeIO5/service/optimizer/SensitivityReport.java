package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Frozen;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rapport de la carte de sensibilité Rainbow (étape A) : entrées explicites, comparateurs (preset en place, DCA fixe), un tableau classé
 * par influence pour Bull et un pour Bear, courbes complètes (valeur → PnL réel), multiplicité. Rien n'est appliqué ni persisté. Mêmes
 * entrées + même empreinte de bougies ⇒ JSON identique hors {@code timings} ({@link #toJsonWithoutTimings()}).
 */
public record SensitivityReport(Inputs inputs, Baseline baseline, List<SetReport> sets, Multiplicity multiplicity, List<String> notes,
                                Timings timings) {

    public record ParamRange(String key, String group, String kind, double min, double max) {
    }

    public record Inputs(String asset, String source, LocalDate endDate, int months, double cash0, Double linkBaseAmount,
                         CandleFingerprint fingerprint, int gridPoints, Map<String, Object> inPlaceBull, Map<String, Object> inPlaceBear,
                         List<ParamRange> ranges, List<String> frozenByDesign, String measure, String freezeReading) {
    }

    /** Comparateurs communs : preset en place (24 m et historique complet) et lecture gel de chaque jeu ; le DCA fixe est dans {@link Reading}. */
    public record Baseline(Reading preset24m, Reading presetFull, Frozen frozenBull, Frozen frozenBear) {
    }

    /** Un point de la courbe : {@code reading} nul si la valeur est ignorée ({@code invalidReason}). */
    public record Point(String value, boolean inPlace, String invalidReason, Reading reading, Frozen frozen, Reading fullHistory) {
    }

    public record ParamReport(int rank, String key, String group, String kind, String inPlace, String best, String worst, double spreadPnl,
                              double bestVsPresetPnl, String direction, boolean beatsPreset, boolean beatsDca, String frozenBest,
                              double frozenSpread, double frozenBestVsPreset, List<Point> curve) {
    }

    /** {@code role} = BULL ou BEAR (le seul jeu qui varie ; l'autre reste celui du preset en place). */
    public record SetReport(String role, List<ParamReport> params, List<String> skipped) {
    }

    public record Multiplicity(int parameters, int valuesTested, long replays, long cycleReplays, long frozenReplays,
                               long fullHistoryReplays, int ignoredCombinations) {
    }

    public record Timings(long totalMs) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de sensibilité non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        return new SensitivityReport(inputs, baseline, sets, multiplicity, notes, null).toJson();
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%+.1f", v);
    }

    private static String n(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String yn(boolean b) {
        return b ? "oui" : "non";
    }

    /** Version lisible : entrées, comparateurs, deux tableaux classés, courbes des 5 paramètres les plus influents, ignorés, multiplicité. */
    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("# Carte de sensibilité Rainbow — " + inputs.asset());
        md.add("");
        md.add("Vue de test : " + inputs.months() + " mois glissants finissant le " + inputs.endDate() + " (J-1) ; capital " + n(inputs.cash0())
                + " USDC ; source des bougies " + inputs.source() + " ; empreinte " + inputs.fingerprint().checksum() + " ("
                + inputs.fingerprint().count() + " bougies, " + inputs.fingerprint().firstDay() + " → " + inputs.fingerprint().lastDay() + ").");
        md.add("Mesure : " + inputs.measure());
        md.add("Gel : " + inputs.freezeReading());
        md.add("");
        md.add("## Comparateurs (24 mois, valeur du portefeuille)");
        md.add("| Ligne | Plage | Valeur finale | PnL réel $ | PnL % | Réalisé $ | Valeur fin bull $ | dont position $ | Drawdown | Expo moy. | Achats / ventes |");
        md.add("|---|---|---|---|---|---|---|---|---|---|---|");
        Reading p = baseline.preset24m();
        md.add(String.format(Locale.ROOT, "| Preset en place | %s → %s | %s | %s | %+.1f | %s | %s | %s | %.1f %% | %.0f %% | %d / %d |", p.from(), p.to(),
                n(p.finalValue()), f(p.pnl()), p.pnlPct(), n(p.realized()), n(p.bullEndValue()), n(p.bullEndPosition()), p.maxDrawdownPct(),
                p.avgExposurePct(), p.buys(), p.sells()));
        md.add(String.format(Locale.ROOT, "| DCA fixe (même cash) | %s → %s | %s | %s | %+.1f | — | — | — | — | — | — |", p.from(), p.to(),
                n(p.fixedFinalValue()), f(p.fixedPnl()), 100.0 * p.fixedPnl() / inputs.cash0()));
        if (baseline.presetFull() != null) {
            Reading r = baseline.presetFull();
            md.add(String.format(Locale.ROOT, "| Preset, historique complet (lecture seule) | %s → %s | %s | %s | %+.1f | %s | %s | %s | %.1f %% | %.0f %% | %d / %d |",
                    r.from(), r.to(), n(r.finalValue()), f(r.pnl()), r.pnlPct(), n(r.realized()), n(r.bullEndValue()), n(r.bullEndPosition()),
                    r.maxDrawdownPct(), r.avgExposurePct(), r.buys(), r.sells()));
            md.add(String.format(Locale.ROOT, "| DCA fixe, historique complet | %s → %s | %s | %s | %+.1f | — | — | — | — | — | — |", r.from(), r.to(),
                    n(r.fixedFinalValue()), f(r.fixedPnl()), 100.0 * r.fixedPnl() / inputs.cash0()));
        }
        md.add("");
        md.add("Lecture gel (seul le régime testé compte, un jeu à la fois) : "
                + (baseline.frozenBull() == null ? "Bull indisponible" : String.format(Locale.ROOT, "Bull gain total %s $ (DCA fixe même budget %s $, investi %s $)",
                f(baseline.frozenBull().totalGain()), f(baseline.frozenBull().dcaSameBudget()), n(baseline.frozenBull().invested())))
                + " ; "
                + (baseline.frozenBear() == null ? "Bear indisponible" : String.format(Locale.ROOT, "Bear gain total %s $ (DCA fixe même budget %s $, investi %s $)",
                f(baseline.frozenBear().totalGain()), f(baseline.frozenBear().dcaSameBudget()), n(baseline.frozenBear().invested()))) + ".");
        for (SetReport s : sets) {
            md.add("");
            md.add("## " + s.role() + " seul (l'autre jeu = preset en place) — classé par influence (écart de PnL réel $, meilleure − pire valeur)");
            md.add("| # | Paramètre | En place | Meilleure | Pire | Écart max−min $ | Meilleure − preset $ | Sens | Bat le preset ? | Bat le DCA ? | Gel : meilleure | Gel : écart $ |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (ParamReport r : s.params()) {
                md.add(String.format(Locale.ROOT, "| %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |", r.rank(), r.key(), r.inPlace(),
                        r.best(), r.worst(), n(r.spreadPnl()), f(r.bestVsPresetPnl()), r.direction(), yn(r.beatsPreset()), yn(r.beatsDca()),
                        r.frozenBest(), n(r.frozenSpread())));
            }
            for (ParamReport r : s.params().stream().limit(5).toList()) {
                md.add("");
                md.add("### " + s.role() + " — " + r.key() + " (rang " + r.rank() + ", en place " + r.inPlace() + ")");
                md.add("| Valeur | PnL réel $ | PnL % | Réalisé $ | Valeur fin bull $ | Drawdown | Expo moy. | Achats / ventes | Gel $ | Histo. complet PnL $ |");
                md.add("|---|---|---|---|---|---|---|---|---|---|");
                for (Point pt : r.curve()) {
                    String v = pt.value() + (pt.inPlace() ? " (en place)" : "");
                    if (pt.reading() == null) {
                        md.add("| " + v + " | ignorée : " + pt.invalidReason() + " | | | | | | | | |");
                        continue;
                    }
                    Reading x = pt.reading();
                    md.add(String.format(Locale.ROOT, "| %s | %s | %+.1f | %s | %s | %.1f %% | %.0f %% | %d / %d | %s | %s |", v, f(x.pnl()), x.pnlPct(),
                            n(x.realized()), n(x.bullEndValue()), x.maxDrawdownPct(), x.avgExposurePct(), x.buys(), x.sells(),
                            pt.frozen() == null ? "—" : f(pt.frozen().totalGain()), pt.fullHistory() == null ? "—" : f(pt.fullHistory().pnl())));
                }
            }
            md.add("");
            md.add("Combinaisons ignorées (" + s.skipped().size() + ") : " + (s.skipped().isEmpty() ? "aucune" : String.join(" ; ", s.skipped())));
        }
        md.add("");
        md.add("## Multiplicité");
        Multiplicity m = multiplicity;
        md.add(m.parameters() + " paramètres, " + m.valuesTested() + " valeurs testées (Bull + Bear), " + m.replays() + " rejeux (" + m.cycleReplays()
                + " cycle, " + m.frozenReplays() + " gel, " + m.fullHistoryReplays() + " historique complet), " + m.ignoredCombinations()
                + " combinaisons ignorées. Plus il y a de valeurs testées, plus un écart « gagnant » peut n'être que du hasard : "
                + OptimizerDefaults.SIGNIFICANCE_NOTE);
        notes.forEach(x -> md.add("- " + x));
        return String.join("\n", md) + "\n";
    }
}
