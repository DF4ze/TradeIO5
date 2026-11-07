package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.DefaultsDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.LastRunDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetRefDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.PresetEventDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ZoneDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
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

    private final AssetAffectationService affectationService;
    private final RainbowLiveRunRepository runRepository;
    private final AffectationConfigResolver configResolver;
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
                RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS,
                RainbowAtrGlobals.pineDefault().baseAmount(),
                configs, Arrays.stream(ReentryMode.values()).map(Enum::name).toList(), ZONES, trendDefaults,
                Arrays.stream(RainbowSetSelector.RangeMapping.values()).map(Enum::name).toList());
    }

    /** Création des Affectations manquantes (presets de base) puis liste enrichie ; transaction en écriture. */
    @Transactional
    public List<AffectationDto> listAffectations(User user, String asset) {
        if (asset != null && !RainbowLiveDefaultPresets.ASSETS.contains(asset)) {
            throw new IllegalArgumentException("Actif non autorisé : " + asset
                    + " (autorisés : " + RainbowLiveDefaultPresets.ASSETS + ")");
        }
        affectationService.ensureAffectations(user);
        return toDtos(affectationService.list(user).stream().filter(a -> asset == null || a.getAssetSymbol().equals(asset)).toList());
    }

    /** Historique des changements d'Affectation du user (filtre actif optionnel), du plus récent au plus ancien. */
    @Transactional(readOnly = true)
    public List<PresetEventDto> presetEvents(User user, String asset) {
        return eventService.list(user, asset).stream()
                .map(e -> new PresetEventDto(e.getId(), e.getAssetSymbol(), e.getType().name(), e.getPresetBeforeId(),
                        e.getPresetBeforeName(), e.getPresetAfterId(), e.getPresetAfterName(), e.getStrategyRevision(),
                        e.getOccurredAt(), e.getReason()))
                .toList();
    }

    @Transactional(readOnly = true)
    public AffectationDto getAffectation(User user, Long id) {
        return toDtos(List.of(affectationService.get(user, id))).getFirst();
    }

    // ---------------------------------------------------------------- mapping

    private List<AffectationDto> toDtos(List<AssetAffectation> affectations) {
        if (affectations.isEmpty()) {
            return List.of();
        }
        Map<Long, RunStats> stats = runRepository.statsByAffectations(affectations).stream()
                .collect(Collectors.toMap(RunStats::getAffectationId, Function.identity()));
        Map<Long, RainbowLiveRun> latest = runRepository.latestByAffectations(affectations).stream()
                .collect(Collectors.toMap(r -> r.getAffectation().getId(), Function.identity()));
        return affectations.stream().map(a -> toDto(a, stats.get(a.getId()), latest.get(a.getId()))).toList();
    }

    private AffectationDto toDto(AssetAffectation a, RunStats stats, RainbowLiveRun latest) {
        RainbowLivePassBlock block = latest == null ? null : latest.getSignal();
        LastRunDto lastRun = block == null ? null : new LastRunDto(latest.getDay(), block.getClose(), block.getZone(),
                block.getActionType(), block.getActionAmountUsdc(), block.getActionQuantity(), block.getActiveSet(),
                block.getTrendRegime());
        boolean complete = a.isComplete();
        RainbowAtrConfig cfg = complete ? configResolver.config(a) : null;
        return new AffectationDto(a.getId(), a.getAssetSymbol(), a.label(), a.isEnabled(), complete,
                complete ? configResolver.analysisWindowMonths(a) : 0, a.getCreatedAt(), a.getUpdatedAt(),
                ref(a.getTrendPreset() == null ? null : a.getTrendPreset().getId(), a.getTrendPreset() == null ? null : a.getTrendPreset().getName(),
                        a.getTrendPreset() == null ? null : a.getTrendPreset().getOrigin().name()),
                a.getRainbowUp() == null ? null : ref(a.getRainbowUp().getId(), a.getRainbowUp().getName(), a.getRainbowUp().getOrigin().name()),
                a.getRainbowDown() == null ? null : ref(a.getRainbowDown().getId(), a.getRainbowDown().getName(), a.getRainbowDown().getOrigin().name()),
                cfg == null ? null : cfg.toTuning(), cfg == null ? null : cfg.toGlobals(), stats == null ? 0 : stats.getRuns(),
                stats == null ? null : stats.getFirstDay(), lastRun,
                complete ? RainbowLiveTrendConfigs.toDto(configResolver.trendConfig(a)) : null);
    }

    private static PresetRefDto ref(Long id, String name, String origin) {
        return id == null ? null : new PresetRefDto(id, name, origin);
    }
}
