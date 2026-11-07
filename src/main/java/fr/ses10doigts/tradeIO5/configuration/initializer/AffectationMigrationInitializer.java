package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Migre les anciens presets vers les Affectations, après le seed des presets de base. Idempotent. */
@Component
@RequiredArgsConstructor
@Order(62)
public class AffectationMigrationInitializer implements CommandLineRunner {

    private final AffectationMigrationService service;

    @Override
    public void run(String... args) {
        service.migrate();
    }
}
