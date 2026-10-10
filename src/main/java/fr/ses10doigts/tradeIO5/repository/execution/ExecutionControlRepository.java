package fr.ses10doigts.tradeIO5.repository.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionControlRepository extends JpaRepository<ExecutionControl, Long> {
}
