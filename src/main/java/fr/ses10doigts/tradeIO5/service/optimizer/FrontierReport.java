package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Band;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Calib;
import fr.ses10doigts.tradeIO5.service.optimizer.ParityReport.Metrics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape C1 (frontière exposition → gain / drawdown) : JSON déterministe (hors durées) et tableaux markdown. */
public record FrontierReport(String definition, FrontierConfig config, List<AssetFrontier> assets, List<String> notes) {

    /** Un niveau (multiplicateur) sur une plage : preset, DCA égal calé, bande de bruit. */
    public record Level(double multiplier, boolean valid, Metrics preset, double realized, double latent, Metrics equalDca, Calib calib,
                        Band band, double deltaGain, double deltaDrawdown, Double deltaRatio, boolean gainWin, boolean gainOutOfBand,
                        boolean dcaReached) {
        static Level invalid(double multiplier) {
            return new Level(multiplier, false, null, 0, 0, null, null, null, 0, 0, null, false, false, false);
        }
    }

    public record Range(int index, String label, LocalDate from, LocalDate to, boolean valid, List<Level> levels) {
    }

    /** Synthèse d'un niveau sur les blocs rejouables (hors historique complet). */
    public record Summary(double multiplier, double exposurePct, double medianGain, double worstDrawdown, Double medianRatio, int blocksWon,
                          int validBlocks, double medianDeltaGain, double worstDeltaDrawdown, boolean concluded, boolean passes, boolean saturated,
                          Double gainElasticity, Double drawdownElasticity) {
    }

    /** Niveau d'un profil : exposition maximale respectant le plafond (interpolée entre deux points de grille, ou point de grille). */
    public record ProfileLevel(String profile, double capPct, String status, Double multiplier, Double exposurePct, Double medianGain,
                               Double worstDrawdown, int blocksWon, double medianDeltaGain, double worstDeltaDrawdown, String verdict, String note) {
    }

    public record AssetFrontier(String asset, String source, double cash0, double referenceBase, CandleFingerprint fingerprint,
                                List<Double> multipliers, boolean extended, List<Range> blocks, Range full, List<Summary> summaries,
                                Double saturationFrom, Double peakRatioMultiplier, List<ProfileLevel> profiles, String verdict, String verdictNote,
                                long replays, Long totalMs) {
    }

    public static final String DEFINITION = ParityReport.EXPOSURE_DEFINITION
            + " Levier unique : multiplicateur du montant de base du lien (preset, Trend, modulation inchangés).";

    public record Estimate(long worstCaseReplays, double replayMs, double sequentialMinutes) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de frontière non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        List<AssetFrontier> a = assets.stream().map(x -> new AssetFrontier(x.asset, x.source, x.cash0, x.referenceBase, x.fingerprint,
                x.multipliers, x.extended, x.blocks, x.full, x.summaries, x.saturationFrom, x.peakRatioMultiplier, x.profiles, x.verdict,
                x.verdictNote, x.replays, null)).toList();
        return new FrontierReport(definition, config, a, notes).toJson();
    }

    private static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String x(double m) {
        return String.format(Locale.ROOT, "×%s", m == Math.rint(m) ? String.valueOf((long) m) : String.valueOf(m));
    }

    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("**" + definition + "**");
        md.add("");
        for (AssetFrontier a : assets) {
            md.add("# " + a.asset() + " — " + a.verdict());
            md.add(String.format(Locale.ROOT, "Capital %.0f USDC ; base de référence %.2f ; source %s ; bougies %s ; %d rejeux ; grille %s%s.", a.cash0(),
                    a.referenceBase(), a.source(), a.fingerprint(), a.replays(), a.multipliers(), a.extended() ? " (élargie)" : ""));
            md.add(a.verdictNote());
            md.add("");
            List<Range> ranges = new ArrayList<>(a.blocks());
            ranges.add(a.full());
            for (Range r : ranges) {
                md.add("### " + a.asset() + " — " + r.label() + (r.index() < 0 ? " (hors verdict)" : ""));
                if (!r.valid()) {
                    md.add("écarté : plage non rejouable");
                    md.add("");
                    continue;
                }
                md.add("| Mult. | Expo % | Gain / capital total % | Gain / capital engagé % | DD % | Gain/DD | DCA égal gain % | DCA égal DD % | Écart expo DCA (pt, tenu) | Bande P80 gain % | Δ gain (pt) | Δ DD (pt) | Réalisé $ | Latent $ | Achats / ventes |");
                md.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
                for (Level l : r.levels()) {
                    if (!l.valid()) {
                        md.add("| " + x(l.multiplier()) + " | non rejouable |");
                        continue;
                    }
                    md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %s (%s) | %s | %s | %s | %.1f | %.1f | %d / %d |",
                            x(l.multiplier()), f(l.preset().exposurePct()), f(l.preset().gainPct()), f(l.preset().gainOnEngagedPct()),
                            f(l.preset().maxDrawdownPct()), f(l.preset().gainOverDd()), f(l.equalDca().gainPct()), f(l.equalDca().maxDrawdownPct()),
                            f(l.calib().errorPts()), l.dcaReached() ? "oui" : "NON : inatteignable", f(l.band().gainP80()), f(l.deltaGain()),
                            f(l.deltaDrawdown()), l.realized(), l.latent(), l.preset().buys(), l.preset().sells()));
                }
                md.add("");
            }
            md.add("### " + a.asset() + " — synthèse par niveau (4 blocs)");
            md.add("| Mult. | Expo moy. % | Gain médian % | Pire DD % | Gain/DD médian | Blocs gagnés | Médiane Δ gain | Pire Δ DD | Règle | Saturé | Élasticité gain | Élasticité DD |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (Summary s : a.summaries()) {
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %d/%d | %s | %s | %s | %s | %s | %s |", x(s.multiplier()), f(s.exposurePct()),
                        f(s.medianGain()), f(s.worstDrawdown()), f(s.medianRatio()), s.blocksWon(), s.validBlocks(), f(s.medianDeltaGain()),
                        f(s.worstDeltaDrawdown()), !s.concluded() ? "non conclu (DCA inatteignable)" : s.passes() ? "OK" : "échec",
                        s.saturated() ? "oui" : "non", f(s.gainElasticity()), f(s.drawdownElasticity())));
            }
            md.add("");
            md.add(String.format(Locale.ROOT, "Saturation de l'exposition à partir de : %s ; gain/|DD| médian maximal à : %s.",
                    a.saturationFrom() == null ? "aucune (grille)" : x(a.saturationFrom()),
                    a.peakRatioMultiplier() == null ? "n/d" : x(a.peakRatioMultiplier())));
            md.add("");
            md.add("### " + a.asset() + " — niveau par profil (interpolation linéaire entre points de grille)");
            md.add("| Profil | Plafond DD % | Statut | Mult. | Expo max % | Gain médian % | Pire DD % | Blocs gagnés | Médiane Δ gain | Pire Δ DD | Verdict | Note |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|");
            for (ProfileLevel p : a.profiles()) {
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %d | %s | %s | %s | %s |", p.profile(), f(p.capPct()), p.status(),
                        p.multiplier() == null ? "n/d" : x(Math.round(p.multiplier() * 100) / 100.0), f(p.exposurePct()), f(p.medianGain()),
                        f(p.worstDrawdown()), p.blocksWon(), f(p.medianDeltaGain()), f(p.worstDeltaDrawdown()), p.verdict(), p.note()));
            }
            md.add("");
        }
        notes.forEach(n -> md.add("- " + n));
        return String.join("\n", md) + "\n";
    }
}
