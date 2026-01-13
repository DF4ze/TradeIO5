package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {


	List<ApiCredential> findByUserAndEnabledTrue(User user);

	Optional<ApiCredential> findByUserAndWebProvider(User user, WebProvider webProvider);

	Optional<ApiCredential> findByUserAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(User user, String exchangeCode);


}