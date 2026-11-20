package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape C2 (politique d'exposition contre exposition constante, à plafond égal) : JSON déterministe (hors durées), markdown. */
public record ExposurePolicyReport(String definition, ExposurePolicyConfig config, List<AssetPolicy> assets, List<String> notes) {

    public static final String DEFINITION = ParityReport.EXPOSURE_DEFINITION
            + " Plafond de drawdown du profil lu dans ModulationDefaults ; drawdown = pire creux de la valeur du portefeuille (≤ 0), « tenu » = drawdown ≥ plafond."
            + " Gain en % du capital (cash non investi compté comme une perte).";

    /** Réglage retenu et sa mesure sur le bloc de test. {@code bullMax} / {@code bearMax} nuls pour la constante. */
    public record Pick(Double bullMax, Double bearMax, double multiplier, String status, Double tuneMedianGain, Double tuneWorstDrawdown,
                       Line test, boolean holds) {
    }

    public record Rotation(int testBlock, String testLabel, List<Integer> tuneBlocks, Pick constant, Pick policy, double deltaGain) {
    }

    public record FullHistory(Pick constant, Pick policy) {
    }

    /** Verdict d'un profil pour un actif. */
    public record ProfileResult(String profile, double capPct, List<Rotation> rotations, FullHistory full, String verdict, int wins,
                                double medianDeltaGain, boolean capFail, int policyBreaches, int constantBreaches, boolean stable,
                                String modalBounds, int modalCount, String reason) {
    }

    public record Multiplicity(int constantGridSize, int policyCombos, int rotations, int profiles, long selections, long replays) {
    }

    public record AssetPolicy(String asset, String source, double cash0, double referenceBase, CandleFingerprint fingerprint, List<String> blocks,
                              List<ProfileResult> profiles, Multiplicity multiplicity, Long totalMs) {
    }

    public record Estimate(long worstCaseReplays, double replayMs, double sequentialMinutes) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de politique d'exposition non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        List<AssetPolicy> a = assets.stream().map(x -> new AssetPolicy(x.asset, x.source, x.cash0, x.referenceBase, x.fingerprint, x.blocks,
                x.profiles, x.multiplicity, null)).toList();
        return new ExposurePolicyReport(definition, config, a, notes).toJson();
    }

    private static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String m(double v) {
        return String.format(Locale.ROOT, "×%.2f", v);
    }

    private static String line(Pick p) {
        return String.format(Locale.ROOT, "%s | %s | %s | %s | %d / %d | %s", f(p.test().pnlPct()), f(p.test().maxDrawdownPct()),
                p.holds() ? "oui" : "NON", f(p.test().avgExposurePct()), p.test().buys(), p.test().sells(), p.status());
    }

    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("**" + definition + "**");
        md.add("");
        md.add("## Synthèse profil × actif");
        md.add("| Actif | Profil | Plafond DD % | Verdict | Rotations gagnées | Médiane Δ gain (pt) | Plafond (blocs de test dépassés : politique / constante) | Réglages stables (bornes modales) | Raison |");
        md.add("|---|---|---|---|---|---|---|---|---|");
        for (AssetPolicy a : assets) {
            for (ProfileResult p : a.profiles()) {
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %d/%d | %s | %d / %d%s | %s (%s %d/%d) | %s |", a.asset(), p.profile(), f(p.capPct()),
                        p.verdict(), p.wins(), p.rotations().size(), f(p.medianDeltaGain()), p.policyBreaches(), p.constantBreaches(),
                        p.capFail() ? " (politique pire)" : "", p.stable() ? "oui" : "NON", p.modalBounds(), p.modalCount(), p.rotations().size(),
                        p.reason()));
            }
        }
        md.add("");
        for (AssetPolicy a : assets) {
            md.add("# " + a.asset());
            md.add(String.format(Locale.ROOT, "Capital %.0f USDC ; base de référence %.2f ; source %s ; bougies %s ; blocs %s ; %d rejeux ; multiplicité : %d multiplicateurs (constante) "
                            + "+ %d combinaisons (politique) × %d rotations × %d profils = %d sélections.", a.cash0(), a.referenceBase(), a.source(), a.fingerprint(),
                    a.blocks(), a.multiplicity().replays(), a.multiplicity().constantGridSize(), a.multiplicity().policyCombos(),
                    a.multiplicity().rotations(), a.multiplicity().profiles(), a.multiplicity().selections()));
            md.add("");
            for (ProfileResult p : a.profiles()) {
                md.add("## " + a.asset() + " — " + p.profile() + " (plafond " + f(p.capPct()) + " %) — " + p.verdict());
                md.add("| Test | Réglage constante | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Réglage politique (bornes Bull / Bear, mult.) | Gain % | DD % | Tenu | Expo % | Achats / ventes | Statut | Δ gain (pt) |");
                md.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
                for (Rotation r : p.rotations()) {
                    md.add(String.format(Locale.ROOT, "| %s | %s | %s | Bull ≤ %s / Bear ≤ %s, %s | %s | %s |", r.testLabel(), m(r.constant().multiplier()),
                            line(r.constant()), f(r.policy().bullMax()), f(r.policy().bearMax()), m(r.policy().multiplier()), line(r.policy()),
                            f(r.deltaGain())));
                }
                md.add("");
                if (p.full() != null) {
                    md.add(String.format(Locale.ROOT, "Historique complet (lecture seule, hors verdict) : constante %s gain %s %% DD %s %% expo %s %% (%s) ; politique Bull ≤ %s / Bear ≤ %s, %s gain %s %% DD %s %% expo %s %% (%s).",
                            m(p.full().constant().multiplier()), f(p.full().constant().test().pnlPct()), f(p.full().constant().test().maxDrawdownPct()),
                            f(p.full().constant().test().avgExposurePct()), p.full().constant().holds() ? "plafond tenu" : "plafond DÉPASSÉ",
                            f(p.full().policy().bullMax()), f(p.full().policy().bearMax()), m(p.full().policy().multiplier()),
                            f(p.full().policy().test().pnlPct()), f(p.full().policy().test().maxDrawdownPct()), f(p.full().policy().test().avgExposurePct()),
                            p.full().policy().holds() ? "plafond tenu" : "plafond DÉPASSÉ"));
                    md.add("");
                }
            }
        }
        notes.forEach(n -> md.add("- " + n));
        return String.join("\n", md) + "\n";
    }
}
