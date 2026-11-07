package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed des presets système de base (1 Trend + 2 Rainbow par actif BTC / ETH / PAXG). Idempotent, ne modifie jamais
 * un preset existant.
 */
@Component
@RequiredArgsConstructor
@Order(61)
public class ExpertBasePresetInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(ExpertBasePresetInitializer.class);

    private final ExpertBasePresetService service;

    @Override
    public void run(String... args) {
        logger.debug("Presets de base Expert initialisés ({} créé(s)).", service.ensureBasePresets());
    }
}
