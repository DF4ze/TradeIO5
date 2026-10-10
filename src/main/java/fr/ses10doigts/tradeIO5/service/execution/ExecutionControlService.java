package fr.ses10doigts.tradeIO5.service.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.repository.execution.ExecutionControlRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accès à la ligne globale {@link ExecutionControl}. Aucune mise en cache : l'exécuteur relit à chaque étape, donc le kill
 * switch agit aussitôt. Ligne absente => kill switch engagé (échec fermé).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionControlService {

    private final ExecutionControlRepository repository;
    private final DomainClock clock;

    @Transactional(readOnly = true)
    public ExecutionControl current() {
        return repository.findById(ExecutionControl.SINGLETON_ID).orElseGet(ExecutionControl::failClosed);
    }

    @Transactional
    public ExecutionControl setKillSwitch(boolean engaged, String by) {
        ExecutionControl control = repository.findById(ExecutionControl.SINGLETON_ID)
                .orElseGet(() -> ExecutionControl.initial(clock.now()));
        control.setKillSwitch(engaged);
        control.setUpdatedAt(clock.now());
        control.setUpdatedBy(by);
        ExecutionControl saved = repository.save(control);
        log.info("Kill switch d'exécution {} par {}", engaged ? "ENGAGÉ" : "levé", by);
        return saved;
    }
}
