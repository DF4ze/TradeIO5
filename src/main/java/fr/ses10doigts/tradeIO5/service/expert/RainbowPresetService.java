package fr.ses10doigts.tradeIO5.service.expert;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.CreatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.DuplicatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.RainbowPresetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.RainbowPresetRequest;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Presets Rainbow de l'Espace Expert : consultation, création, duplication, modification, suppression (règles dans la classe mère). */
@Service
public class RainbowPresetService extends AbstractExpertPresetService<RainbowPreset> {

    private final RainbowPresetRepository rainbowRepository;

    public RainbowPresetService(RainbowPresetRepository repository, ExpertPresetSupport support) {
        super(repository, support);
        this.rainbowRepository = repository;
    }

    @Transactional(readOnly = true)
    public RainbowPresetDto detail(ExpertContext ctx, Long id) {
        return dto(ctx, get(ctx, id));
    }

    @Transactional
    public RainbowPresetDto createPreset(ExpertContext ctx, CreatePresetRequest request) {
        return dto(ctx, create(ctx, request));
    }

    @Transactional
    public RainbowPresetDto duplicatePreset(ExpertContext ctx, Long id, DuplicatePresetRequest request) {
        return dto(ctx, duplicate(ctx, id, request == null ? null : request.assetSymbol(), request == null ? null : request.name()));
    }

    @Transactional
    public RainbowPresetDto updatePreset(ExpertContext ctx, Long id, RainbowPresetRequest request) {
        return dto(ctx, update(ctx, id, request.revision(), request.name(), request.referenceProvider(),
                () -> support.getValidator().rainbow(request.analysisWindowMonths(), request.tuning(), request.globals()),
                p -> {
                    p.setAnalysisWindowMonths(request.analysisWindowMonths());
                    p.setConfig(RainbowAtrConfig.of(request.tuning(), request.globals()));
                }));
    }

    private RainbowPresetDto dto(ExpertContext ctx, RainbowPreset p) {
        return new RainbowPresetDto(header(ctx, p), p.getAnalysisWindowMonths(), p.getConfig().toTuning(), p.getConfig().toGlobals());
    }

    @Override
    protected String kind() {
        return "rainbow-preset";
    }

    @Override
    protected List<AssetAffectation> affectationsUsing(RainbowPreset preset) {
        return support.getAffectationRepository().findUsingRainbow(preset);
    }

    @Override
    protected List<UsageRole> rolesIn(AssetAffectation a, RainbowPreset preset) {
        List<UsageRole> roles = new ArrayList<>();
        if (a.getRainbowUp() != null && a.getRainbowUp().getId().equals(preset.getId())) {
            roles.add(UsageRole.RAINBOW_UP);
        }
        if (a.getRainbowDown() != null && a.getRainbowDown().getId().equals(preset.getId())) {
            roles.add(UsageRole.RAINBOW_DOWN);
        }
        return roles;
    }

    @Override
    protected Map<String, Object> settings(RainbowPreset preset) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("analysisWindowMonths", preset.getAnalysisWindowMonths());
        PresetSettings.flatten(settings, "", preset.getConfig().toTuning());
        PresetSettings.flatten(settings, "globals.", preset.getConfig().toGlobals());
        return settings;
    }

    @Override
    protected RainbowPreset copyOf(RainbowPreset source, String asset, String name, User owner, String provider, Instant now) {
        return RainbowPreset.builder().assetSymbol(asset).referenceProvider(provider).name(name).origin(PresetOrigin.USER)
                .owner(owner).revision(1).analysisWindowMonths(source.getAnalysisWindowMonths())
                .config(RainbowAtrConfig.of(source.getConfig().toTuning(), source.getConfig().toGlobals())).createdAt(now).updatedAt(now).build();
    }

    /** Point de départ par défaut d'un nouveau Rainbow : le Bear de l'actif (jeu prudent, joué en DOWN). */
    @Override
    protected RainbowPreset baseFor(String asset) {
        return rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BEAR).orElseThrow(
                () -> new ExpertApiException(ExpertApiException.Code.PRESET_NOT_FOUND, "Preset Rainbow de base absent pour " + asset));
    }
}
