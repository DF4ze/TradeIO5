package fr.ses10doigts.tradeIO5.service.expert.simulation;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rapport de parité du rejeu mono-actif contre les runs enregistrés par la prod. {@code pinned} = wallet recalé sur le
 * snapshot live chaque jour (parité de la DÉCISION du jour) ; {@code free} = wallet simulé en continu (cascade des écarts).
 */
public record ParityReport(Long bindingId, String asset, LocalDate from, LocalDate to, String candleSource, double cash0,
                           double qty0, Stats pinned, Stats free, double finalValue, double fixedFinalValue,
                           double acceptancePct, boolean accepted, List<Row> mismatches, Map<String, Integer> causes,
                           List<String> notes) {

    /** Un écart : jour, attendu (live), obtenu (rejeu), cause probable. */
    public record Row(LocalDate day, String expected, String got, String cause) {
    }

    /** Comptes d'une exécution : jours comparés, identiques, écarts expliqués / inexpliqués. */
    public record Stats(int compared, int matching, int explained, int unexplained, double matchPct) {
    }

    /** Rapport lisible (Markdown) : verdict, comptes, causes puis un écart par ligne. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Parité rejeu / prod — ").append(asset).append(" (lien ").append(bindingId).append(")\n\n");
        sb.append("Période ").append(from).append(" → ").append(to).append(", candles ").append(candleSource)
                .append(", C0 ").append(fmt(cash0)).append(" USDC + ").append(fmt(qty0)).append(" ").append(asset).append("\n\n");
        sb.append("- **Verdict** : ").append(accepted ? "ACCEPTÉ" : "NON ACCEPTÉ").append(" (seuil ").append(fmt(acceptancePct))
                .append(" % identiques et 0 écart inexpliqué)\n");
        sb.append("- Décision du jour (wallet recalé) : ").append(line(pinned)).append('\n');
        sb.append("- Wallet simulé en continu : ").append(line(free)).append('\n');
        sb.append("- Valeur finale ").append(fmt(finalValue)).append(" USDC, DCA fixe de même base ").append(fmt(fixedFinalValue))
                .append(" USDC\n\n");
        if (!causes.isEmpty()) {
            sb.append("## Causes\n");
            causes.forEach((cause, count) -> sb.append("- ").append(cause).append(" : ").append(count).append('\n'));
            sb.append('\n');
        }
        for (String note : notes) {
            sb.append("> ").append(note).append('\n');
        }
        sb.append("\n## Écarts (décision du jour)\n| Jour | Attendu | Obtenu | Cause probable |\n|---|---|---|---|\n");
        for (Row row : mismatches) {
            sb.append("| ").append(row.day()).append(" | ").append(row.expected()).append(" | ").append(row.got()).append(" | ")
                    .append(row.cause()).append(" |\n");
        }
        return sb.toString();
    }

    private static String line(Stats s) {
        return s.matching() + "/" + s.compared() + " identiques (" + fmt(s.matchPct()) + " %), " + s.explained()
                + " écarts expliqués, " + s.unexplained() + " inexpliqués";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
