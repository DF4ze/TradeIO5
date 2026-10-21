package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import org.springframework.stereotype.Component;

/** Branche le chiffreur Spring sur {@link ReadSecretConverter} (instancié par Hibernate, hors conteneur). */
@Component
public class ReadSecretCipherBinder {

    public ReadSecretCipherBinder(SecretCipher cipher) {
        ReadSecretConverter.bind(cipher);
    }
}
