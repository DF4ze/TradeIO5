package fr.ses10doigts.tradeIO5.model.dto.expert;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO de la timeline des épisodes et de la répartition du gain du rejeu complet (Espace Expert). Un épisode = suite de jours
 * consécutifs d'un actif à même (régime Bull/Bear, zone Rainbow) ; régime « INACTIF » = barre hors régime. Montants en USDC.
 */
public final class EpisodeDtos {

    /** Régime des barres inactives (régime nul du Trend Mix). */
    public static final String INACTIVE = "INACTIF";

    private EpisodeDtos() {
    }

    /** Répartition additive de l'écart stratégie − DCA fixe : {@code total = exposure + timing + fees} (à l'arrondi numérique). */
    public record Gain(double exposure, double timing, double fees, double total) {
        public static final Gain ZERO = new Gain(0, 0, 0, 0);

        public Gain plus(Gain o) {
            return new Gain(exposure + o.exposure, timing + o.timing, fees + o.fees, total + o.total);
        }
    }

    /**
     * @param zone              -1 = bornes non calculables, 0 extrême bas, 1 X2, 2 X1, 3 X0,5, 4 pas d'achat, 5 extrême haut
     * @param avgCostEnd        prix de revient moyen en fin d'épisode ({@code null} si position nulle)
     * @param exposureStartPct  part de l'actif dans le wallet avant le 1er jour de l'épisode (null si inconnue)
     * @param exposureEndPct    idem à la fin du dernier jour
     */
    public record EpisodeDto(String asset, String regime, int zone, String zoneLabel, LocalDate from, LocalDate to, int days,
                             double boughtUsdc, double boughtQty, double soldUsdc, double soldQty, Double avgCostEnd,
                             double pnlRealized, double pnlLatent, double pnlTotal, Double exposureStartPct,
                             Double exposureEndPct, Gain gain) {
    }

    /** Agrégat de tous les épisodes d'un même (régime, zone), tous actifs confondus. */
    public record EpisodeAggregateDto(String regime, int zone, String zoneLabel, int episodes, int days, double boughtUsdc,
                                      double soldUsdc, double pnlRealized, double pnlLatent, double pnlTotal, Gain gain) {
    }

    /** Répartition du gain d'un actif sur la plage ; {@code meanDiffQty} = d̄ (qté stratégie − qté référence, moyenne des jours). */
    public record AssetGainDto(String asset, double meanDiffQty, Gain gain) {
    }

    public record GainBreakdownDto(Gain total, List<AssetGainDto> assets) {
    }

    /** Réponse complète : plage effective, épisodes chronologiques, agrégat zone × régime, répartition du gain. */
    public record EpisodeReportDto(String resultId, LocalDate from, LocalDate to, List<EpisodeDto> episodes,
                                   List<EpisodeAggregateDto> aggregates, GainBreakdownDto gain, double pnlTotal,
                                   List<String> notes) {
    }
}
