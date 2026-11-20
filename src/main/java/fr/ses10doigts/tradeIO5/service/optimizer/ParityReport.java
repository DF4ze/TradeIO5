package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape B' (mesure à exposition égale) : JSON déterministe (hors durées) et tableaux markdown. */
public record ParityReport(String definition, ParityConfig config, List<AssetReport> assets, List<String> notes) {

    /** Définition unique de l'exposition, celle déjà rapportée pour le preset (étape A) ; écrite en tête du rapport. */
    public static final String EXPOSURE_DEFINITION = "Exposition = valeur de la position / valeur totale du portefeuille, moyenne sur les jours"
            + " de la plage (celle de SensitivityEvaluator.cycle, étape A). Même mesure pour le preset et le DCA.";

    public record Estimate(long worstCaseReplays, double replayMs, double sequentialMinutes) {
    }

    /** {@code gainOnEngagedPct} = gain / exposition moyenne ; {@code gainOverDd} = gain % / |drawdown| % (null si non défini). */
    public record Metrics(double gainPct, Double gainOnEngagedPct, double exposurePct, double maxDrawdownPct, Double gainOverDd, long buys,
                          long sells) {
    }

    public record Calib(double base, double exposurePct, double targetPct, double errorPts, boolean reached, int replays) {
    }

    /** Gains et rapports du DCA calé pour chaque décalage de début (null = plage non rejouable). */
    public record Band(List<Integer> shifts, List<Double> gains, List<Double> ratios, Double gainMin, Double gainP20, Double gainMedian,
                       Double gainP80, Double gainMax, Double ratioP80) {
        static Band of(List<Integer> shifts, List<Double> gains, List<Double> ratios, double q) {
            return new Band(shifts, gains, ratios, ExposureParity.percentile(gains, 0), ExposureParity.percentile(gains, 0.2),
                    ExposureParity.percentile(gains, 0.5), ExposureParity.percentile(gains, q), ExposureParity.percentile(gains, 1),
                    ExposureParity.percentile(ratios, q));
        }
    }

    public record BlockResult(int index, String label, LocalDate from, LocalDate to, boolean valid, String note, Metrics preset,
                              double realized, double latent, Metrics equalDca, Calib calib, Band band, Metrics usualDca, Metrics challenger,
                              double deltaGain, Double deltaRatio, boolean ddOk, boolean gainWin, boolean gainOutOfBand, boolean ratioWin,
                              boolean ratioOutOfBand, boolean pass, String failure) {
        static BlockResult invalid(int index, String label, String note) {
            return new BlockResult(index, label, null, null, false, note, null, 0, 0, null, null, null, null, null, 0, null, false, false,
                    false, false, false, false, "");
        }
    }

    public record Verdict(String decision, int passes, int validBlocks, double medianDeltaGain, int failGain, int failDrawdown, int failNoise,
                          String failureSide) {
    }

    public record AssetReport(String asset, String source, double cash0, CandleFingerprint fingerprint, List<BlockResult> blocks,
                              BlockResult full, Verdict verdict, boolean challengerAvailable, List<Integer> shifts, long replays,
                              Long totalMs) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de parité non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        List<AssetReport> a = assets.stream().map(x -> new AssetReport(x.asset, x.source, x.cash0, x.fingerprint, x.blocks, x.full, x.verdict,
                x.challengerAvailable, x.shifts, x.replays, null)).toList();
        return new ParityReport(definition, config, a, notes).toJson();
    }

    private static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.2f", v);
    }

    public String toMarkdown() {
        List<String> md = new ArrayList<>();
        md.add("**" + definition + "**");
        md.add("");
        for (AssetReport a : assets) {
            md.add("## " + a.asset() + " — verdict : " + a.verdict().decision());
            md.add(String.format(Locale.ROOT, "Capital %.0f USDC ; source %s ; bougies %s ; %d rejeux ; décalages (j) %s.", a.cash0(), a.source(),
                    a.fingerprint(), a.replays(), a.shifts()));
            md.add("");
            md.add("| Bloc | Preset gain % | Expo % | DD % | Gain/DD | DCA égal gain % | Expo % | DD % | Gain/DD | Montant | Écart expo (pt) | Bande gain % P20 / méd / P80 (min–max) | DCA habituel gain % (expo) | Challenger B gain % (expo) | Δ gain | Δ gain/DD | Bloc |");
            md.add("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
            List<BlockResult> rows = new ArrayList<>(a.blocks());
            rows.add(a.full());
            for (BlockResult b : rows) {
                if (!b.valid()) {
                    md.add("| " + b.label() + " | " + b.note() + " |");
                    continue;
                }
                md.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s / %s / %s (%s–%s) | %s (%s) | %s | %s | %s | %s |",
                        b.label() + " (" + b.from() + " → " + b.to() + ")", f(b.preset().gainPct()), f(b.preset().exposurePct()),
                        f(b.preset().maxDrawdownPct()), f(b.preset().gainOverDd()), f(b.equalDca().gainPct()), f(b.equalDca().exposurePct()),
                        f(b.equalDca().maxDrawdownPct()), f(b.equalDca().gainOverDd()), f(b.calib().base()), f(b.calib().errorPts()),
                        f(b.band().gainP20()), f(b.band().gainMedian()), f(b.band().gainP80()), f(b.band().gainMin()), f(b.band().gainMax()),
                        f(b.usualDca().gainPct()), f(b.usualDca().exposurePct()),
                        b.challenger() == null ? "n/d" : f(b.challenger().gainPct()) + " (" + f(b.challenger().exposurePct()) + ")",
                        f(b.deltaGain()), f(b.deltaRatio()), b.index() < 0 ? "(hors verdict)" : b.pass() ? "OK" : "échec : " + b.failure()));
            }
            md.add("");
            md.add("| Bloc | Gain / expo (preset) | Gain / expo (DCA égal) | Réalisé $ | Latent $ | Achats / ventes preset | Achats DCA égal | Calibrage (rejeux, tenu) |");
            md.add("|---|---|---|---|---|---|---|---|");
            for (BlockResult b : rows) {
                if (b.valid()) {
                    md.add(String.format(Locale.ROOT, "| %s | %s | %s | %.1f | %.1f | %d / %d | %d | %d, %s |", b.label(),
                            f(b.preset().gainOnEngagedPct()), f(b.equalDca().gainOnEngagedPct()), b.realized(), b.latent(), b.preset().buys(),
                            b.preset().sells(), b.equalDca().buys(), b.calib().replays(), b.calib().reached() ? "oui" : "NON"));
                }
            }
            Verdict v = a.verdict();
            md.add("");
            md.add(String.format(Locale.ROOT, "Verdict **%s** : %d bloc(s) sur %d satisfont la règle ; médiane Δ gain %s pt ; échecs : gain %d, drawdown %d, bruit %d ; côté de l'échec : %s.",
                    v.decision(), v.passes(), v.validBlocks(), f(v.medianDeltaGain()), v.failGain(), v.failDrawdown(), v.failNoise(), v.failureSide()));
            md.add("");
        }
        notes.forEach(n -> md.add("- " + n));
        return String.join("\n", md) + "\n";
    }
}
