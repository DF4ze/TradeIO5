package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import fr.ses10doigts.tradeIO5.security.model.User;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RainbowLiveRunRepository extends JpaRepository<RainbowLiveRun, Long> {

    Optional<RainbowLiveRun> findByAffectationAndDay(AssetAffectation affectation, LocalDate day);

    List<RainbowLiveRun> findByAffectationOrderByDayAsc(AssetAffectation affectation);

    /** Supprime les runs d'un utilisateur pour un jour (recalcul forcé du jour : cf. {@code RainbowLiveRunService#discardDay}). */
    long deleteByUserAndDay(User user, LocalDate day);

    long countByAffectation(AssetAffectation affectation);

    /** Agrégats par Affectation (1 requête pour tous). */
    @Query("select r.affectation.id as affectationId, count(r) as runs, min(r.day) as firstDay "
            + "from RainbowLiveRun r where r.affectation in :affectations group by r.affectation.id")
    List<RunStats> statsByAffectations(@Param("affectations") Collection<AssetAffectation> affectations);

    /** Dernier run de chaque Affectation (1 requête pour tous). */
    @Query("select r from RainbowLiveRun r where r.affectation in :affectations "
            + "and r.day = (select max(r2.day) from RainbowLiveRun r2 where r2.affectation = r.affectation)")
    List<RainbowLiveRun> latestByAffectations(@Param("affectations") Collection<AssetAffectation> affectations);

    interface RunStats {
        Long getAffectationId();

        long getRuns();

        LocalDate getFirstDay();
    }
}
