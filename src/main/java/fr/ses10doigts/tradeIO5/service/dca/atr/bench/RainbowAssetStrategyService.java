package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAssetStrategy;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowAssetStrategyRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stratégies Actif : le catalogue du code ({@link RainbowLiveDefaultPresets}) ne sert que de graine (insertion
 * idempotente, une stratégie à la fois, clé : actif + nom, jamais écrasée) ; ensuite System les modifie en base
 * ({@link #update}) et la {@code revision} s'incrémente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowAssetStrategyService {

    private final RainbowAssetStrategyRepository repository;
    private final DomainClock clock;

    /** Insère chaque stratégie du catalogue absente de la base ; renvoie celles créées (vide si tout est déjà là). */
    @Transactional
    public List<RainbowAssetStrategy> ensureStrategies() {
        List<RainbowAssetStrategy> created = new ArrayList<>();
        Instant now = clock.now();
        for (RainbowAssetStrategy seed : catalog(now)) {
            if (repository.existsByAssetSymbolAndName(seed.getAssetSymbol(), seed.getName())) {
                continue;
            }
            created.add(repository.save(seed));
            log.info("Stratégie Actif créée : {} / {} ({})", seed.getAssetSymbol(), seed.getName(), seed.getMode());
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<RainbowAssetStrategy> list() {
        return repository.findAllByOrderByAssetSymbolAscNameAsc();
    }

    /** Catalogue : par actif, la stratégie « TrendMix Rainbow DCA ATR » (réglages par défaut du code). */
    static List<RainbowAssetStrategy> catalog(Instant now) {
        List<RainbowAssetStrategy> out = new ArrayList<>();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            RainbowLiveTrendConfigs.Resolved trend = RainbowLiveTrendConfigs.defaults(asset);
            out.add(RainbowAssetStrategy.builder()
                    .assetSymbol(asset)
                    .name(RainbowLiveDefaultPresets.TREND_MIX_NAME)
                    .mode(RainbowLiveMode.TREND_MIX)
                    .analysisWindowMonths(RainbowLiveDefaultPresets.DEFAULT_ANALYSIS_WINDOW_MONTHS)
                    .initialCapitalUsdc(RainbowLiveDefaultPresets.DEFAULT_INITIAL_CAPITAL_USDC)
                    .createdAt(now)
                    .updatedAt(now)
                    .revision(1)
                    .config(RainbowAtrConfig.of(trend.bear().tuning(), trend.bear().globals()))
                    .trendConfigJson(RainbowLiveTrendConfigs.toJson(RainbowLiveTrendConfigs.toDto(trend)))
                    .build());
        }
        return out;
    }

    /** Modification par System : fenêtre et réglages Trend (jeux Bear/Bull inclus) ; {@code revision} + 1. */
    public record UpdateRequest(int analysisWindowMonths, TrendConfigDto trendConfig) {
    }

    @Transactional
    public RainbowAssetStrategy update(Long id, UpdateRequest req) {
        RainbowAssetStrategy s = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stratégie introuvable : " + id));
        if (req.analysisWindowMonths() < 1) {
            throw new IllegalArgumentException("analysisWindowMonths doit être ≥ 1");
        }
        if (req.trendConfig() == null) {
            throw new IllegalArgumentException("trendConfig requis");
        }
        RainbowLiveTrendConfigs.Resolved resolved = RainbowLiveTrendConfigs.normalize(s.getAssetSymbol(), req.trendConfig());
        s.setAnalysisWindowMonths(req.analysisWindowMonths());
        s.setConfig(RainbowAtrConfig.of(resolved.bear().tuning(), resolved.bear().globals()));
        s.setTrendConfigJson(RainbowLiveTrendConfigs.toJson(RainbowLiveTrendConfigs.toDto(resolved)));
        s.setUpdatedAt(clock.now());
        s.setRevision(s.getRevision() + 1);
        RainbowAssetStrategy saved = repository.save(s);
        log.info("Stratégie Actif modifiée : {} / {} -> révision {}", saved.getAssetSymbol(), saved.getName(), saved.getRevision());
        return saved;
    }

    @Transactional(readOnly = true)
    public RainbowAssetStrategy get(Long id) {
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Stratégie introuvable : " + id));
    }
}
