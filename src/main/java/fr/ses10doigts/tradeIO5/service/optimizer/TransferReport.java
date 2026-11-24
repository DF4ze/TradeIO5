package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.BlockDelta;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.PairResult;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.Verdict;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape D1a : JSON déterministe (hors durée) et tableaux markdown. */
public record TransferReport(String definition, TransferConfig config, List<AssetInfo> assets, List<PairResult> pairs, Verdict verdict,
                             List<Drift> drift, List<String> notes, Long totalMs) {

    public static final String DEFINITION = "Δ = gain du preset pur − gain du DCA fixe calé à exposition moyenne égale (dichotomie de B'), en points du capital, "
            + "par bloc de 24 mois ; exposition = valeur de la position / valeur totale, moyenne sur les jours. Trend = Params.defaults() + KEEP_PREVIOUS "
            + "(« Trend de base » du live pour un actif sans réglage dédié). Aucun réglage par actif.";

    /** Un actif de l'étude : {@code role} = CONTROLE (déjà en base) ou NOUVEAU (bougies bench étiquetées). */
    public record AssetInfo(String asset, String role, String sourceLabel, CandleFingerprint fingerprint, Double atrPct) {
    }

    /** Dérive : preset pur, bloc le plus récent, chemin loader / E1 contre l'entrée reconstruite de D1a (même preset, même actif). */
    public record Drift(String asset, Double loaderGain, Double rebuiltGain, Double diffPts) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport D1a non sérialisable", e);
        }
    }

    public String toJsonWithoutTimings() {
        return new TransferReport(definition, config, assets, pairs, verdict, drift, notes, null).toJson();
    }

    private static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String cell(PairResult p) {
        if (p == null) {
            return "-";
        }
        String tag = switch (p.status()) {
            case TransferStudy.TRANSFERE -> "OUI";
            case TransferStudy.NON_TRANSFERE -> "non";
            case TransferStudy.PROVISOIRE -> "prov. " + (p.provisionalSign() == null ? "?" : p.provisionalSign() > 0 ? "+" : p.provisionalSign() < 0 ? "-" : "0");
            default -> "n/c";
        };
        return (p.inSample() ? "[échant.] " : "") + f(p.medianDelta()) + " (" + p.wonBlocks() + "/" + p.validBlocks() + ") " + tag;
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# D1a — transfert des presets existants\n\n").append(definition).append("\n\n## Actifs\n");
        sb.append("| Actif | Rôle | Source | Bougies | Premier | Dernier | Empreinte | ATR % méd. |\n|---|---|---|---|---|---|---|---|\n");
        for (AssetInfo a : assets) {
            CandleFingerprint fp = a.fingerprint();
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s |%n", a.asset(), a.role(), a.sourceLabel(),
                    fp == null ? "n/d" : fp.count(), fp == null ? "n/d" : fp.firstDay(), fp == null ? "n/d" : fp.lastDay(), fp == null ? "n/d" : fp.checksum(),
                    f(a.atrPct())));
        }
        sb.append("\n## Preset × actif : Δ médian (pts de capital) (blocs gagnés / blocs valides) TRANSFERE\n");
        sb.append("`[échant.]` = preset réglé pour cet actif : rapporté, exclu du verdict. `prov.` = moins de ").append(config.minBlocks())
                .append(" blocs : signe seul, hors verdict.\n\n| Actif | BTC | ETH | PAXG |\n|---|---|---|---|\n");
        for (AssetInfo a : assets) {
            sb.append("| ").append(a.asset());
            for (String p : TransferStudy.PRESET_ASSETS) {
                sb.append(" | ").append(cell(pairs.stream().filter(x -> x.target().equals(a.asset()) && x.preset().equals(p)).findFirst().orElse(null)));
            }
            sb.append(" |\n");
        }
        sb.append("\n## Verdict global : ").append(verdict.verdict()).append("\n").append(verdict.text()).append("\n\n");
        sb.append("Règle 2 (GENERIQUE si TRANSFERE sur ≥ ").append(String.format(Locale.ROOT, "%.0f", 100 * config.genericShare()))
                .append(" % des actifs hors échantillon conclus) :\n\n| Preset | Part TRANSFERE | Δ médian poolé (blocs) | GENERIQUE |\n|---|---|---|---|\n");
        for (String p : TransferStudy.PRESET_ASSETS) {
            sb.append(String.format(Locale.ROOT, "| %s | %.0f %% | %s | %s |%n", p, 100 * verdict.genericShareByPreset().get(p),
                    f(verdict.pooledMedianByPreset().get(p)), verdict.genericPresets().contains(p) ? "oui" : "non"));
        }
        sb.append(String.format(Locale.ROOT, "%nRègle 3 (choix par volatilité, ATR %% médian le plus proche ; actifs hors sources conclus : %d) : TRANSFERE sur %.0f %% (seuil %.0f %%), "
                        + "bat la moyenne des deux autres sur %.0f %% (seuil %.0f %%) => %s.%n%n", verdict.volumeAssets(), 100 * verdict.volumeTransfereShare(),
                100 * config.genericShare(), 100 * verdict.volumeBeatShare(), 100 * config.volWinShare(), verdict.volumeRulePasses() ? "tient" : "ne tient pas"));
        sb.append("| Actif | Preset choisi |\n|---|---|\n");
        verdict.volumeChoice().forEach((a, p) -> sb.append("| ").append(a).append(" | ").append(p).append(" |\n"));
        sb.append("\n## Actifs « provisoire » (hors verdict)\n");
        List<PairResult> prov = pairs.stream().filter(p -> TransferStudy.PROVISOIRE.equals(p.status()) || TransferStudy.NON_CONCLU.equals(p.status())).toList();
        if (prov.isEmpty()) {
            sb.append("Aucun.\n");
        }
        for (PairResult p : prov) {
            sb.append("- ").append(p.target()).append(" × ").append(p.preset()).append(" : ").append(p.status()).append(" (").append(p.validBlocks())
                    .append(" bloc(s)) ").append(p.note()).append("\n");
        }
        sb.append("\n## A posteriori / dans l'échantillon (hors verdict) : meilleur preset par actif (Δ médian)\n| Actif | Meilleur preset | Δ médian |\n|---|---|---|\n");
        for (AssetInfo a : assets) {
            pairs.stream().filter(x -> x.target().equals(a.asset()) && x.medianDelta() != null).max(Comparator.comparingDouble(PairResult::medianDelta))
                    .ifPresent(b -> sb.append("| ").append(a.asset()).append(" | ").append(b.preset()).append(b.inSample() ? " (échant.)" : "").append(" | ")
                            .append(f(b.medianDelta())).append(" |\n"));
        }
        sb.append("\n## Détail des blocs\n| Actif | Preset | Bloc | Preset % | DCA égal % | Δ | Bande | Expo % | DD ok |\n|---|---|---|---|---|---|---|---|---|\n");
        for (PairResult p : pairs) {
            for (BlockDelta b : new ArrayList<>(p.blocks())) {
                if (b.valid()) {
                    sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %.2f | %.2f | %+.2f | %.2f | %.1f | %s |%n", p.target(), p.preset(), b.label(),
                            b.presetGain(), b.dcaGain(), b.delta(), b.noiseWidth(), b.presetExposure(), b.ddOk() ? "oui" : "non"));
                }
            }
        }
        sb.append("\n## Bilan de dérive (preset pur, bloc le plus récent)\n| Actif | Chemin loader (E1) | Entrée D1a | Écart (pts) |\n|---|---|---|---|\n");
        for (Drift d : drift) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", d.asset(), f(d.loaderGain()), f(d.rebuiltGain()), f(d.diffPts())));
        }
        sb.append("\n## Notes\n");
        notes.forEach(n -> sb.append("- ").append(n).append("\n"));
        return sb.toString();
    }
}
