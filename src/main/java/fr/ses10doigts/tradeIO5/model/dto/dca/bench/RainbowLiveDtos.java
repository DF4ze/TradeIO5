package fr.ses10doigts.tradeIO5.model.dto.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO de l'API REST du bench grandeur nature (jamais d'entité JPA sérialisée). {@code tuning}/{@code globals} =
 * records du moteur tels quels (noms de champs identiques, enums en chaîne).
 */
public final class RainbowLiveDtos {

    private RainbowLiveDtos() {
    }

    /**
     * {@code reentryModes} = valeurs de l'enum {@code ReentryMode} ; {@code zones} = codes/noms/libellés des zones du
     * moteur (le front ne duplique aucune de ces constantes).
     */
    public record DefaultsDto(List<String> assets, String cashCurrency, String defaultName, int analysisWindowMonths,
                              double baseAmount, Map<String, ConfigDto> configs,
                              List<String> reentryModes, List<ZoneDto> zones,
                              Map<String, TrendConfigDto> trendDefaults, List<String> rangeMappings,
                              List<String> uiModes, String systemPrefix) {
    }

    public record ZoneDto(int code, String name, String label) {
    }

    public record ConfigDto(RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
    }

    /** Réglages d'un preset TREND_MIX : Trend Mix, mapping du RANGE (KEEP_PREVIOUS|TO_BEAR|TO_BULL), jeux Bear/Bull. */
    public record TrendConfigDto(TrendMixCalculator.Params trend, String rangeMapping, ConfigDto bear, ConfigDto bull) {
    }

    /** Stratégie Actif (lecture seule côté user) ; {@code trendConfig} nul pour une stratégie FIXED. */
    public record StrategyDto(Long id, String assetSymbol, String name, String mode, int revision, Instant updatedAt,
                              int analysisWindowMonths, ConfigDto config,
                              TrendConfigDto trendConfig) {
    }

    /** Activation / désactivation d'un preset. */
    public record EnabledDto(boolean enabled) {
    }

    /** Mode d'affichage de la page, par utilisateur. */
    public record UiModeDto(String mode) {
    }

    /** Dernier signal enregistré d'un preset (run le plus récent). */
    public record LastRunDto(LocalDate day, Double close, Integer zone, RainbowLiveAction actionType,
                             Double actionAmountUsdc, Double actionQuantity,
                             String activeSet, String trendRegime) {
    }

    public record PresetDto(Long id, String assetSymbol, String name, boolean enabled, int analysisWindowMonths,
                            Instant createdAt, Instant updatedAt,
                            RainbowAtrTuning tuning, RainbowAtrGlobals globals,
                            long runCount, LocalDate firstRunDay, LastRunDto lastRun, String mode, TrendConfigDto trendConfig,
                            boolean followsStrategy, Integer strategyRevision) {
    }

    /** Événement de l'historique des switchs de preset. */
    public record PresetEventDto(Long id, String assetSymbol, String type, Long presetBeforeId, String presetBeforeName,
                                 Long presetAfterId, String presetAfterName, Integer strategyRevision, Instant occurredAt,
                                 String reason) {
    }
}
