package fr.ses10doigts.tradeIO5.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.Exchange;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {

	List<ApiCredential> findByEnabledTrue();

	List<ApiCredential> findByUserAndEnabledTrue(User user);

    Optional<ApiCredential> findByUserAndExchangeAndEnabledTrue(User user, Exchange exchange);

	Optional<ApiCredential> findByUserAndExchange(User user, Exchange exchange);

	Optional<ApiCredential> findByUserAndExchange_CodeAndEnabledTrue(User user, String exchangeCode);

	boolean existsByUserAndExchange_CodeAndEnabledTrue(User user, String exchangeCode);


}