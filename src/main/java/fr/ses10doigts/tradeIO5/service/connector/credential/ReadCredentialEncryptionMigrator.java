package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Au démarrage, chiffre les secrets {@code READ} encore en clair (clés historiques, graines des initializers). Sans clé maître :
 * ne fait rien. Idempotent ; ne touche ni aux valeurs vides ni aux clés TRADE. Passe après les initializers
 * ({@code @Order(40)}) qui peuvent semer des clés en clair.
 */
@Slf4j
@Component
@Order(45)
@RequiredArgsConstructor
public class ReadCredentialEncryptionMigrator implements CommandLineRunner {

    private final ApiCredentialRepository repository;

    @Override
    public void run(String... args) {
        migrate();
    }

    /** @return nombre de credentials READ chiffrées par cet appel */
    public int migrate() {
        if (!ReadSecretConverter.active()) {
            log.info("Chiffrement des clés READ : clé maître absente, migration ignorée");
            return 0;
        }
        int done = 0;
        for (Object[] row : repository.findRawReadSecrets()) {
            Long id = ((Number) row[0]).longValue();
            String apiKey = (String) row[1];
            String secretKey = (String) row[2];
            String passphrase = (String) row[3];
            String sealedApiKey = ReadSecretConverter.seal(apiKey);
            String sealedSecretKey = ReadSecretConverter.seal(secretKey);
            String sealedPassphrase = ReadSecretConverter.seal(passphrase);
            if (!same(sealedApiKey, apiKey) || !same(sealedSecretKey, secretKey) || !same(sealedPassphrase, passphrase)) {
                repository.updateRawSecrets(id, sealedApiKey, sealedSecretKey, sealedPassphrase);
                done++;
            }
        }
        if (done > 0) {
            log.info("Chiffrement des clés READ : {} credential(s) chiffrée(s)", done);
        }
        return done;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
