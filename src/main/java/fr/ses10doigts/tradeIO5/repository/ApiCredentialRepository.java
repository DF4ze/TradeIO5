package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Toutes les lectures « courantes » sont limitées à la portée {@link CredentialScope#READ} : une clé {@code TRADE}
 * n'est jamais renvoyée aux lecteurs de soldes / catalogue / indicateurs. Seul {@code TradeCredentialResolver} lit la portée TRADE.
 */
public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {

	List<ApiCredential> findByUserAndScopeAndEnabledTrue(User user, CredentialScope scope);

	Optional<ApiCredential> findByUserAndWebProviderAndScope(User user, WebProvider webProvider, CredentialScope scope);

	Optional<ApiCredential> findByUserAndScopeAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(User user,
			CredentialScope scope, WebProviderCode code);

	default List<ApiCredential> findReadByUserAndEnabledTrue(User user) {
		return findByUserAndScopeAndEnabledTrue(user, CredentialScope.READ);
	}

	default Optional<ApiCredential> findReadByUserAndWebProvider(User user, WebProvider webProvider) {
		return findByUserAndWebProviderAndScope(user, webProvider, CredentialScope.READ);
	}

	default Optional<ApiCredential> findReadByUserAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(User user,
			WebProviderCode code) {
		return findByUserAndScopeAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(user, CredentialScope.READ, code);
	}

	/** Valeurs brutes (id, api_key, secret_key, passphrase) des clés READ, telles que stockées. */
	@Query(value = "select id, api_key, secret_key, passphrase from api_credentials where scope = 'READ'", nativeQuery = true)
	List<Object[]> findRawReadSecrets();

	/** Écrit les valeurs brutes, sans passer par le convertisseur (migration de chiffrement des clés READ). */
	@Transactional
	@Modifying
	@Query(value = "update api_credentials set api_key = :apiKey, secret_key = :secretKey, passphrase = :passphrase where id = :id",
			nativeQuery = true)
	int updateRawSecrets(@Param("id") Long id, @Param("apiKey") String apiKey, @Param("secretKey") String secretKey,
			@Param("passphrase") String passphrase);
}
