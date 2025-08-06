package fr.ses10doigts.tradeIO5.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

	List<Wallet> findByUser(User utilisateur);

}
