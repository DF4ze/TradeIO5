package fr.ses10doigts.tradeIO5.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

	List<Wallet> findByEnabledTrue();

	List<Wallet> findByUserAndEnabledTrue(User utilisateur);

	Optional<Wallet> findByUserAndName(User user, String walletName);
}
