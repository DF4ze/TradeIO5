package fr.ses10doigts.tradeIO5.model.dto.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePerformanceCalculator;

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
    public record DefaultsDto(List<String> assets, String stablecoin, String defaultName, int analysisWindowMonths,
                              double initialCapitalUsdc, double baseAmount, Map<String, ConfigDto> configs,
                              List<String> reentryModes, List<ZoneDto> zones) {
    }

    public record ZoneDto(int code, String name, String label) {
    }

    public record ConfigDto(RainbowAtrTuning tuning, RainbowAtrGlobals globals) {
    }

    /** Wallet mock ; {@code lastClose}/{@code equityUsdc} nuls tant qu'aucun close n'est connu. */
    public record WalletDto(double cashUsdc, double positionQuantity, Double lastClose, Double equityUsdc) {
    }

    public record LastRunDto(LocalDate day, RainbowLivePass pass, Double close, Integer zone, RainbowLiveAction actionType,
                             Double actionAmountUsdc, Double actionQuantity) {
    }

    public record PresetDto(Long id, String assetSymbol, String name, boolean enabled, int analysisWindowMonths,
                            double initialCapitalUsdc, Instant createdAt, Instant updatedAt,
                            RainbowAtrTuning tuning, RainbowAtrGlobals globals, WalletDto wallet,
                            long runCount, LocalDate firstRunDay, LastRunDto lastRun) {
    }

    public record BlockDto(Double close, Double sma, Double atr, Double boundDown2, Double boundDown1, Double boundUp1,
                           Double boundUp2, Double boundUp3, Integer zone, Double athDistance, Double buyFactor,
                           Double sellFactor, Boolean moonMode, Boolean buyArmed, Boolean sellArmed, Boolean buyLocked,
                           Integer cooldownRemaining, Double moonReserveQty, RainbowLiveAction actionType,
                           Double actionAmountUsdc, Double actionQuantity, Double actionPrice, Double cashAfter,
                           Double positionAfter, String configHash, Instant computedAt) {
    }

    public record RunDto(LocalDate day, BlockDto pass2355, BlockDto pass0005, boolean deltaActionDiffers,
                         String configHash, boolean configChanged, List<String> changedParams) {
    }

    public record ConfigMarkerDto(LocalDate day, String configHash, List<String> changedParams) {
    }

    public record PerformanceDto(Long presetId, String assetSymbol, double initialCapitalUsdc, int days,
                                 LocalDate firstDay, LocalDate lastDay,
                                 RainbowLivePerformanceCalculator.Metrics metrics,
                                 RainbowLivePerformanceCalculator.WalletMetrics wallet,
                                 List<RainbowLivePerformanceCalculator.DayPoint> series,
                                 List<RainbowLivePerformanceCalculator.TradeMarker> markers,
                                 List<ConfigMarkerDto> configMarkers) {
    }
}
