package fr.ses10doigts.tradeIO5.repository.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface JobRunRepository extends JpaRepository<JobRun, Long> {

    Optional<JobRun> findFirstByJobNameOrderByStartedAtDesc(String jobName);

    Page<JobRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    long deleteByStartedAtBefore(Instant cutoff);
}
