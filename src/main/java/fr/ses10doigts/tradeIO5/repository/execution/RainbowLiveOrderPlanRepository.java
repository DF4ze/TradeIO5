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

    /** Plans courants (hors remplacés) de tous les utilisateurs (ou d'un seul si {@code userId} non nul) entre deux jours inclus. */
    @Query("select p from RainbowLiveOrderPlan p where p.status <> :excluded and p.day between :from and :to "
            + "and (:userId is null or p.user.id = :userId) order by p.day desc, p.createdAt desc")
    List<RainbowLiveOrderPlan> findAdminPlans(@Param("excluded") PlanStatus excluded, @Param("from") LocalDate from,
                                              @Param("to") LocalDate to, @Param("userId") Long userId);

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
