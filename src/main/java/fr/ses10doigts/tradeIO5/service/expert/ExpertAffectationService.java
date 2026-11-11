package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationRequest;
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
 * Lecture et écriture des Affectations de l'utilisateur (plusieurs par actif, nommées). L'écriture s'appuie sur
 * {@link AssetAffectationService} (contrôles d'actif et de propriété, instance Système en lecture seule, purge moteur,
 * événement) ; une Affectation est jouée si un lien la référence. Le signalement d'écart de provider est une information,
 * jamais un blocage.
 */
@Service
@RequiredArgsConstructor
public class ExpertAffectationService {

    private final AssetAffectationService affectationService;
    private final TrendPresetService trendService;
    private final RainbowPresetService rainbowService;
    private final RainbowLiveBindingRepository bindingRepository;
    private final ExpertAssets assets;

    /** Affectations de l'utilisateur (instances Système créées sur les presets de base si absentes), filtre d'actif optionnel. */
    @Transactional
    public List<AffectationDto> list(ExpertContext ctx, String asset) {
        String symbol = asset == null ? null : assets.requireAsset(asset);
        affectationService.ensureAffectations(ctx.user());
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user());
        return affectationService.list(ctx.user()).stream().filter(a -> symbol == null || a.getAssetSymbol().equals(symbol))
                .map(a -> dto(a, bindings)).toList();
    }

    @Transactional(readOnly = true)
    public AffectationDto get(ExpertContext ctx, Long id) {
        return dto(affectationService.get(ctx.user(), id), bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user()));
    }

    /** Crée une Affectation utilisateur ; un preset invisible de l'appelant (USER d'un autre) ⇒ 404, nom pris ⇒ 409. */
    @Transactional
    public AffectationDto create(ExpertContext ctx, AffectationRequest request) {
        String symbol = assets.requireAsset(require("assetSymbol", request.assetSymbol()));
        checkPresets(ctx, request);
        AssetAffectation a = affectationService.create(ctx.user(), symbol, request.name(), request.trendPresetId(),
                request.rainbowUpId(), request.rainbowDownId());
        return dto(a, bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user()));
    }

    /** Renomme et/ou change les presets (le moteur est réinitialisé si un preset change) ; instance Système ⇒ 403. */
    @Transactional
    public AffectationDto update(ExpertContext ctx, Long id, AffectationRequest request) {
        checkPresets(ctx, request);
        AssetAffectation a = affectationService.update(ctx.user(), id, request.name(), request.trendPresetId(), request.rainbowUpId(),
                request.rainbowDownId());
        return dto(a, bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(ctx.user()));
    }

    /** Supprime une Affectation utilisateur jamais liée ; Système ⇒ 403, déjà utilisée ⇒ 409. */
    @Transactional
    public void delete(ExpertContext ctx, Long id) {
        affectationService.delete(ctx.user(), id);
    }

    private void checkPresets(ExpertContext ctx, AffectationRequest request) {
        trendService.get(ctx, require("trendPresetId", request.trendPresetId()));
        rainbowService.get(ctx, require("rainbowUpId", request.rainbowUpId()));
        rainbowService.get(ctx, require("rainbowDownId", request.rainbowDownId()));
    }

    private static <T> T require(String field, T id) {
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
        boolean active = userBindings.stream().anyMatch(b -> b.getAffectation() != null && a.getId().equals(b.getAffectation().getId()));
        return new AffectationDto(a.getId(), a.getAssetSymbol(), a.displayName(), a.isSystem(), active, a.isComplete(), ref(a.getTrendPreset()), ref(a.getRainbowUp()),
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
