package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface RainbowLiveEngineStateRepository extends JpaRepository<RainbowLiveEngineState, Long> {

    Optional<RainbowLiveEngineState> findByAffectationAndDay(AssetAffectation affectation, LocalDate day);

    /** Purge de la chaîne d'états d'une Affectation (resynchronisation, changement de presets) : tout état antérieur ou postérieur issu d'une chaîne faussée est périmé. */
    @org.springframework.transaction.annotation.Transactional
    void deleteByAffectation(AssetAffectation affectation);

    /** État de fin du dernier jour strictement antérieur à {@code day}. */
    Optional<RainbowLiveEngineState> findFirstByAffectationAndDayBeforeOrderByDayDesc(AssetAffectation affectation, LocalDate day);
}
