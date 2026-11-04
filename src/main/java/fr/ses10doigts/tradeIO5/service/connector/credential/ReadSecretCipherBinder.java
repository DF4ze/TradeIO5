package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Branche le chiffreur Spring sur {@link ReadSecretConverter} (instancié par Hibernate, hors conteneur). Mode strict par défaut
 * ({@code tradeio.security.secrets-strict=true}) : sans clé maître, erreur au démarrage dans le log et écriture de clés refusée.
 */
@Slf4j
@Component
public class ReadSecretCipherBinder {

    public ReadSecretCipherBinder(SecretCipher cipher, @Value("${tradeio.security.secrets-strict:true}") boolean strict) {
        ReadSecretConverter.bind(cipher);
        ReadSecretConverter.strict(strict);
        if (!cipher.isConfigured()) {
            if (strict) {
                log.error("TRADEIO_MASTER_KEY absente : écriture de toute clé API REFUSÉE, clés chiffrées illisibles, migration impossible");
            } else {
                log.warn("TRADEIO_MASTER_KEY absente, mode non strict : les clés seront écrites en CLAIR");
            }
        }
    }
}
