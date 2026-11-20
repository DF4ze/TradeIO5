package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape C3 (calage de l'exposition par profil sur le pire cas, marge mesurée) : JSON déterministe (hors durées), markdown. */
public record ExposureCalibrationReport(String definition, ExposureCalibrationConfig config, List<AssetCalibration> assets, List<String> notes) {

    public static final String DEFINITION = ParityReport.EXPOSURE_DEFINITION
            + " Drawdown = pire creux de la valeur du portefeuille (≤ 0) ; plafond lu dans ModulationDefaults, « tenu » = drawdown ≥ plafond ;"
            + " r(T) = drawdown réel / plafond (r ≤ 1 : plafond tenu). Gain en % du capital (cash non investi compté comme une perte).";

    /** Un test à la date T : niveau calé sans donnée postérieure à T, replay des 12 mois suivants. */
    public record TestPoint(LocalDate anchor, LocalDate testTo, boolean bear, double multiplier, String status, int calibWindows, double drawdownPct,
                            double ratio, boolean holds, double gainPct, double exposurePct) {
    }

    /** Une marge k : plafond de calage = plafond / k ; tenue du VRAI plafond sur les T. */
    public record MarginResult(double k, double capUsedPct, List<TestPoint> points, int tested, int holds, Double holdShare, Double ratioMedian,
                               Double ratioP90, Double ratioMax, int bearTested, int bearHolds, Double bearRatioMax, int otherTested, int otherHolds,
                               Double otherRatioMax) {
    }

    /** Calage sans marge sur tout le passé (fenêtres + historique complet) ; {@code worstWindow} = fenêtre qui fixe le drawdown au niveau retenu. */
    public record NoMargin(double multiplier, String status, String worstWindow, boolean worstWindowContainsBear) {
    }

    /** Niveau de C1 (4 blocs de 24 mois), rejoué directement. */
    public record C1Level(double multiplier, String status, double exposureBlocksPct, double gainMedianBlocksPct, Double fullDrawdownPct,
                          boolean fullHolds, Double equivalentAutonomyDays) {
    }

    public record Recommendation(double k, boolean sufficient, double multiplier, String status, double exposureBlocksPct, Double exposureFullPct,
                                 double gainMedianBlocksPct, double gainMedianVsC1Pt, Double fullDrawdownPct, boolean fullHolds,
                                 Double equivalentAutonomyDays, boolean autonomyInBounds) {
    }

    public record ProfileCalibration(String profile, double capPct, NoMargin noMargin, C1Level c1, List<MarginResult> margins,
                                     Recommendation recommendation) {
    }

    /** Pour une marge : plus mauvaise part de T tenus parmi les profils. */
    public record KSummary(double k, double minHoldShare, String worstProfile, boolean sufficient) {
    }

    public record Onboarding(Double meanMultiplier, double referenceBaseC1, Double loaderBase, int defaultAutonomyDays, Double onboardingBaseDefault,
                             Double multiplierAtDefault, double autonomyMinDays, double autonomyMaxDays, String note) {
    }

    public record Windows(int count, String first, String last, int containingBear, int overlappingBear, int truncated, int notReplayable) {
    }

    public record Multiplicity(int levels, int windows, int anchors, int margins, int profiles, long calibrations, long testReplays, long replays) {
    }

    public record AssetCalibration(String asset, String source, double cash0, double referenceBase, CandleFingerprint fingerprint, List<Double> grid,
                                   Windows windows, int anchors, int anchorsTested, int anchorsBear, List<ProfileCalibration> profiles,
                                   List<KSummary> kSummaries, Double recommendedK, String kRule, Onboarding onboarding, Multiplicity multiplicity,
                                   Long totalMs) {
    }

    public record Estimate(long worstCaseReplays, double replayMs, double sequentialMinutes) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de calage d'exposition non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        List<AssetCalibration> a = assets.stream().map(x -> new AssetCalibration(x.asset, x.source, x.cash0, x.referenceBase, x.fingerprint, x.grid,
                x.windows, x.anchors, x.anchorsTested, x.anchorsBear, x.profiles, x.kSummaries, x.recommendedK, x.kRule, x.onboarding, x.multiplicity,
                null)).toList();
        return new ExposureCalibrationReport(definition, config, a, notes).toJson();
    }

    private static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pct(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.0f %%", 100.0 * v);
    }

    private static String x(double v) {
        return String.format(Locale.ROOT, "×%.2f", v);
    }

    private static String frac(int n, int d) {
        return n + "/" + d;
    }

    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("**" + definition + "**");
        md.add("");
        md.add("## Synthèse profil × actif");
        md.add("| Actif | Profil | Plafond DD % | Niveau sans marge (×) | Part des T tenus (k = 1) | k recommandé | Niveau recommandé (×) | Expo moy. % (blocs C1 / hist.) | Gain médian % (blocs C1) | Écart au niveau C1 (pt) | DD hist. complet % | Plafond hist. |");
        md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (AssetCalibration a : assets) {
            for (ProfileCalibration p : a.profiles()) {
                Recommendation r = p.recommendation();
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s (%s) | %s | %s | %s (%s) | %s / %s | %s | %s | %s | %s |", a.asset(), p.profile(), f(p.capPct()),
                        x(p.noMargin().multiplier()), p.noMargin().status(), pct(p.margins().getFirst().holdShare()),
                        a.recommendedK() == null ? "aucun k ≤ " + f(config.margins().getLast()) : f(a.recommendedK()), x(r.multiplier()), r.status(),
                        f(r.exposureBlocksPct()), f(r.exposureFullPct()), f(r.gainMedianBlocksPct()), f(r.gainMedianVsC1Pt()), f(r.fullDrawdownPct()),
                        r.fullHolds() ? "tenu" : "DÉPASSÉ"));
            }
        }
        md.add("");
        for (AssetCalibration a : assets) {
            md.add("# " + a.asset());
            Windows w = a.windows();
            md.add(String.format(Locale.ROOT, "Capital %.0f USDC ; base de référence ×1 = %.2f ; source %s ; bougies %s ; %d fenêtres de %d mois (pas %d j, %s → %s), dont %d contiennent "
                            + "le bear %s → %s (%d en recouvrent ≥ %d j) : fenêtres qui se chevauchent, donc non indépendantes (elles servent à trouver le pire cas, pas à compter des "
                            + "événements) ; fenêtres tronquées écartées %d, non rejouables %d ; %d dates T (%d testées, %d dont le test recouvre le bear) ; %d rejeux.",
                    a.cash0(), a.referenceBase(), a.source(), a.fingerprint(), w.count(), config.windowMonths(), config.windowStepDays(), w.first(), w.last(),
                    w.containingBear(), config.bearFrom(), config.bearTo(), w.overlappingBear(), config.bearMinOverlapDays(), w.truncated(), w.notReplayable(),
                    a.anchors(), a.anchorsTested(), a.anchorsBear(), a.multiplicity().replays()));
            md.add(String.format(Locale.ROOT, "Multiplicité : %d niveaux × %d fenêtres, %d dates T × %d marges × %d profils = %d calages, %d replays de test.",
                    a.multiplicity().levels(), a.multiplicity().windows(), a.multiplicity().anchors(), a.multiplicity().margins(), a.multiplicity().profiles(),
                    a.multiplicity().calibrations(), a.multiplicity().testReplays()));
            md.add("");
            md.add("## " + a.asset() + " — marge k (plafond de calage = plafond / k ; part des T où le VRAI plafond tient)");
            md.add("| Profil | k | Plafond de calage % | Tient | Part | r médian | r P90 | r max | Bear : tient | Bear : r max | Hors bear : tient | Hors bear : r max |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (ProfileCalibration p : a.profiles()) {
                for (MarginResult m : p.margins()) {
                    md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |", p.profile(), f(m.k()), f(m.capUsedPct()),
                            frac(m.holds(), m.tested()), pct(m.holdShare()), f(m.ratioMedian()), f(m.ratioP90()), f(m.ratioMax()), frac(m.bearHolds(), m.bearTested()),
                            f(m.bearRatioMax()), frac(m.otherHolds(), m.otherTested()), f(m.otherRatioMax())));
                }
            }
            md.add("");
            md.add("Règle fixée avant lecture : " + a.kRule());
            md.add("| k | Pire part de T tenus (profils confondus) | Profil le plus mauvais | Suffit |");
            md.add("|---|---|---|---|");
            for (KSummary s : a.kSummaries()) {
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s |", f(s.k()), pct(s.minHoldShare()), s.worstProfile(), s.sufficient() ? "oui" : "non"));
            }
            md.add("");
            md.add("## " + a.asset() + " — r(T) à k = 1 (drawdown réel des 12 mois après T / plafond ; > 1 = plafond dépassé)");
            md.add("| T | Bear | " + String.join(" | ", a.profiles().stream().map(p -> p.profile() + " ×, DD %, r").toList()) + " |");
            md.add("|---|---|" + "---|".repeat(a.profiles().size()));
            List<TestPoint> first = a.profiles().getFirst().margins().getFirst().points();
            for (int i = 0; i < first.size(); i++) {
                StringBuilder row = new StringBuilder("| " + first.get(i).anchor() + " | " + (first.get(i).bear() ? "oui" : "") + " |");
                for (ProfileCalibration p : a.profiles()) {
                    List<TestPoint> pts = p.margins().getFirst().points();
                    TestPoint t = i < pts.size() ? pts.get(i) : null;
                    row.append(t == null ? " n/d |" : String.format(Locale.ROOT, " %s, %s, %s%s |", x(t.multiplier()), f(t.drawdownPct()), f(t.ratio()),
                            t.holds() ? "" : " ✗"));
                }
                md.add(row.toString());
            }
            md.add("");
            Onboarding o = a.onboarding();
            md.add("## " + a.asset() + " — équivalence avec l'onboarding");
            md.add(String.format(Locale.ROOT, "Multiplicateur moyen des zones %s ; base ×1 de C1 = %.2f ; base proposée à l'autonomie par défaut (%d j) = %s, soit ×%s ; base du chargeur = %s ; bornes d'autonomie %s à %s j. %s",
                    f(o.meanMultiplier()), o.referenceBaseC1(), o.defaultAutonomyDays(), f(o.onboardingBaseDefault()), f(o.multiplierAtDefault()), f(o.loaderBase()),
                    f(o.autonomyMinDays()), f(o.autonomyMaxDays()), o.note()));
            md.add("| Profil | Niveau C1 (×) | Autonomie équivalente C1 (j) | Niveau recommandé (×) | Autonomie équivalente recommandée (j) | Dans les bornes |");
            md.add("|---|---|---|---|---|---|");
            for (ProfileCalibration p : a.profiles()) {
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s |", p.profile(), x(p.c1().multiplier()), f(p.c1().equivalentAutonomyDays()),
                        x(p.recommendation().multiplier()), f(p.recommendation().equivalentAutonomyDays()), p.recommendation().autonomyInBounds() ? "oui" : "NON"));
            }
            md.add("");
        }
        notes.forEach(n -> md.add("- " + n));
        return String.join("\n", md) + "\n";
    }
}
