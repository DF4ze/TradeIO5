package fr.ses10doigts.tradeIO5.repository.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {

    boolean existsByUserIdAndWalletIdAndDay(Long userId, Long walletId, LocalDate day);

    List<PortfolioSnapshot> findByUserIdAndDayBetweenOrderByDayAsc(Long userId, LocalDate from, LocalDate to);

    /** Dernier instantané de chaque wallet de l'utilisateur. */
    @Query("select s from PortfolioSnapshot s where s.user.id = :userId and s.day = "
            + "(select max(s2.day) from PortfolioSnapshot s2 where s2.wallet = s.wallet and s2.user.id = :userId)")
    List<PortfolioSnapshot> latestByUser(@Param("userId") Long userId);
}
