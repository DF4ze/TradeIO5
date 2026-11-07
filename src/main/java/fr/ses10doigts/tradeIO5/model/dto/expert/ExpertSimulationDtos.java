package fr.ses10doigts.tradeIO5.model.dto.expert;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** DTO de l'API de simulation de l'Espace Expert ({@code /api/expert/simulations}) : jamais d'entité JPA exposée. */
public final class ExpertSimulationDtos {

    private ExpertSimulationDtos() {
    }

    /** Réglages Trend non enregistrés (brouillon), validés comme ceux d'un preset. */
    public record TrendDraft(TrendMixCalculator.Params trend, String rangeMapping) {
    }

    /** Réglages Rainbow non enregistrés (brouillon), validés comme ceux d'un preset. */
    public record RainbowDraft(Integer analysisWindowMonths, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
    }

    /** Preset Trend par {@code id} (système ou propre à l'appelant) OU brouillon : exactement l'un des deux. */
    public record TrendRef(Long id, TrendDraft draft) {
    }

    /** Preset Rainbow par {@code id} (système ou propre à l'appelant) OU brouillon : exactement l'un des deux. */
    public record RainbowRef(Long id, RainbowDraft draft) {
    }

    /**
     * Demande de simulation. {@code type} : TREND | RAINBOW | AFFECTATION. {@code provider} : source tentée en premier (défaut :
     * provider de référence du preset, sinon défauts de l'actif). {@code from} / {@code to} : plage (défaut : 24 derniers mois).
     * TREND : {@code trend}. RAINBOW : {@code rainbow} + {@code signal} (UP | DOWN) + {@code trend} de référence. AFFECTATION :
     * {@code trend}, {@code rainbowUp}, {@code rainbowDown}. Toute référence omise prend celle de l'Affectation de l'utilisateur,
     * à défaut le preset système de base.
     */
    public record SimulationRequest(String type, String assetSymbol, String provider, LocalDate from, LocalDate to,
                                    TrendRef trend, RainbowRef rainbow, String signal, RainbowRef rainbowUp,
                                    RainbowRef rainbowDown) {
    }

    public record SimulationCreatedDto(String id, String state) {
    }

    public record SimulationErrorDto(String code, String message, Object details) {
    }

    /** État d'une simulation : progression ({@code 0..1}) puis résultat ({@code DONE}) ou erreur ({@code FAILED}). */
    public record SimulationDto(String id, String type, String assetSymbol, String state, double progress, Instant createdAt,
                                Instant finishedAt, Instant expiresAt, SimulationErrorDto error, SimulationResultDto result) {
    }

    /** Éligibilité d'un provider : {@code available} = bougies D1 déjà en cache, {@code maxDepth} = profondeur servable (nul = illimitée). */
    public record ProviderEligibilityDto(String provider, int available, Integer maxDepth, boolean eligible, boolean loadable) {
    }

    public record EligibilityDto(int requiredPoints, List<ProviderEligibilityDto> providers) {
    }

    public record MetaDto(String type, String assetSymbol, String requestedProvider, String usedProvider, String providerSymbol,
                          boolean substituted, Instant firstCandle, Instant lastCandle, int candles, LocalDate from, LocalDate to,
                          String trendLabel, String rainbowLabel, List<String> warnings) {
    }

    public record PhaseDto(String regime, LocalDate start, LocalDate end, int bars) {
    }

    public record SwitchDto(LocalDate date, String from, String to) {
    }

    /** Métriques Trend (dans l'esprit du bench) sur la plage : bascules, durées de segment, part UP. */
    public record TrendMetricsDto(int switches, int phases, double averagePhaseBars, double averageUpBars,
                                  double averageDownBars, int longestPhaseBars, int shortestPhaseBars, double upShare,
                                  int resolvedBars, int unresolvedBars, String lastRegime) {
    }

    public record SetDto(String name, RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
    }

    /** Wallet fermé (capital = max(investi − vendu)) : actif = position × clôture, stable = capital − investi + vendu. */
    public record WalletDto(double capital, List<Number> asset, List<Number> stable, List<Number> total) {
    }

    /**
     * Séries du graphique, une valeur par bougie rejouée, aux noms des colonnes du gabarit {@code replay-template.html} :
     * {@code d} dates, {@code trend} (0 aucun, 1 UP, 2 DOWN), {@code active} (0/1), {@code cols} (o, h, l, c, bornes, ath,
     * marqueurs, pos = taille du bag, reserve, realized, potential, total, fixedGain…), {@code wallet} (nul pour TREND).
     */
    public record ChartDto(List<String> d, List<Integer> trend, List<Integer> active, Map<String, List<Number>> cols,
                           WalletDto wallet, List<SetDto> sets) {
    }

    /** Ligne de la fenêtre de résultats (tableau du pine) : {@code value} numérique ou {@code text}. */
    public record ResultLineDto(int index, String key, String label, Double value, String text, String unit) {
    }

    public record SimulationResultDto(MetaDto meta, EligibilityDto eligibility, List<PhaseDto> phases, List<SwitchDto> switches,
                                      TrendMetricsDto trendMetrics, ChartDto chart, List<ResultLineDto> lines) {
    }
}
