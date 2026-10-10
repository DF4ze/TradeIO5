package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.repository.execution.ExecutionControlRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Défaut d'exécution : crée la ligne {@link ExecutionControl} si elle est absente (kill switch ENGAGÉ, multiplicateurs par
 * défaut) ; une ligne existante n'est jamais modifiée (règle des défauts, {@code docs/operations/flyway.md}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(70)
public class ExecutionControlInitializer implements CommandLineRunner {

    private final ExecutionControlRepository repository;
    private final DomainClock clock;

    @Override
    public void run(String... args) {
        if (repository.existsById(ExecutionControl.SINGLETON_ID)) {
            log.debug("ExecutionControl déjà présent");
            return;
        }
        repository.save(ExecutionControl.initial(clock.now()));
        log.info("ExecutionControl créé : kill switch engagé, plafonds ordre {} × / jour {} × baseAmount",
                ExecutionDefaults.DEFAULT_MAX_ORDER_MULTIPLIER,
                ExecutionDefaults.DEFAULT_MAX_DAY_MULTIPLIER);
    }
}
