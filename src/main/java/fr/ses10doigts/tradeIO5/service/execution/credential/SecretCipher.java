package fr.ses10doigts.tradeIO5.service.execution.credential;

import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Chiffrement au repos des secrets TRADE : AES-256-GCM, IV aléatoire de 12 octets par valeur, format
 * {@code enc:v1:base64(iv || chiffré+tag)}. Le contexte ({@code apiKey}, {@code secretKey}, {@code passphrase}) est
 * authentifié (AAD) : une valeur recopiée d'un champ à l'autre est rejetée.
 * <p>
 * La clé maître (base64 de 32 octets) vient de la variable d'environnement {@value ExecutionDefaults#MASTER_KEY_ENV} du VPS :
 * jamais en base, jamais dans Flyway ni dans le binaire. Absente => {@link #isConfigured()} faux, tout chiffrement /
 * déchiffrement échoue explicitement ({@link MasterKeyMissingException}), <b>aucun repli en clair</b>. Présente mais invalide =>
 * échec au démarrage. Aucun secret ni fragment de clé dans les messages d'erreur.
 */
@Slf4j
@Component
public class SecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SecretCipher(@Value("${" + ExecutionDefaults.MASTER_KEY_ENV + ":}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            this.key = null;
            log.info("Chiffrement des secrets TRADE : clé maître absente, credentials TRADE inutilisables");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(masterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ExecutionDefaults.MASTER_KEY_ENV + " invalide : base64 attendu");
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException(ExecutionDefaults.MASTER_KEY_ENV + " invalide : " + KEY_BYTES
                    + " octets attendus (base64), " + raw.length + " reçus");
        }
        this.key = new SecretKeySpec(raw, "AES");
        log.info("Chiffrement des secrets TRADE : clé maître chargée");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** @throws MasterKeyMissingException clé maître absente */
    public String encrypt(String plain, String context) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + encrypted.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(encrypted, 0, out, IV_BYTES, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new SecretCipherException("Chiffrement impossible", e);
        }
    }

    /**
     * @throws MasterKeyMissingException clé maître absente
     * @throws SecretCipherException valeur non chiffrée (jamais de repli en clair), altérée ou chiffrée avec une autre clé
     */
    public String decrypt(String stored, String context) {
        requireKey();
        if (!isEncrypted(stored)) {
            throw new SecretCipherException("Valeur non chiffrée refusée (" + context + ")");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (in.length <= IV_BYTES) {
                throw new SecretCipherException("Valeur chiffrée illisible (" + context + ")");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new SecretCipherException("Valeur chiffrée illisible ou altérée (" + context + ")");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new MasterKeyMissingException();
        }
    }
}
