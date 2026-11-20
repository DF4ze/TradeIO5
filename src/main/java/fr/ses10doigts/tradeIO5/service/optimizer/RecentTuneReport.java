package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.SensitivityEvaluator.Line;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rapport de l'étape D0 (réglage récent persistant ?). Déterministe : mêmes entrées + même empreinte de bougies + même graine ⇒ JSON
 * identique à l'octet hors {@code timings}.
 */
public record RecentTuneReport(Inputs inputs, Estimate estimate, List<TPoint> points, Verdict verdict, List<ParamStat> frequency,
                               ZoneStat zones, List<AComparison> stepA, Latest latest, Multiplicity multiplicity, List<String> notes,
                               Timings timings) {

    /** Hors reproductibilité : durée mesurée. */
    public record Timings(long totalMs) {
    }

    public record Inputs(String asset, String source, double cash0, LocalDate firstT, LocalDate lastT, int stepMonths, int tuneMonths,
                         List<Integer> horizons, int rounds, int trialsPerSearch, double exposureGuardFactor, int perturbations,
                         long seed, CandleFingerprint fingerprint, int freeParamsPerSet, double minShare) {
    }

    /** Coût avant lancement : rejeux au pire × durée d'un rejeu. */
    public record Estimate(int dates, int searches, long worstSearchReplays, long serialReplays, double configuredMs, double measuredMs,
                           int pool, double minutes, double minutesAtConfiguredMs) {
    }

    /** Un réglage déplacé : valeurs avant / après (libellés) et déplacement normalisé dans la plage (NaN pour un interrupteur / mode). */
    public record Change(String role, String key, String before, String after, double move) {
    }

    public record SearchInfo(int round, String role, long trials, long replays, long guardRejections, int sobolSamples, boolean adopted,
                             double startScore, double chosenScore, String reason, Map<String, Integer> vetoes) {
    }

    /** Dans l'échantillon (vue de réglage) : gel de chaque jeu et cycle réel, preset en place puis réglé. */
    public record InSample(double bullGelPreset, double bullGelTuned, double bearGelPreset, double bearGelTuned, double cyclePnlPreset,
                           double cyclePnlTuned, double exposurePreset, double exposureTuned, double drawdownPreset, double drawdownTuned) {
    }

    /** Test en avançant sur [T, T + horizon] : preset en place, réglé, DCA à exposition égale, médiane de perturbations. */
    public record Forward(int horizonMonths, String phase, double priceChangePct, Line preset, Line tuned, Line dcaEqual, double dcaEqualBase,
                          boolean dcaEqualCalibrated, double deltaPts, Double deltaPctOfPreset, double deltaVsDcaEqualPts,
                          double randomMedianPnl, double randomMinPnl, double randomMaxPnl, int randomAccepted, int randomDraws,
                          boolean beatsPreset, boolean beatsRandom, boolean beatsDcaEqual) {
    }

    public record TPoint(LocalDate t, LocalDate tuneFrom, boolean valid, String note, List<Change> changes, List<SearchInfo> searches,
                         InSample inSample, List<Forward> forward, boolean x1Bull, boolean x05Bull, boolean x1Bear, boolean x05Bear,
                         long replays, long guardRejections) {
    }

    /** Statistiques d'une phase de marché (haussière / baissière) à un horizon. */
    public record PhaseStat(String phase, int n, int deltaPositive, double medianDeltaPts, int beatsRandom) {
    }

    public record HorizonVerdict(int horizonMonths, int n, int deltaPositive, double shareDeltaPositive, double medianDeltaPts,
                                 Double medianDeltaPctOfPreset, int beatsRandom, double shareBeatsRandom, int beatsDcaEqual, boolean pass,
                                 List<String> causes, double effectiveEvents, List<PhaseStat> phases) {
    }

    /** {@code RÉGLAGE_RÉCENT_PERSISTANT} / {@code NON_PERSISTANT} sur l'horizon de verdict ; l'horizon court est rapporté à part. */
    public record Verdict(String code, HorizonVerdict verdictHorizon, List<HorizonVerdict> others, String rule) {
    }

    /** Réglage déplacé : combien de T, dans quel sens (hausse / baisse), déplacement normalisé médian. */
    public record ParamStat(String role, String key, int changed, int total, int up, int down, Double medianMove) {
    }

    /** Zones X1 / X0,5 : activées sur combien de T, et Δ moyen à l'horizon de verdict selon qu'elles sont activées ou non. */
    public record ZoneStat(int total, int x1Bull, int x05Bull, int x1Bear, int x05Bear, Double meanDeltaActive, Double meanDeltaInactive,
                           int nActive, int nInactive) {
    }

    /** Étape A (réglage « gel » le meilleur sur le dernier bloc) contre ce que D0 retient. */
    public record AComparison(String role, String key, String stepADirection, int d0Up, int d0Down, int d0Changed, String d0Latest,
                              boolean sameAsMajority) {
    }

    /** Réglages trouvés au T le plus récent, au format de l'Expert (non appliqués). */
    public record Latest(LocalDate t, List<Change> changes, Map<String, Object> bullExport, Map<String, Object> bearExport) {
    }

    public record Multiplicity(int dates, int searches, long searchTrials, long replays, int perturbationDraws, int horizons, int configurations) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport de réglage récent non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        return new RecentTuneReport(inputs, estimate, points, verdict, frequency, zones, stepA, latest, multiplicity, notes, null).toJson();
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%+.2f", v);
    }

    private static String p(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String pct(double share) {
        return String.format(Locale.ROOT, "%.0f %%", 100 * share);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Réglage récent persistant ? (étape D0, ").append(inputs.asset()).append(")\n\n");
        sb.append("Définition : réglé sur les ").append(inputs.tuneMonths()).append(" mois se terminant en T (Bull puis Bear, ")
                .append(inputs.rounds()).append(" tours, recherche conjointe, gel, garde-fou d'exposition ×")
                .append(String.format(Locale.ROOT, "%.2f", inputs.exposureGuardFactor())).append("), joué sur [T, T + ")
                .append(inputs.horizons()).append(" mois]. Bougies ").append(inputs.fingerprint().checksum()).append(" (")
                .append(inputs.fingerprint().count()).append(", ").append(inputs.fingerprint().firstDay()).append(" → ")
                .append(inputs.fingerprint().lastDay()).append("), capital ").append(inputs.cash0()).append(" USDC, graine ")
                .append(inputs.seed()).append(".\n\n");
        sb.append("## Verdict : ").append(verdict.code()).append("\n");
        sb.append(verdict.rule()).append("\n\n");
        horizonLines(sb, verdict.verdictHorizon());
        verdict.others().forEach(h -> horizonLines(sb, h));
        sb.append("\n## Par T (condensé)\n");
        for (int h : inputs.horizons()) {
            sb.append("\n### Horizon ").append(h).append(" mois\n");
            sb.append("| T | Phase | Réglages bougés | Preset % | Réglé % | Δ pt | Δ % PnL preset | DCA égal % | Aléatoire (méd.) % | Expo preset / réglé % | DD preset / réglé % | Achats / ventes réglé |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (TPoint t : points) {
                Forward fw = t.forward().stream().filter(x -> x.horizonMonths() == h).findFirst().orElse(null);
                if (fw == null) {
                    sb.append("| ").append(t.t()).append(" | — | ").append(t.note()).append(" |||||||||||\n");
                    continue;
                }
                sb.append("| ").append(t.t()).append(" | ").append(fw.phase()).append(" ").append(f(fw.priceChangePct())).append(" % | ")
                        .append(t.changes().size()).append(" | ").append(f(fw.preset().pnlPct())).append(" | ").append(f(fw.tuned().pnlPct()))
                        .append(" | **").append(f(fw.deltaPts())).append("** | ")
                        .append(fw.deltaPctOfPreset() == null ? "—" : String.format(Locale.ROOT, "%+.0f %%", fw.deltaPctOfPreset())).append(" | ")
                        .append(f(fw.dcaEqual().pnlPct())).append(fw.dcaEqualCalibrated() ? "" : " (non calé)").append(" | ")
                        .append(f(fw.randomMedianPnl())).append(" (").append(fw.randomAccepted()).append(") | ")
                        .append(p(fw.preset().avgExposurePct())).append(" / ").append(p(fw.tuned().avgExposurePct())).append(" | ")
                        .append(p(fw.preset().maxDrawdownPct())).append(" / ").append(p(fw.tuned().maxDrawdownPct())).append(" | ")
                        .append(fw.tuned().buys()).append(" / ").append(fw.tuned().sells()).append(" |\n");
            }
        }
        sb.append("\n## Dans l'échantillon (vue de réglage, contrôle)\n");
        sb.append("| T | Gel Bull $ preset → réglé | Gel Bear $ preset → réglé | Cycle réel % preset → réglé | Expo % | Garde-fou rejets | Essais |\n|---|---|---|---|---|---|---|\n");
        for (TPoint t : points) {
            if (t.inSample() == null) {
                continue;
            }
            InSample s = t.inSample();
            sb.append("| ").append(t.t()).append(" | ").append(f(s.bullGelPreset())).append(" → ").append(f(s.bullGelTuned())).append(" | ")
                    .append(f(s.bearGelPreset())).append(" → ").append(f(s.bearGelTuned())).append(" | ").append(f(s.cyclePnlPreset()))
                    .append(" → ").append(f(s.cyclePnlTuned())).append(" | ").append(p(s.exposurePreset())).append(" → ")
                    .append(p(s.exposureTuned())).append(" | ").append(t.guardRejections()).append(" | ")
                    .append(t.searches().stream().mapToLong(SearchInfo::trials).sum()).append(" |\n");
        }
        sb.append("\n## Réglages qui bougent le plus souvent\n| Jeu | Réglage | T où bougé | Hausse | Baisse | Déplacement médian (fraction de la plage) |\n|---|---|---|---|---|---|\n");
        for (ParamStat s : frequency) {
            sb.append("| ").append(s.role()).append(" | ").append(s.key()).append(" | ").append(s.changed()).append("/").append(s.total())
                    .append(" | ").append(s.up()).append(" | ").append(s.down()).append(" | ").append(s.medianMove() == null ? "—" : f(s.medianMove()))
                    .append(" |\n");
        }
        if (zones != null) {
            sb.append("\n## Zones X1 / X0,5\nActivées (bornes > 0 après réglage) sur ").append(zones.total()).append(" T : Bull X1 ")
                    .append(zones.x1Bull()).append(", Bull X0,5 ").append(zones.x05Bull()).append(", Bear X1 ").append(zones.x1Bear())
                    .append(", Bear X0,5 ").append(zones.x05Bear()).append(". Δ moyen à l'horizon de verdict : ")
                    .append(zones.meanDeltaActive() == null ? "—" : f(zones.meanDeltaActive())).append(" pt si au moins une zone activée (")
                    .append(zones.nActive()).append(" T), ").append(zones.meanDeltaInactive() == null ? "—" : f(zones.meanDeltaInactive()))
                    .append(" pt sinon (").append(zones.nInactive()).append(" T).\n");
        }
        sb.append("\n## Comparaison avec l'étape A (réglage « gel » le meilleur sur le dernier bloc)\n| Jeu | Réglage | Étape A | D0 hausse | D0 baisse | D0 bougé | Dernier T | Même sens que la majorité D0 |\n|---|---|---|---|---|---|---|---|\n");
        for (AComparison a : stepA) {
            sb.append("| ").append(a.role()).append(" | ").append(a.key()).append(" | ").append(a.stepADirection()).append(" | ").append(a.d0Up())
                    .append(" | ").append(a.d0Down()).append(" | ").append(a.d0Changed()).append("/").append(points.size()).append(" | ")
                    .append(a.d0Latest()).append(" | ").append(a.sameAsMajority() ? "oui" : "non").append(" |\n");
        }
        if (latest != null) {
            sb.append("\n## Réglages au T le plus récent (").append(latest.t()).append(") — NON APPLIQUÉS\n| Jeu | Réglage | Avant | Après |\n|---|---|---|---|\n");
            latest.changes().forEach(c -> sb.append("| ").append(c.role()).append(" | ").append(c.key()).append(" | ").append(c.before())
                    .append(" | ").append(c.after()).append(" |\n"));
            if (latest.changes().isEmpty()) {
                sb.append("| — | aucun réglage retenu | | |\n");
            }
        }
        sb.append("\n## Multiplicité\n").append(multiplicity.dates()).append(" T, ").append(multiplicity.searches()).append(" recherches, ")
                .append(multiplicity.searchTrials()).append(" essais du moteur, ").append(multiplicity.replays()).append(" rejeux de candidats, ")
                .append(multiplicity.perturbationDraws()).append(" tirages de perturbation, ").append(multiplicity.horizons())
                .append(" horizons, ").append(multiplicity.configurations()).append(" configuration (un seul run, aucune variante).\n");
        sb.append("\n## Écarts de périmètre et limites\n");
        notes.forEach(n -> sb.append("- ").append(n).append("\n"));
        return sb.toString();
    }

    private static void horizonLines(StringBuilder sb, HorizonVerdict h) {
        sb.append("- **").append(h.horizonMonths()).append(" mois** : ").append(h.pass() ? "OK" : "KO").append(" — Δ > 0 vs preset sur ")
                .append(h.deltaPositive()).append("/").append(h.n()).append(" T (").append(pct(h.shareDeltaPositive())).append("), médiane Δ ")
                .append(f(h.medianDeltaPts())).append(" pt (")
                .append(h.medianDeltaPctOfPreset() == null ? "— % du PnL preset" : String.format(Locale.ROOT, "%+.0f %% du PnL preset", h.medianDeltaPctOfPreset()))
                .append("), bat la médiane des perturbations sur ").append(h.beatsRandom()).append("/").append(h.n()).append(" (")
                .append(pct(h.shareBeatsRandom())).append("), bat le DCA égal sur ").append(h.beatsDcaEqual()).append("/").append(h.n())
                .append(". Événements effectifs ≈ ").append(String.format(Locale.ROOT, "%.1f", h.effectiveEvents()))
                .append(" (périodes de test chevauchées, non indépendantes).");
        if (!h.pass()) {
            sb.append(" Échec : ").append(String.join(", ", h.causes())).append(".");
        }
        sb.append("\n");
        h.phases().forEach(ph -> sb.append("  - phase ").append(ph.phase()).append(" : ").append(ph.n()).append(" T, Δ > 0 sur ")
                .append(ph.deltaPositive()).append(", médiane Δ ").append(f(ph.medianDeltaPts())).append(" pt, bat l'aléatoire ")
                .append(ph.beatsRandom()).append("\n"));
    }
}
