package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AssignRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.PresetRefDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.ProviderGapDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.ExpertPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveCandleSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lecture et écriture de l'Affectation de l'utilisateur par actif. L'écriture s'appuie sur
 * {@link AssetAffectationService#assign} (contrôles d'actif et de propriété, purge moteur, événement) ; l'activation reste
 * gérée par {@code PATCH /api/rainbow-live/affectations/{id}/enabled}. Le signalement d'écart de provider est une
 * information, jamais un blocage.
 */
@Service
@RequiredArgsConstructor
public class ExpertAffectationService {

    private final AssetAffectationService affectationService;
    private final TrendPresetService trendService;
    private final RainbowPresetService rainbowService;
    private final RainbowLiveBindingRepository bindingRepository;
    private final ExpertAssets assets;

    /** Affectations de l'utilisateur (une par actif, créées sur les presets de base si absentes). */
    @Transactional
    public List<AffectationDto> list(ExpertContext ctx) {
        affectationService.ensureAffectations(ctx.user());
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user());
        return affectationService.list(ctx.user()).stream().map(a -> dto(a, bindings)).toList();
    }

    @Transactional
    public AffectationDto get(ExpertContext ctx, String asset) {
        AssetAffectation a = affectationService.forAsset(ctx.user(), assets.requireAsset(asset));
        return dto(a, bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user()));
    }

    /** Installe Trend + Rainbow UP + Rainbow DOWN ; un preset invisible de l'appelant (USER d'un autre) ⇒ 404. */
    @Transactional
    public AffectationDto assign(ExpertContext ctx, String asset, AssignRequest request) {
        String symbol = assets.requireAsset(asset);
        trendService.get(ctx, require("trendPresetId", request.trendPresetId()));
        rainbowService.get(ctx, require("rainbowUpId", request.rainbowUpId()));
        rainbowService.get(ctx, require("rainbowDownId", request.rainbowDownId()));
        AssetAffectation a = affectationService.assign(ctx.user(), symbol, request.trendPresetId(), request.rainbowUpId(),
                request.rainbowDownId());
        return dto(a, bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user()));
    }

    private static Long require(String field, Long id) {
        if (id == null) {
            throw new ExpertValidationException(field, "obligatoire");
        }
        return id;
    }

    static AffectationDto dto(AssetAffectation a, List<RainbowLiveBinding> userBindings) {
        List<String> providers = userBindings.stream().filter(b -> b.getAssetSymbol().equals(a.getAssetSymbol()))
                .map(b -> LiveCandleSource.of(b.getWallet())).filter(Objects::nonNull).map(MarketDataSource::name).distinct().sorted().toList();
        List<ProviderGapDto> gaps = new ArrayList<>();
        gap(gaps, "TREND", a.getTrendPreset(), providers);
        gap(gaps, "RAINBOW_UP", a.getRainbowUp(), providers);
        gap(gaps, "RAINBOW_DOWN", a.getRainbowDown(), providers);
        return new AffectationDto(a.getId(), a.getAssetSymbol(), a.isEnabled(), a.isComplete(), ref(a.getTrendPreset()), ref(a.getRainbowUp()),
                ref(a.getRainbowDown()), gaps, a.getUpdatedAt());
    }

    private static void gap(List<ProviderGapDto> out, String role, ExpertPreset preset, List<String> bindingProviders) {
        if (preset == null) {
            return;
        }
        String ref = preset.getReferenceProvider();
        boolean differs = ref != null && bindingProviders.stream().anyMatch(p -> !p.equals(ref));
        out.add(new ProviderGapDto(role, preset.getId(), preset.getName(), ref, bindingProviders, differs));
    }

    private static PresetRefDto ref(ExpertPreset p) {
        return p == null ? null : new PresetRefDto(p.getId(), p.getName(), p.getOrigin().name(), p.getReferenceProvider());
    }
}
