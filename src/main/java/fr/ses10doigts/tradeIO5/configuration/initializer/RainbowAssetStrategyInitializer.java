package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowAssetStrategyService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed des stratégies Actif du bench Rainbow (TrendMix Rainbow DCA ATR, une par actif). Idempotent : chaque stratégie
 * absente est insérée, une stratégie existante n'est jamais modifiée (System la modifie ensuite en base).
 */
@Component
@RequiredArgsConstructor
@Order(60)
public class RainbowAssetStrategyInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(RainbowAssetStrategyInitializer.class);

    private final RainbowAssetStrategyService strategyService;

    @Override
    public void run(String... args) {
        int created = strategyService.ensureStrategies().size();
        logger.debug("🌈 Stratégies Actif Rainbow initialisées ({} créé(s)).", created);
    }
}
