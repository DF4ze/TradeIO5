package fr.ses10doigts.tradeIO5.service.expert.simulation;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rapport de parité du rejeu d'un wallet (1 à N liens, cash et exposition communs) contre les runs enregistrés par la prod.
 * {@code pinned} = wallet recalé sur les snapshots live chaque jour (parité de la DÉCISION du jour) ; {@code free} = wallet simulé en
 * continu (cascade des écarts). Acceptation : globale ET par lien (≥ {@code acceptancePct} % identiques, 0 écart inexpliqué).
 */
public record ParityReport(Long walletId, LocalDate from, LocalDate to, double cash0, Stats pinned, Stats free,
                           double finalValue, double fixedFinalValue, double acceptancePct, boolean accepted,
                           List<LinkReport> links, List<Row> mismatches, Map<String, Integer> causes, List<String> notes) {

    /** Un écart : lien, jour, attendu (live), obtenu (rejeu), cause probable. */
    public record Row(Long bindingId, String asset, LocalDate day, String expected, String got, String cause) {
    }

    /** Comptes d'une exécution : jours comparés, identiques, écarts expliqués / inexpliqués. */
    public record Stats(int compared, int matching, int explained, int unexplained, double matchPct) {

        public static Stats of(int compared, int matching, int explained, int unexplained) {
            return new Stats(compared, matching, explained, unexplained, compared == 0 ? 0.0 : 100.0 * matching / compared);
        }

        public Stats plus(Stats o) {
            return of(compared + o.compared, matching + o.matching, explained + o.explained, unexplained + o.unexplained);
        }
    }

    /** Parité d'un lien du wallet (période propre : de son 1er run exploitable au dernier). */
    public record LinkReport(Long bindingId, String asset, int priority, LocalDate from, LocalDate to, String candleSource,
                             double qty0, Stats pinned, Stats free, boolean accepted) {
    }

    /** Rapport lisible (Markdown) : verdict, comptes globaux et par lien, causes puis un écart par ligne. */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Parité rejeu / prod — wallet ").append(walletId).append(" (").append(links.size()).append(" lien(s))\n\n");
        sb.append("Période ").append(from).append(" → ").append(to).append(", C0 ").append(fmt(cash0)).append(" USDC\n\n");
        sb.append("- **Verdict** : ").append(accepted ? "ACCEPTÉ" : "NON ACCEPTÉ").append(" (seuil ").append(fmt(acceptancePct))
                .append(" % identiques et 0 écart inexpliqué, par lien et global)\n");
        sb.append("- Global, décision du jour (wallet recalé) : ").append(line(pinned)).append('\n');
        sb.append("- Global, wallet simulé en continu : ").append(line(free)).append('\n');
        sb.append("- Valeur finale ").append(fmt(finalValue)).append(" USDC, DCA fixe de même budget ").append(fmt(fixedFinalValue))
                .append(" USDC\n\n## Par lien\n");
        for (LinkReport l : links) {
            sb.append("- ").append(l.asset()).append(" (lien ").append(l.bindingId()).append(", priorité ").append(l.priority())
                    .append(", ").append(l.candleSource()).append(", ").append(l.from()).append(" → ").append(l.to()).append(") : ")
                    .append(l.accepted() ? "ACCEPTÉ" : "NON ACCEPTÉ").append(" — recalé ").append(line(l.pinned()))
                    .append(" ; continu ").append(line(l.free())).append('\n');
        }
        sb.append('\n');
        if (!causes.isEmpty()) {
            sb.append("## Causes\n");
            causes.forEach((cause, count) -> sb.append("- ").append(cause).append(" : ").append(count).append('\n'));
            sb.append('\n');
        }
        for (String note : notes) {
            sb.append("> ").append(note).append('\n');
        }
        sb.append("\n## Écarts (décision du jour)\n| Jour | Actif | Attendu | Obtenu | Cause probable |\n|---|---|---|---|---|\n");
        for (Row row : mismatches) {
            sb.append("| ").append(row.day()).append(" | ").append(row.asset()).append(" | ").append(row.expected()).append(" | ")
                    .append(row.got()).append(" | ").append(row.cause()).append(" |\n");
        }
        return sb.toString();
    }

    private static String line(Stats s) {
        return s.matching() + "/" + s.compared() + " identiques (" + fmt(s.matchPct()) + " %), " + s.explained()
                + " expliqués, " + s.unexplained() + " inexpliqués";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
