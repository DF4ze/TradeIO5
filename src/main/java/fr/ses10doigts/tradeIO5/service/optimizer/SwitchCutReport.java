package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Rapport de l'étape E1 : frontière « preset pur » contre frontière « preset + coupure au switch », à plafond de drawdown égal. */
public record SwitchCutReport(String definition, SwitchCutConfig config, List<Run> runs, List<String> notes) {

    /** Un run : un actif, une taille de ré-entrée (en % du capital initial). */
    public record Run(String label, double reentryPct, AssetCut asset) {
    }

    public static final String DEFINITION = "E1 : coupure du bag au switch Bull → Bear, ré-entrée au switch Bear → Bull ; comparée au preset pur (modulation ATH "
            + "du preset désactivée) à plafond de drawdown égal ; BTC, blocs de 24 mois, un seul run.";

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);

    /** Un point de grille sur un bloc, une ligne : gain, drawdown et exposition moyenne. */
    public record Point(double gainPct, double maxDrawdownPct, double exposurePct) {
    }

    /** Un niveau (multiplicateur) : les blocs de la ligne « pur » et de la ligne « coupure », ordres de coupure exécutés. */
    public record Level(double multiplier, List<Point> pure, List<Point> cut, int cutSells, int cutBuys, double cutCostUsdc) {
    }

    /** Interpolation d'une ligne au plafond : multiplicateur, exposition médiane, gain de chaque bloc. */
    public record AtCap(String status, Double multiplier, Double exposurePct, List<Double> gains, Double medianGain) {
    }

    /** Comparaison à un plafond de profil. */
    public record CapResult(String profile, double capPct, AtCap pure, AtCap cut, List<Double> deltaGains, int blocksWon, Double medianDelta,
                            String verdict) {
    }

    public record AssetCut(String asset, double cash0, double referenceBase, List<String> blockLabels, List<Level> levels, List<CapResult> caps,
                           int capsUseful, String verdict, String verdictText, long replays, long durationMs) {
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Rapport E1 non sérialisable", e);
        }
    }

    static String f(Double v) {
        return v == null || v.isNaN() ? "n/d" : String.format(Locale.ROOT, "%+.1f", v);
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder("# Étape E1 — coupure du bag au switch\n\n").append(definition).append("\n\n");
        sb.append(String.format(Locale.ROOT, "Configuration : vente %.0f %% du bag au switch Bull → Bear, ré-entrée au switch Bear → Bull indiquée par run (en %% du capital initial), "
                        + "règle fixée avant lecture : par plafond, UTILE si ≥ %d blocs sur %d gagnés ET Δ gain médian > 0 ; coupure retenue si UTILE à ≥ %d plafonds sur 4.\n\n",
                config.sellFraction() * 100, config.parity().minBlocksPass(), config.parity().blockCount(), config.minCapsPass()));
        sb.append("## Synthèse\n\n| Actif | Ré-entrée | Blocs | Plafonds UTILES | Verdict |\n|---|---|---|---|---|\n");
        for (Run r : runs) {
            sb.append(String.format(Locale.ROOT, "| %s | %.0f %% | %d | %d/4 | %s |\n", r.asset().asset(), r.reentryPct(), r.asset().blockLabels().size(),
                    r.asset().capsUseful(), r.asset().verdict()));
        }
        sb.append('\n');
        for (Run r : runs) {
            section(sb, r);
        }
        sb.append("## Notes\n\n");
        notes.forEach(n -> sb.append("- ").append(n).append('\n'));
        return sb.toString();
    }

    private void section(StringBuilder sb, Run r) {
        AssetCut a = r.asset();
        sb.append("## ").append(a.asset()).append(String.format(Locale.ROOT, ", ré-entrée %.0f %%", r.reentryPct())).append(" — verdict : ").append(a.verdict()).append("\n\n").append(a.verdictText()).append("\n\n");
        sb.append("### Plafonds de drawdown (à drawdown égal)\n\n| Profil | Plafond | Pur : ×, expo %, gain médian % | Coupure : ×, expo %, gain médian % | Δ gain par bloc | Blocs gagnés | Δ médian | Verdict |\n|---|---|---|---|---|---|---|---|\n");
        for (CapResult c : a.caps()) {
            sb.append(String.format(Locale.ROOT, "| %s | %.0f | %s | %s | %s | %d/%d | %s | %s |\n", c.profile(), c.capPct(), cell(c.pure()), cell(c.cut()),
                    c.deltaGains().stream().map(SwitchCutReport::f).toList(), c.blocksWon(), a.blockLabels().size(), f(c.medianDelta()), c.verdict()));
        }
        sb.append("\n### Grille (pire drawdown des blocs, gain médian des blocs)\n\n| × | Pur : pire DD | Pur : gain méd. | Coupure : pire DD | Coupure : gain méd. | Ventes | Rachats |\n|---|---|---|---|---|---|---|\n");
        for (Level l : a.levels()) {
            sb.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %d | %d |\n", l.multiplier(), f(worst(l.pure())), f(median(l.pure().stream().map(Point::gainPct).toList())),
                    f(worst(l.cut())), f(median(l.cut().stream().map(Point::gainPct).toList())), l.cutSells(), l.cutBuys()));
        }
        sb.append(String.format(Locale.ROOT, "\n%d rejeux en %d ms. Blocs : %s.\n\n", a.replays(), a.durationMs(), a.blockLabels()));
    }

    private static String cell(AtCap c) {
        return c.multiplier() == null ? c.status() : String.format(Locale.ROOT, "×%.2f, %.1f, %s (%s)", c.multiplier(), c.exposurePct(), f(c.medianGain()), c.status());
    }

    static Double worst(List<Point> ps) {
        return ps.stream().mapToDouble(Point::maxDrawdownPct).min().orElse(Double.NaN);
    }

    static Double median(List<Double> v) {
        List<Double> s = new ArrayList<>(v);
        s.sort(Double::compare);
        if (s.isEmpty()) {
            return Double.NaN;
        }
        return s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2.0;
    }
}
