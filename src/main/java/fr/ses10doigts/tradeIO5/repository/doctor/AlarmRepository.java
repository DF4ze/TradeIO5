package fr.ses10doigts.tradeIO5.repository.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.Alarm;
import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    /** Alarme non résolue (ACTIVE ou ACKED) portant cette clé, la plus récente. */
    Optional<Alarm> findFirstByAlarmKeyAndStateNotOrderByIdDesc(String alarmKey, AlarmState excluded);

    /** Dernière alarme résolue portant cette clé (pour la rouvrir si le défaut revient peu après). */
    Optional<Alarm> findFirstByAlarmKeyAndStateAndResolvedAtAfterOrderByIdDesc(String alarmKey, AlarmState state, Instant after);

    List<Alarm> findByCheckIdAndStateNot(String checkId, AlarmState excluded);

    List<Alarm> findByStateInOrderByLastSeenDesc(Collection<AlarmState> states);

    List<Alarm> findAllByOrderByLastSeenDesc();

    long deleteByStateAndResolvedAtBefore(AlarmState state, Instant cutoff);
}
