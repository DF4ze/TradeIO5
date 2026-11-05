package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface RainbowLiveEngineStateRepository extends JpaRepository<RainbowLiveEngineState, Long> {

    Optional<RainbowLiveEngineState> findByPresetAndDay(RainbowLivePreset preset, LocalDate day);

    /** Purge de la chaîne d'états d'un preset (resynchronisation) : tout état antérieur ou postérieur issu d'une chaîne faussée est périmé. */
    @org.springframework.transaction.annotation.Transactional
    void deleteByPreset(RainbowLivePreset preset);

    /** État de fin du dernier jour strictement antérieur à {@code day}. */
    Optional<RainbowLiveEngineState> findFirstByPresetAndDayBeforeOrderByDayDesc(RainbowLivePreset preset, LocalDate day);
}
