package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.CreatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.DuplicatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.TrendPresetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.TrendPresetRequest;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presets Trend de l'Espace Expert : consultation, création, duplication, modification, suppression (règles dans la classe mère). */
@Service
public class TrendPresetService extends AbstractExpertPresetService<TrendPreset> {

    private final TrendPresetRepository trendRepository;

    public TrendPresetService(TrendPresetRepository repository, ExpertPresetSupport support) {
        super(repository, support);
        this.trendRepository = repository;
    }

    @Transactional(readOnly = true)
    public TrendPresetDto detail(ExpertContext ctx, Long id) {
        return dto(ctx, get(ctx, id));
    }

    @Transactional
    public TrendPresetDto createPreset(ExpertContext ctx, CreatePresetRequest request) {
        return dto(ctx, create(ctx, request));
    }

    @Transactional
    public TrendPresetDto duplicatePreset(ExpertContext ctx, Long id, DuplicatePresetRequest request) {
        return dto(ctx, duplicate(ctx, id, request == null ? null : request.assetSymbol(), request == null ? null : request.name()));
    }

    @Transactional
    public TrendPresetDto saveAsSystemPreset(ExpertContext ctx, Long id, TrendPresetRequest request) {
        return dto(ctx, saveAsSystem(ctx, id, request.name(), request.referenceProvider(),
                () -> support.getValidator().trend(request.trend(), request.rangeMapping()),
                p -> {
                    p.applyParams(request.trend());
                    p.setRangeMapping(request.rangeMapping());
                }));
    }

    @Transactional
    public TrendPresetDto updatePreset(ExpertContext ctx, Long id, TrendPresetRequest request) {
        return dto(ctx, update(ctx, id, request.revision(), request.name(), request.referenceProvider(),
                () -> support.getValidator().trend(request.trend(), request.rangeMapping()),
                p -> {
                    p.applyParams(request.trend());
                    p.setRangeMapping(request.rangeMapping());
                }));
    }

    private TrendPresetDto dto(ExpertContext ctx, TrendPreset p) {
        return new TrendPresetDto(header(ctx, p), p.toParams(), p.getRangeMapping());
    }

    @Override
    protected String kind() {
        return "trend-preset";
    }

    @Override
    protected List<AssetAffectation> affectationsUsing(TrendPreset preset) {
        return support.getAffectationRepository().findByTrendPreset(preset);
    }

    @Override
    protected List<UsageRole> rolesIn(AssetAffectation a, TrendPreset preset) {
        return a.getTrendPreset() != null && a.getTrendPreset().getId().equals(preset.getId()) ? List.of(UsageRole.TREND) : List.of();
    }

    @Override
    protected Map<String, Object> settings(TrendPreset preset) {
        Map<String, Object> settings = new LinkedHashMap<>();
        PresetSettings.flatten(settings, "", preset.toParams());
        settings.put("rangeMapping", preset.getRangeMapping());
        return settings;
    }

    @Override
    protected TrendPreset copyOf(TrendPreset source, String asset, String name, PresetOrigin origin, User owner, String provider, Instant now) {
        TrendPreset copy = TrendPreset.builder().assetSymbol(asset).referenceProvider(provider).name(name).origin(origin)
                .owner(owner).revision(1).rangeMapping(source.getRangeMapping()).createdAt(now).updatedAt(now).build();
        copy.applyParams(source.toParams());
        return copy;
    }

    @Override
    protected TrendPreset baseFor(String asset) {
        return trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).orElseThrow(
                () -> new ExpertApiException(ExpertApiException.Code.PRESET_NOT_FOUND, "Preset Trend de base absent pour " + asset));
    }
}
