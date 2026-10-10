package fr.ses10doigts.tradeIO5.repository.execution;

import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface RainbowLiveOrderStepRepository extends JpaRepository<RainbowLiveOrderStep, Long> {

    /** Étapes d'un utilisateur envoyées depuis {@code since} (consommation du plafond journalier). */
    @Query("select s from RainbowLiveOrderStep s where s.plan.user = :user and s.submittedAt >= :since and s.status in :statuses")
    List<RainbowLiveOrderStep> findSentSince(@Param("user") User user, @Param("since") Instant since,
                                             @Param("statuses") Collection<StepStatus> statuses);
}
