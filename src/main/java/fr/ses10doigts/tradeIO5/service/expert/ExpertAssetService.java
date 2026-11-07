package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AssetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.ProviderDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.EligibilityDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.service.expert.simulation.SimulationEligibility;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Actifs proposés : pour l'instant ceux dont les presets de base existent (la découverte d'actifs est un chantier à part).
 * Lecture seule, aucun appel exchange ; l'éligibilité à la simulation est renseignée par le moteur de simulation.
 */
@Service
@RequiredArgsConstructor
public class ExpertAssetService {

    private final AssetRepository assetRepository;
    private final ExpertAssets assets;
    private final TrendPresetRepository trendRepository;
    private final AssetAffectationRepository affectationRepository;
    private final RainbowPresetRepository rainbowRepository;
    private final SimulationEligibility simulationEligibility;

    @Transactional(readOnly = true)
    public List<AssetDto> list(ExpertContext ctx) {
        return RainbowLiveDefaultPresets.ASSETS.stream()
                .filter(symbol -> trendRepository.findByAssetSymbolAndBaseRole(symbol, BasePresetRole.TREND).isPresent())
                .map(symbol -> {
                    List<ProviderDto> providers = assets.providers(symbol).stream()
                            .map(p -> new ProviderDto(p.getSource().name(), p.getMaxHorizonDays())).toList();
                    String name = assetRepository.findBySymbol(symbol).map(a -> a.getName()).orElse(symbol);
                    boolean exploitable = affectationRepository.findByUserAndAssetSymbol(ctx.user(), symbol)
                            .map(AssetAffectation::isComplete).orElse(false);
                    return new AssetDto(symbol, name, providers, exploitable, !providers.isEmpty(), eligibility(ctx, symbol));
                }).toList();
    }

    /**
     * Historique D1 requis pour simuler l'Affectation de l'utilisateur (à défaut, les presets système de base) et bougies déjà en
     * cache par provider. Aucun appel exchange.
     */
    private EligibilityDto eligibility(ExpertContext ctx, String symbol) {
        AssetAffectation affectation = affectationRepository.findByUserAndAssetSymbol(ctx.user(), symbol).orElse(null);
        TrendPreset trend = affectation != null && affectation.getTrendPreset() != null ? affectation.getTrendPreset()
                : trendRepository.findByAssetSymbolAndBaseRole(symbol, BasePresetRole.TREND).orElse(null);
        RainbowPreset up = affectation != null && affectation.getRainbowUp() != null ? affectation.getRainbowUp()
                : rainbowRepository.findByAssetSymbolAndBaseRole(symbol, BasePresetRole.RAINBOW_BULL).orElse(null);
        RainbowPreset down = affectation != null && affectation.getRainbowDown() != null ? affectation.getRainbowDown()
                : rainbowRepository.findByAssetSymbolAndBaseRole(symbol, BasePresetRole.RAINBOW_BEAR).orElse(null);
        int required = Stream.of(up, down).filter(Objects::nonNull)
                .mapToInt(p -> SimulationEligibility.requiredPoints(trend == null ? null : trend.toParams(),
                        p.getConfig().toTuning(), p.getAnalysisWindowMonths()))
                .max().orElse(SimulationEligibility.requiredPoints(trend == null ? null : trend.toParams(), null, null));
        return simulationEligibility.forAsset(symbol, required);
    }
}
