package fr.ses10doigts.tradeIO5.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

	boolean existsByExternalTransactionId(String tradeId);

	@Query("SELECT MAX(p.timestamp) FROM Transaction p WHERE p.user = :user AND p.provider = :provider")
	Optional<LocalDateTime> findLastTransactionDateByUserAndProvider(User user, Provider provider);

	List<Transaction> findByUser(User user);

	List<Transaction> findByAssetAndUserAndProviderAndSide(String asset, User user, Provider provider, TradeSide side);

	List<Transaction> findByWalletAndAssetAndSide(Wallet wallet, String asset, TradeSide side);

}