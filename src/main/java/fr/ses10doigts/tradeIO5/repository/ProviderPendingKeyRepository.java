package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ProviderPendingKey;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ProviderPendingKeyRepository extends JpaRepository<ProviderPendingKey, Long> {

    Optional<ProviderPendingKey> findByUserAndProviderCodeAndScope(User user, String providerCode, CredentialScope scope);

    /** Purge des paires expirées (à appeler dans une transaction). @return nombre de lignes supprimées */
    long deleteByExpiresAtBefore(LocalDateTime limit);
}
