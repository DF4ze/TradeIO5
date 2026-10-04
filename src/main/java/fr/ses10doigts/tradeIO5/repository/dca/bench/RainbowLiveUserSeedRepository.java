package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUserSeed;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RainbowLiveUserSeedRepository extends JpaRepository<RainbowLiveUserSeed, Long> {

    boolean existsByUser(User user);
}
