package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetTemplateService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seed des templates de presets système du bench Rainbow (Bench global + Trend Mix, par actif). Idempotent : chaque
 * template absent est inséré un à un, un template existant n'est jamais modifié.
 */
@Component
@RequiredArgsConstructor
@Order(60)
public class RainbowLivePresetTemplateInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(RainbowLivePresetTemplateInitializer.class);

    private final RainbowLivePresetTemplateService templateService;

    @Override
    public void run(String... args) {
        int created = templateService.ensureTemplates().size();
        logger.info("🌈 Templates de presets Rainbow initialisés ({} créé(s)).", created);
    }
}
