package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RainbowLiveRunRepository extends JpaRepository<RainbowLiveRun, Long> {

    Optional<RainbowLiveRun> findByPresetAndDay(RainbowLivePreset preset, LocalDate day);

    List<RainbowLiveRun> findByPresetOrderByDayAsc(RainbowLivePreset preset);

    long countByPreset(RainbowLivePreset preset);

    /** Runs d'un preset entre deux jours (inclus), ordre chronologique. */
    List<RainbowLiveRun> findByPresetAndDayBetweenOrderByDayAsc(RainbowLivePreset preset, LocalDate from, LocalDate to);

    /** Run immédiatement antérieur à {@code day} (référence du « config modifiée » du premier run d'une plage). */
    Optional<RainbowLiveRun> findFirstByPresetAndDayBeforeOrderByDayDesc(RainbowLivePreset preset, LocalDate day);

    /** Agrégats par preset (1 requête pour tous). */
    @Query("select r.preset.id as presetId, count(r) as runs, min(r.day) as firstDay "
            + "from RainbowLiveRun r where r.preset in :presets group by r.preset.id")
    List<RunStats> statsByPresets(@Param("presets") Collection<RainbowLivePreset> presets);

    /** Dernier run de chaque preset (1 requête pour tous). */
    @Query("select r from RainbowLiveRun r where r.preset in :presets "
            + "and r.day = (select max(r2.day) from RainbowLiveRun r2 where r2.preset = r.preset)")
    List<RainbowLiveRun> latestByPresets(@Param("presets") Collection<RainbowLivePreset> presets);

    interface RunStats {
        Long getPresetId();

        long getRuns();

        LocalDate getFirstDay();
    }
}
