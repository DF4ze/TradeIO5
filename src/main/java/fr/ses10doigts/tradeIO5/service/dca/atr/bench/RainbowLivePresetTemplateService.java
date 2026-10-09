package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetTemplate;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetTemplateRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Templates de presets système : catalogue des défauts du code ({@link RainbowLiveDefaultPresets}) et insertion
 * idempotente en base, un template à la fois (clé : actif + nom). Un template déjà présent n'est jamais modifié :
 * changer un défaut dans le code n'altère pas la base (il faut un nouveau nom de template ou une migration).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLivePresetTemplateService {

    private final RainbowLivePresetTemplateRepository repository;
    private final DomainClock clock;

    /** Insère chaque template du catalogue absent de la base ; renvoie ceux créés (vide si tout est déjà là). */
    @Transactional
    public List<RainbowLivePresetTemplate> ensureTemplates() {
        List<RainbowLivePresetTemplate> created = new ArrayList<>();
        Instant now = clock.now();
        for (RainbowLivePresetTemplate seed : catalog(now)) {
            if (repository.existsByAssetSymbolAndName(seed.getAssetSymbol(), seed.getName())) {
                continue;
            }
            created.add(repository.save(seed));
            log.info("Template de preset système créé : {} / {} ({})", seed.getAssetSymbol(), seed.getName(), seed.getMode());
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<RainbowLivePresetTemplate> list() {
        return repository.findAllByOrderByAssetSymbolAscNameAsc();
    }

    /** Catalogue des défauts : par actif, « Bench global » (FIXED) et « Trend Mix » (réglages par défaut du code). */
    static List<RainbowLivePresetTemplate> catalog(Instant now) {
        List<RainbowLivePresetTemplate> out = new ArrayList<>();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            out.add(RainbowLivePresetTemplate.builder()
                    .assetSymbol(asset)
                    .name(RainbowLiveDefaultPresets.DEFAULT_NAME)
                    .mode(RainbowLiveMode.FIXED)
                    .analysisWindowMonths(RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS)
                    .initialCapitalUsdc(RainbowLiveDefaultPresets.DEFAULT_INITIAL_CAPITAL_USDC)
                    .createdAt(now)
                    .config(RainbowLiveDefaultPresets.configFor(asset))
                    .build());
            RainbowLiveTrendConfigs.Resolved trend = RainbowLiveTrendConfigs.defaults(asset);
            out.add(RainbowLivePresetTemplate.builder()
                    .assetSymbol(asset)
                    .name(RainbowLiveDefaultPresets.TREND_MIX_NAME)
                    .mode(RainbowLiveMode.TREND_MIX)
                    .analysisWindowMonths(RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS)
                    .initialCapitalUsdc(RainbowLiveDefaultPresets.DEFAULT_INITIAL_CAPITAL_USDC)
                    .createdAt(now)
                    .config(RainbowAtrConfig.of(trend.bear().tuning(), trend.bear().globals()))
                    .trendConfigJson(RainbowLiveTrendConfigs.toJson(RainbowLiveTrendConfigs.toDto(trend)))
                    .build());
        }
        return out;
    }
}
