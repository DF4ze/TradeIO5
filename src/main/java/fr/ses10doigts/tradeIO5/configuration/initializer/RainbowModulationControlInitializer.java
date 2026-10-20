package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.repository.modulation.RainbowModulationControlRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Défaut de modulation : crée la ligne {@link RainbowModulationControl} (défauts de {@code ModulationDefaults}) si elle est
 * absente ; une ligne existante n'est jamais modifiée (règle des défauts, {@code docs/operations/flyway.md}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(71)
public class RainbowModulationControlInitializer implements CommandLineRunner {

    private final RainbowModulationControlRepository repository;
    private final DomainClock clock;

    @Override
    public void run(String... args) {
        if (repository.existsById(RainbowModulationControl.SINGLETON_ID)) {
            log.debug("RainbowModulationControl déjà présent");
            return;
        }
        repository.save(RainbowModulationControl.defaults(clock.now(), "initializer"));
        log.info("RainbowModulationControl créé avec les défauts de modulation");
    }
}
