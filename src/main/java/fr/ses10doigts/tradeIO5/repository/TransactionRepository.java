package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

	boolean existsByExternalTransactionId(String tradeId);

	@Query("SELECT MAX(p.timestamp) FROM Transaction p WHERE p.user = :user AND p.webProvider = :webProvider")
	Optional<LocalDateTime> findLastTransactionDateByUserAndWebProvider(User user, WebProvider webProvider);

	List<Transaction> findByUser(User user);

	List<Transaction> findByAssetAndUserAndWebProviderAndSide(String asset, User user, WebProvider webProvider, TradeSide side);

	List<Transaction> findByWalletAndAssetAndSide(Wallet wallet, String asset, TradeSide side);

}