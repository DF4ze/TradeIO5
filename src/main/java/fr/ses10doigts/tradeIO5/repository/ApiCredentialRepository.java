package fr.ses10doigts.tradeIO5.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {


	List<ApiCredential> findByUserAndEnabledTrue(User user);

	Optional<ApiCredential> findByUserAndProvider(User user, Provider provider);

	Optional<ApiCredential> findByUserAndEnabledTrueAndProvider_CodeAndProvider_EnabledTrue(User user, String exchangeCode);


}