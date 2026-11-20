package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Reading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rapport de l'étape B (optimisation par blocs). Rien n'est appliqué ni persisté. Mêmes entrées + même empreinte de bougies ⇒ JSON identique
 * hors {@code timings}.
 */
public record TuneReport(Inputs inputs, List<BlockInfo> blocks, List<Rotation> rotations, Aggregate aggregate, Challenger challenger,
                         Multiplicity multiplicity, List<String> notes, Timings timings) {

    public record Inputs(String asset, String source, double cash0, LocalDate lastEnd, int blockMonths, CandleFingerprint fingerprint,
                         int maxPasses, double minGainPoints, double plateauRatio, double exposureGuardFactor, int refinePoints,
                         double widenCeiling, List<String> bullKeys, List<String> bearKeys, List<String> outOfScope, List<String> silent,
                         Estimate estimate) {
    }

    /** Estimation avant lancement : rejeux au pire × durée supposée. */
    public record Estimate(long worstCaseReplays, double replayMs, double sequentialMinutes) {
    }

    public record BlockInfo(int index, LocalDate from, LocalDate to, boolean valid, String note) {
    }

    /** Un réglage modifié (avant → après). */
    public record Change(String role, String key, String before, String after) {
    }

    /** Un bloc mesuré pour les trois lignes côte à côte. {@code dcaPnlPct} = DCA fixe (même cash) en % du capital. */
    public record Compare(int block, Reading preset, Reading tuned, double presetPnlPct, double tunedPnlPct, double dcaPnlPct,
                          double deltaVsPreset, double deltaVsDca, boolean exposureOk) {
    }

    public record Rotation(int testBlock, List<Integer> tuneBlocks, List<Change> changes, List<BlockTuner.Decision> decisions, int passes,
                           Compare test, boolean beatsPreset, boolean beatsDca, boolean adoptable, long replays, int valuesTested) {
    }

    /** Verdict : {@code ADOPTABLE} ou {@code NUL} (ou {@code NON_EVALUABLE} si moins de 4 rotations). */
    public record Aggregate(int rotations, int beatsPreset, int beatsDca, int beatsBoth, double medianDeltaVsPreset, double medianDeltaVsDca,
                            double meanDeltaVsPreset, double meanDeltaVsDca, boolean exposureOkEverywhere, String verdict, List<String> reasons,
                            List<Inversion> inversions) {
    }

    /** Un réglage qui ne va pas dans le même sens d'une rotation à l'autre (ou qui n'est retenu que dans certaines). */
    public record Inversion(String role, String key, List<String> perRotation, String kind) {
    }

    /** Preset unique réglé sur les blocs valides (non testé) ; exports au format de l'Expert ({@code RainbowPresetRequest}). */
    public record Challenger(List<Change> changes, List<BlockTuner.Decision> decisions, int passes, List<Compare> inSample, Reading presetFull,
                             Reading challengerFull, double presetFullPnlPct, double challengerFullPnlPct, double dcaFullPnlPct,
                             Map<String, Object> bullExport, Map<String, Object> bearExport) {
    }

    public record Multiplicity(int parameters, int rotations, int passesRun, int valuesTested, long replays, int ignoredValues,
                               int guardRejections) {
    }

    public record Timings(long totalMs) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de réglage non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        return new TuneReport(inputs, blocks, rotations, aggregate, challenger, multiplicity, notes, null).toJson();
    }

    /** JSON au format de l'Expert (corps de {@code RainbowPresetRequest}) : à charger tel quel dans le simulateur. */
    public static String exportJson(Map<String, Object> export) {
        try {
            return MAPPER.writeValueAsString(export);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Export non sérialisable", e);
        }
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%+.2f", v);
    }

    private static String n(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String yn(boolean b) {
        return b ? "oui" : "non";
    }

    private static String row(String label, Reading r, double pnlPct) {
        return "| " + label + " | " + f(pnlPct) + " | " + n(r.realized()) + " | " + n(r.bullEndPosition()) + " | " + n(r.maxDrawdownPct()) + " | "
                + n(r.avgExposurePct()) + " | " + r.buys() + " / " + r.sells() + " |";
    }

    private static void compareTable(List<String> md, Compare c) {
        md.add("| Ligne | PnL % capital | Réalisé $ | Latent fin bull $ | Drawdown % | Expo moy. % | Achats / ventes |");
        md.add("|---|---|---|---|---|---|---|");
        md.add(row("Preset en place", c.preset(), c.presetPnlPct()));
        md.add(row("Réglé", c.tuned(), c.tunedPnlPct()));
        md.add("| DCA fixe | " + f(c.dcaPnlPct()) + " | — | — | — | — | — |");
        md.add("");
        md.add("Δ vs preset en place : " + f(c.deltaVsPreset()) + " pt ; Δ vs DCA fixe : " + f(c.deltaVsDca()) + " pt ; exposition dans la borne : "
                + yn(c.exposureOk()) + ".");
    }

    private static void changes(List<String> md, List<Change> changes) {
        if (changes.isEmpty()) {
            md.add("Aucun réglage modifié.");
            return;
        }
        md.add("| Jeu | Paramètre | Avant | Après |");
        md.add("|---|---|---|---|");
        changes.forEach(c -> md.add("| " + c.role() + " | " + c.key() + " | " + c.before() + " | " + c.after() + " |"));
    }

    private static void decisions(List<String> md, List<BlockTuner.Decision> decisions) {
        md.add("| Passe | Jeu | Paramètre | Avant → essayé | Retenu | Gain moy. pt | Blocs ↑ | Plateau | Bord | Motif |");
        md.add("|---|---|---|---|---|---|---|---|---|---|");
        decisions.forEach(d -> md.add("| " + d.pass() + " | " + d.role() + " | " + d.key() + " | " + d.before() + " → " + d.after() + " | " + yn(d.kept())
                + " | " + f(d.meanDelta()) + " | " + d.blocksImproved() + "/" + d.blocks() + " | " + d.plateau() + " | " + d.edge() + " | " + d.reason() + " |"));
    }

    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("# Étape B — optimisation par blocs, " + inputs.asset());
        md.add("");
        md.add("Capital " + n(inputs.cash0()) + " USDC, source " + inputs.source() + ", empreinte " + inputs.fingerprint().checksum() + " ("
                + inputs.fingerprint().count() + " bougies, " + inputs.fingerprint().firstDay() + " → " + inputs.fingerprint().lastDay() + "). Blocs de "
                + inputs.blockMonths() + " mois non chevauchants ; réglage sur les blocs restants, test sur le bloc écarté, jamais l'inverse.");
        md.add("Configuration : " + inputs.maxPasses() + " passes max, gain moyen minimal " + inputs.minGainPoints() + " pt de capital, plateau ≥ "
                + inputs.plateauRatio() + " du meilleur, EXPOSURE_GUARD ×" + inputs.exposureGuardFactor() + ", affinage " + inputs.refinePoints()
                + " points, élargissement jusqu'à " + inputs.widenCeiling() + " au plus.");
        md.add("Estimation avant lancement : " + estimateLine());
        md.add("");
        md.add("## Blocs");
        md.add("| # | Plage | Retenu | Note |");
        md.add("|---|---|---|---|");
        blocks.forEach(b -> md.add("| " + b.index() + " | " + b.from() + " → " + b.to() + " | " + yn(b.valid()) + " | " + b.note() + " |"));
        md.add("");
        md.add("## Périmètre");
        md.add("- Balayés : Bull " + inputs.bullKeys() + " ; Bear " + inputs.bearKeys() + ".");
        md.add("- Hors B1 (taille d'achat = exposition = L2), ignorés : " + inputs.outOfScope() + ".");
        md.add("- Muets, non touchés : " + inputs.silent() + ".");
        md.add("");
        md.add("## Rotations");
        for (Rotation r : rotations) {
            md.add("");
            md.add("### Rotation " + (r.testBlock() + 1) + " — test sur le bloc " + r.testBlock() + ", réglage sur les blocs " + r.tuneBlocks() + " (" + r.passes()
                    + " passe(s), " + r.valuesTested() + " valeurs, " + r.replays() + " rejeux)");
            md.add("");
            md.add("Preset réglé :");
            changes(md, r.changes());
            md.add("");
            md.add("Bloc de test :");
            md.add("");
            compareTable(md, r.test());
            md.add("");
            md.add("Bat le preset : " + yn(r.beatsPreset()) + " ; bat le DCA fixe : " + yn(r.beatsDca()) + " ; rotation gagnante : " + yn(r.adoptable()) + ".");
            md.add("");
            md.add("<details><summary>Descente (une ligne par paramètre et par passe)</summary>");
            md.add("");
            decisions(md, r.decisions());
            md.add("");
            md.add("</details>");
        }
        md.add("");
        md.add("## Agrégat des rotations");
        md.add("| Rotation | Δ vs preset (pt) | Δ vs DCA fixe (pt) | Expo réglé % | Expo preset % | Drawdown réglé % | Bat preset | Bat DCA | Borne expo |");
        md.add("|---|---|---|---|---|---|---|---|---|");
        for (Rotation r : rotations) {
            Compare t = r.test();
            md.add("| test bloc " + r.testBlock() + " | " + f(t.deltaVsPreset()) + " | " + f(t.deltaVsDca()) + " | " + n(t.tuned().avgExposurePct()) + " | "
                    + n(t.preset().avgExposurePct()) + " | " + n(t.tuned().maxDrawdownPct()) + " | " + yn(r.beatsPreset()) + " | " + yn(r.beatsDca()) + " | "
                    + yn(t.exposureOk()) + " |");
        }
        md.add("");
        md.add("Bat le preset : " + aggregate.beatsPreset() + "/" + aggregate.rotations() + " ; bat le DCA fixe : " + aggregate.beatsDca() + "/"
                + aggregate.rotations() + " ; les deux : " + aggregate.beatsBoth() + "/" + aggregate.rotations() + ". Médiane Δ vs preset " + f(aggregate.medianDeltaVsPreset())
                + " pt, vs DCA " + f(aggregate.medianDeltaVsDca()) + " pt ; moyenne " + f(aggregate.meanDeltaVsPreset()) + " / " + f(aggregate.meanDeltaVsDca()) + " pt.");
        md.add("");
        md.add("**Verdict : " + aggregate.verdict() + "**");
        aggregate.reasons().forEach(x -> md.add("- " + x));
        md.add("");
        md.add("### Réglages qui s'inversent ou ne tiennent pas d'une rotation à l'autre");
        if (aggregate.inversions().isEmpty()) {
            md.add("Aucun.");
        } else {
            md.add("| Jeu | Paramètre | Nature | Par rotation (avant → après) |");
            md.add("|---|---|---|---|");
            aggregate.inversions().forEach(i -> md.add("| " + i.role() + " | " + i.key() + " | " + i.kind() + " | " + String.join(" ; ", i.perRotation()) + " |"));
        }
        md.add("");
        md.add("## Challenger unique (réglé sur les blocs valides, NON TESTÉ : sa crédibilité vient des rotations)");
        md.add("");
        changes(md, challenger.changes());
        md.add("");
        md.add("Mesure sur les blocs de réglage (dans l'échantillon, à ne pas lire comme une preuve) :");
        for (Compare c : challenger.inSample()) {
            md.add("");
            md.add("Bloc " + c.block() + " :");
            md.add("");
            compareTable(md, c);
        }
        md.add("");
        md.add("<details><summary>Descente du challenger</summary>");
        md.add("");
        decisions(md, challenger.decisions());
        md.add("");
        md.add("</details>");
        md.add("");
        md.add("Export au format de l'Expert (`RainbowPresetRequest`) : `tune-challenger-bull.json` et `tune-challenger-bear.json` (jeu Bull = Rainbow UP, jeu Bear = Rainbow DOWN), à charger puis rejouer à la main.");
        md.add("");
        md.add("## Multiplicité");
        md.add("- Paramètres balayés : " + multiplicity.parameters() + " (Bull + Bear) ; rotations : " + multiplicity.rotations() + " + 1 challenger ; passes effectuées au total : "
                + multiplicity.passesRun() + ".");
        md.add("- Valeurs testées : " + multiplicity.valuesTested() + " ; rejeux : " + multiplicity.replays() + " ; valeurs ignorées (domaine invalide) : "
                + multiplicity.ignoredValues() + " ; rejets EXPOSURE_GUARD (bloc par bloc) : " + multiplicity.guardRejections() + ".");
        md.add("");
        md.add("## Rappel : historique complet (lecture seule, contient les blocs de réglage)");
        md.add("| Ligne | PnL % capital | Expo moy. % | Drawdown % |");
        md.add("|---|---|---|---|");
        md.add("| Preset en place | " + f(challenger.presetFullPnlPct()) + " | " + n(challenger.presetFull().avgExposurePct()) + " | " + n(challenger.presetFull().maxDrawdownPct()) + " |");
        md.add("| Challenger | " + f(challenger.challengerFullPnlPct()) + " | " + n(challenger.challengerFull().avgExposurePct()) + " | " + n(challenger.challengerFull().maxDrawdownPct()) + " |");
        md.add("| DCA fixe | " + f(challenger.dcaFullPnlPct()) + " | — | — |");
        md.add("");
        md.add("## Notes");
        notes.forEach(x -> md.add("- " + x));
        return String.join("\n", md) + "\n";
    }

    private String estimateLine() {
        return inputs.estimate().worstCaseReplays() + " rejeux au pire × " + n(inputs.estimate().replayMs()) + " ms = " + n(inputs.estimate().sequentialMinutes())
                + " min en série.";
    }
}
