package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
