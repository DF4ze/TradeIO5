package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.DefaultsDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.LastRunDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetEventDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.StrategyDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAssetStrategy;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUiMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ZoneDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository.RunStats;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lecture du bench grandeur nature pour l'API REST (presets enrichis, dernier signal). Toutes les
 * lectures sont scopées à l'utilisateur fourni ; un preset d'un autre utilisateur ⇒
 * {@link RainbowLivePresetNotFoundException}. Aucune écriture hors seed lazy de {@link #listPresets}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveQueryService {

    /** Zones du moteur (codes {@link RainbowAtrEngine}) avec libellé lisible, exposées à la page. */
    private static final List<ZoneDto> ZONES = List.of(
            new ZoneDto(RainbowAtrEngine.EXTREME_BAS, "EXTREME_BAS", "Extrême bas"),
            new ZoneDto(RainbowAtrEngine.X2, "X2", "Zone ×2"),
            new ZoneDto(RainbowAtrEngine.X1, "X1", "Zone ×1"),
            new ZoneDto(RainbowAtrEngine.X0_5, "X0_5", "Zone ×0,5"),
            new ZoneDto(RainbowAtrEngine.NO_BUY, "NO_BUY", "Pas d'achat"),
            new ZoneDto(RainbowAtrEngine.EXTREME_HAUT, "EXTREME_HAUT", "Extrême haut"));

    private final RainbowLivePresetService presetService;
    private final RainbowAssetStrategyService strategyService;
    private final RainbowLiveRunRepository runRepository;
    private final RainbowLivePresetConfigResolver configResolver;
    private final RainbowLivePresetEventService eventService;

    /** Métadonnées du formulaire : actifs, stablecoin, valeurs par défaut et config par défaut de chaque actif. */
    public DefaultsDto defaults() {
        Map<String, ConfigDto> configs = new LinkedHashMap<>();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor(asset);
            configs.put(asset, new ConfigDto(c.toTuning(), c.toGlobals()));
        }
        Map<String, TrendConfigDto> trendDefaults = new LinkedHashMap<>();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            trendDefaults.put(asset, RainbowLiveTrendConfigs.toDto(RainbowLiveTrendConfigs.defaults(asset)));
        }
        return new DefaultsDto(RainbowLiveDefaultPresets.ASSETS, AssetGroup.USD,
                RainbowLiveDefaultPresets.DEFAULT_NAME, RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS,
                RainbowAtrGlobals.pineDefault().baseAmount(),
                configs, Arrays.stream(ReentryMode.values()).map(Enum::name).toList(), ZONES, trendDefaults,
                Arrays.stream(RainbowSetSelector.RangeMapping.values()).map(Enum::name).toList(),
                Arrays.stream(RainbowLiveUiMode.values()).map(Enum::name).toList(),
                RainbowLiveDefaultPresets.SYSTEM_PREFIX);
    }

    /** Création des presets qui suivent les stratégies puis liste enrichie ; transaction en écriture. */
    @Transactional
    public List<PresetDto> listPresets(User user, String asset) {
        if (asset != null && !RainbowLiveDefaultPresets.ASSETS.contains(asset)) {
            throw new IllegalArgumentException("Actif non autorisé : " + asset
                    + " (autorisés : " + RainbowLiveDefaultPresets.ASSETS + ")");
        }
        presetService.ensureStrategyPresets(user);
        return toPresetDtos(asset == null ? presetService.list(user) : presetService.list(user, asset));
    }

    /** Stratégies Actif (lecture seule). */
    @Transactional(readOnly = true)
    public List<StrategyDto> strategies() {
        return strategyService.list().stream().map(RainbowLiveQueryService::toStrategyDto).toList();
    }

    public static StrategyDto toStrategyDto(RainbowAssetStrategy t) {
        boolean trend = t.getMode() == RainbowLiveMode.TREND_MIX;
        return new StrategyDto(t.getId(), t.getAssetSymbol(), t.getName(), t.getMode().name(), t.getRevision(), t.getUpdatedAt(),
                t.getAnalysisWindowMonths(),
                new ConfigDto(t.getConfig().toTuning(), t.getConfig().toGlobals()),
                trend ? RainbowLiveTrendConfigs.fromJson(t.getTrendConfigJson()) : null);
    }

    /** Historique des switchs de preset du user (filtre actif optionnel), du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<PresetEventDto> presetEvents(User user, String asset) {
        return eventService.list(user, asset).stream()
                .map(e -> new PresetEventDto(e.getId(), e.getAssetSymbol(), e.getType().name(), e.getPresetBeforeId(),
                        e.getPresetBeforeName(), e.getPresetAfterId(), e.getPresetAfterName(), e.getStrategyRevision(),
                        e.getOccurredAt(), e.getReason()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PresetDto getPreset(User user, Long presetId) {
        return toPresetDtos(List.of(presetService.get(user, presetId))).getFirst();
    }

    // ---------------------------------------------------------------- mapping

    private List<PresetDto> toPresetDtos(List<RainbowLivePreset> presets) {
        if (presets.isEmpty()) {
            return List.of();
        }
        Map<Long, RunStats> stats = runRepository.statsByPresets(presets).stream()
                .collect(Collectors.toMap(RunStats::getPresetId, Function.identity()));
        Map<Long, RainbowLiveRun> latest = runRepository.latestByPresets(presets).stream()
                .collect(Collectors.toMap(r -> r.getPreset().getId(), Function.identity()));
        return presets.stream()
                .map(p -> toPresetDto(p, stats.get(p.getId()), latest.get(p.getId()),
                        p.isTrendMix() ? RainbowLiveTrendConfigs.toDto(configResolver.trendConfig(p)) : null))
                .toList();
    }

    private PresetDto toPresetDto(RainbowLivePreset p, RunStats stats, RainbowLiveRun latest, TrendConfigDto trendConfig) {
        RainbowLivePassBlock block = latest == null ? null : latest.getSignal();
        LastRunDto lastRun = block == null ? null : new LastRunDto(latest.getDay(), block.getClose(), block.getZone(),
                block.getActionType(), block.getActionAmountUsdc(), block.getActionQuantity(), block.getActiveSet(),
                block.getTrendRegime());
        RainbowAtrConfig cfg = configResolver.config(p);
        return new PresetDto(p.getId(), p.getAssetSymbol(), p.getName(), p.isEnabled(),
                configResolver.analysisWindowMonths(p), p.getCreatedAt(), p.getUpdatedAt(),
                cfg.toTuning(), cfg.toGlobals(), stats == null ? 0 : stats.getRuns(),
                stats == null ? null : stats.getFirstDay(), lastRun,
                p.isTrendMix() ? "TREND_MIX" : "FIXED", trendConfig,
                p.isFollowingStrategy(), p.isFollowingStrategy() ? p.getAssetStrategy().getRevision() : null);
    }
}
