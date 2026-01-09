package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.decision.strategy.indicator.IndicatorParameterSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IndicatorParameterSetRepository extends JpaRepository<IndicatorParameterSet, Long> {
}
