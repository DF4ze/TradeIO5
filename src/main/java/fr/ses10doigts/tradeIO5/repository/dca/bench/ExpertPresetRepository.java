package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.ExpertPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.Optional;

/** Requêtes communes aux repositories de presets de l'Espace Expert. */
@NoRepositoryBean
public interface ExpertPresetRepository<P extends ExpertPreset> extends JpaRepository<P, Long> {

    List<P> findByAssetSymbolAndOrigin(String assetSymbol, PresetOrigin origin);

    List<P> findByAssetSymbolAndOwner(String assetSymbol, User owner);

    boolean existsByAssetSymbolAndOriginAndName(String assetSymbol, PresetOrigin origin, String name);

    boolean existsByAssetSymbolAndOwnerAndName(String assetSymbol, User owner, String name);

    /** Lecture verrouillée pour une modification : sérialise les écritures concurrentes sur la révision. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<P> findWithLockById(Long id);
}
