package fr.ses10doigts.tradeIO5.model.dto.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.EpisodeReportDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ChartDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ResultLineDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SetDto;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO du simulateur complet de l'Espace Expert (type {@code FULL}) : demande, défauts du formulaire, résultat. Jamais d'entité JPA
 * exposée. Montants en USDC ; les séries sont parallèles (même longueur que {@code series.d}, une entrée par jour).
 */
public final class FullSimulationDtos {

    private FullSimulationDtos() {
    }

    /** Surcharge d'un lien (tous champs facultatifs) : {@code enabled=false} l'écarte ; montant de base et bag remplacent ceux du lien. */
    public record LinkOverride(Long bindingId, Boolean enabled, Double baseAmount, Double bagPercent) {
    }

    /**
     * Demande du simulateur complet. Tout champ nul prend son défaut : wallet du lien le plus prioritaire, plage = 24 derniers mois,
     * capital = valeur du wallet réel (cash USDC + actifs au prix), modulation = réglages effectifs de l'utilisateur, avec frais.
     * {@code links} nul/vide = tous les liens exploitables du wallet.
     */
    public record FullRequest(Long walletId, LocalDate from, LocalDate to, Double cash0, Double exposureMin, Double exposureMax,
                              Double power, Double bias, Double regulationMarginPct, Boolean withCosts, List<LinkOverride> links) {
    }

    // ---------------------------------------------------------------- défauts du formulaire

    public record WalletChoiceDto(Long walletId, String name, String exchange) {
    }

    /** Valeur du wallet réel d'après le dernier snapshot live de chaque lien (lecture base seule, aucun appel exchange). */
    public record WalletValueDto(double cash, double assetsValue, double total, LocalDate asOf, Map<String, Double> quantities) {
    }

    public record LinkDefaultDto(Long bindingId, String asset, String affectationName, String source, int priority, double bagPercent,
                                 Double baseAmount, boolean usable, String unusableReason) {
    }

    public record ModulationDto(double exposureMin, double exposureMax, double power, double bias, Double regulationMarginPct) {
    }

    public record ModulationLimitsDto(double regulationMarginPct, double biasMax, double powerMin, double powerMax,
                                      double minBandWidthPts) {
    }

    public record FullDefaultsDto(Long walletId, String walletName, String exchange, List<WalletChoiceDto> wallets,
                                  WalletValueDto wallet, List<LinkDefaultDto> links, ModulationDto modulation,
                                  ModulationLimitsDto limits, LocalDate defaultFrom, LocalDate defaultTo, boolean withCosts) {
    }

    // ---------------------------------------------------------------- résultat

    /**
     * Paramétrage RÉELLEMENT rejoué pour un lien (affectation du lien au lancement, surcharges du formulaire appliquées) : Trend Mix,
     * correspondance régime → jeu, fenêtre d'analyse, jeux Rainbow de chaque régime.
     */
    public record FullLinkParamsDto(TrendMixCalculator.Params trend, Object rangeMapping, int analysisWindowMonths, List<SetDto> sets) {
    }

    public record FullLinkMetaDto(Long bindingId, String asset, String affectationName, String requestedSource, String usedSource,
                                  String providerSymbol, boolean substituted, int priority, double bagPercent, Double baseAmount,
                                  LocalDate firstCandle, LocalDate lastCandle, int candles, FullLinkParamsDto params) {
    }

    public record FullMetaDto(Long walletId, LocalDate from, LocalDate to, double cash0, boolean withCosts, ModulationDto modulation,
                              List<FullLinkMetaDto> links, List<String> warnings) {
    }

    /**
     * Séries journalières communes : valeur du wallet stratégie ({@code wallet}) et du DCA fixe de même budget ({@code fixed}), cash,
     * valeur et part d'exposition (%) par actif, frais cumulés. {@code exposurePct} + {@code cashPct} = 100 chaque jour.
     */
    public record FullSeriesDto(List<String> d, List<Number> wallet, List<Number> fixed, List<Number> cash, List<Number> fixedCash,
                                Map<String, List<Number>> assetValue, Map<String, List<Number>> exposurePct, List<Number> cashPct,
                                List<Number> costCum, List<Number> fixedCostCum) {
    }

    /**
     * Ordre voulu par un lien un jour donné. {@code outcome} : {@code OK} (passé), {@code BELOW_MIN} (sous le minimum, reporté en
     * carry) ou {@code BLOCKED} ({@code reason} = cause, ex. INSUFFICIENT_CASH). {@code side} : BUY | SELL.
     */
    public record FullOrderDto(String day, String side, double amountUsdc, double quantity, String outcome, String reason,
                               double costUsdc) {
    }

    /**
     * Panneau prix d'un actif (pour la piste d'épisodes) et {@code chart} = graphique Rainbow complet de l'actif (mêmes colonnes que l'onglet Rainbow, jours du lien seulement), aligné sur {@code series.d} (null = pas de bougie ce jour-là) : OHLC, régime ({@code regime} : 0 aucun,
     * 1 UP, 2 DOWN), barre active (0/1), zone Rainbow (-1 si bornes non calculables), ordres.
     */
    public record FullAssetChartDto(String asset, Long bindingId, List<Number> o, List<Number> h, List<Number> l, List<Number> c,
                                    List<Integer> regime, List<Integer> active, List<Integer> zone, List<FullOrderDto> orders,
                                    ChartDto chart) {
    }

    /**
     * Mesure d'un drawdown sur toute la plage simulée (points de %, ≤ 0) : maximum, plus haut précédent, creux, retour au plus haut
     * (null si jamais), plus longue durée sous l'eau (jours), drawdown du dernier jour.
     */
    public record DrawdownMeasureDto(double maxPct, LocalDate peakDay, LocalDate troughDay, LocalDate recoveryDay,
                                     int longestUnderwaterDays, double lastPct) {
    }

    /** Verdict de la stratégie face au plafond d'un profil ({@code profile} : PRUDENT | BALANCED | DYNAMIC | FULL). */
    public record DrawdownVerdictDto(String profile, double capPct, boolean respected, LocalDate firstBreachDay, double maxOverrunPts) {
    }

    /**
     * Bloc drawdown du résultat : séries alignées sur {@code series.d} (stratégie, DCA fixe), mesures de chacune, verdict de la
     * stratégie par profil. {@code userProfile} : profil de l'utilisateur (null si jamais choisi) ; {@code defaultProfile} : profil
     * présélectionné à l'écran (le sien, null si CUSTOM ou aucun : l'utilisateur choisit). Signalement seul, aucun effet sur les ordres.
     */
    public record DrawdownDto(List<Number> strategy, List<Number> fixed, DrawdownMeasureDto strategyMeasure,
                              DrawdownMeasureDto fixedMeasure, List<DrawdownVerdictDto> verdicts, String userProfile,
                              String defaultProfile) {
    }

    public record FullSimulationResultDto(FullMetaDto meta, FullSeriesDto series, List<FullAssetChartDto> assets,
                                          List<ResultLineDto> lines, EpisodeReportDto episodes, DrawdownDto drawdown) {
    }
}
