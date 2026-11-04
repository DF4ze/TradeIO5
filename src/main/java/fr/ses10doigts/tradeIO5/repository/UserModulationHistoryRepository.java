package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.user.UserModulationHistory;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserModulationHistoryRepository extends JpaRepository<UserModulationHistory, Long> {

    /** Les 50 derniers changements de l'utilisateur, du plus récent au plus ancien. */
    List<UserModulationHistory> findTop50ByUserOrderByOccurredAtDescIdDesc(User user);
}
