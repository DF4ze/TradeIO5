package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Presets système de base : par actif (BTC / ETH / PAXG) 1 {@link TrendPreset} + 2 {@link RainbowPreset} (Bull, Bear)
 * semés depuis les constantes du code ({@link RainbowLiveTrendConfigs#defaults}). Insertion idempotente par
 * (actif, rôle de base) ; un preset existant n'est jamais modifié (l'admin le modifie en base).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpertBasePresetService {

    public static final String TREND_BASE_NAME = "Trend de base";

    private final TrendPresetRepository trendRepository;
    private final RainbowPresetRepository rainbowRepository;
    private final DomainClock clock;

    /** Insère les presets de base absents ; renvoie le nombre créé (0 si tout est déjà là). */
    @Transactional
    public int ensureBasePresets() {
        Instant now = clock.now();
        int created = 0;
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            RainbowLiveTrendConfigs.Resolved d = RainbowLiveTrendConfigs.defaults(asset);
            created += ensureTrend(asset, d, now) ? 1 : 0;
            created += ensureRainbow(asset, d.bull(), BasePresetRole.RAINBOW_BULL, now) ? 1 : 0;
            created += ensureRainbow(asset, d.bear(), BasePresetRole.RAINBOW_BEAR, now) ? 1 : 0;
        }
        return created;
    }

    private boolean ensureTrend(String asset, RainbowLiveTrendConfigs.Resolved d, Instant now) {
        if (trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).isPresent()) {
            return false;
        }
        TrendPreset t = TrendPreset.builder()
                .assetSymbol(asset)
                .name(TREND_BASE_NAME)
                .origin(PresetOrigin.SYSTEM)
                .baseRole(BasePresetRole.TREND)
                .revision(1)
                .rangeMapping(d.range().name())
                .createdAt(now)
                .updatedAt(now)
                .build();
        t.applyParams(d.trend());
        trendRepository.save(t);
        log.info("Preset Trend de base créé : {}", asset);
        return true;
    }

    private boolean ensureRainbow(String asset, RainbowAtrParamSet set, BasePresetRole role, Instant now) {
        if (rainbowRepository.findByAssetSymbolAndBaseRole(asset, role).isPresent()) {
            return false;
        }
        rainbowRepository.save(RainbowPreset.builder()
                .assetSymbol(asset)
                .name(set.name())
                .origin(PresetOrigin.SYSTEM)
                .baseRole(role)
                .revision(1)
                .analysisWindowMonths(RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS)
                .config(RainbowAtrConfig.of(set.tuning(), set.globals()))
                .createdAt(now)
                .updatedAt(now)
                .build());
        log.info("Preset Rainbow de base créé : {} / {}", asset, role);
        return true;
    }
}
