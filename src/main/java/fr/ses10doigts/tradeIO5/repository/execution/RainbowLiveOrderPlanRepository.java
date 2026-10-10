package fr.ses10doigts.tradeIO5.repository.execution;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RainbowLiveOrderPlanRepository extends JpaRepository<RainbowLiveOrderPlan, Long> {

    /** Révision courante (la plus récente) du (user, jour, passe). */
    Optional<RainbowLiveOrderPlan> findFirstByUserAndDayAndPassOrderByRevisionDesc(User user, LocalDate day, RainbowLivePass pass);

    /** Plans courants (hors remplacés) d'un utilisateur entre deux jours inclus, du plus récent au plus ancien. */
    List<RainbowLiveOrderPlan> findByUserAndStatusNotAndDayBetweenOrderByDayDescCreatedAtDesc(User user, PlanStatus status,
                                                                                         LocalDate from, LocalDate to);

    Optional<RainbowLiveOrderPlan> findFirstByUserAndStatusNotOrderByDayDescCreatedAtDesc(User user, PlanStatus status);

    /** Plans à réconcilier au démarrage (exécution commencée, pas terminée). */
    List<RainbowLiveOrderPlan> findByStatus(PlanStatus status);

    /**
     * Prise en charge atomique d'un plan {@code PLANNED} : un seul appelant obtient 1 (deux déclenchements concurrents
     * n'envoient jamais deux fois les mêmes ordres).
     */
    @Modifying
    @Query("update RainbowLiveOrderPlan p set p.status = :to, p.executionStartedAt = :now where p.id = :id and p.status = :from")
    int transition(@Param("id") Long id, @Param("from") PlanStatus from, @Param("to") PlanStatus to, @Param("now") Instant now);
}
