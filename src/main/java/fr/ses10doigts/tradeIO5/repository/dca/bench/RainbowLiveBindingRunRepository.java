package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RainbowLiveBindingRunRepository extends JpaRepository<RainbowLiveBindingRun, Long> {

    Optional<RainbowLiveBindingRun> findByBindingAndDay(RainbowLiveBinding binding, LocalDate day);

    long countByBinding(RainbowLiveBinding binding);

    /** Dernier run de chaque lien (1 requête pour tous). */
    @Query("select r from RainbowLiveBindingRun r where r.binding in :bindings "
            + "and r.day = (select max(r2.day) from RainbowLiveBindingRun r2 where r2.binding = r.binding)")
    List<RainbowLiveBindingRun> latestByBindings(@Param("bindings") Collection<RainbowLiveBinding> bindings);
}
