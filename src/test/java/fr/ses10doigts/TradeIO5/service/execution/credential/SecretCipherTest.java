package fr.ses10doigts.tradeIO5.service.execution.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecretCipher : AES-GCM au repos, clé maître en variable d'environnement")
class SecretCipherTest {

    static String newKey() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    @Test
    @DisplayName("Aller-retour ; IV aléatoire : deux chiffrements de la même valeur diffèrent")
    void roundTripAndIv() {
        SecretCipher c = new SecretCipher(newKey());
        String a = c.encrypt("s3cret-value", "apiKey");
        String b = c.encrypt("s3cret-value", "apiKey");
        assertTrue(SecretCipher.isEncrypted(a));
        assertFalse(a.contains("s3cret-value"));
        assertNotEquals(a, b);
        assertEquals("s3cret-value", c.decrypt(a, "apiKey"));
        assertEquals("s3cret-value", c.decrypt(b, "apiKey"));
    }

    @Test
    @DisplayName("Altération (GCM) ou mauvais contexte (AAD) => échec ; autre clé => échec")
    void tamper() {
        SecretCipher c = new SecretCipher(newKey());
        String enc = c.encrypt("value", "secretKey");
        byte[] raw = Base64.getDecoder().decode(enc.substring(SecretCipher.PREFIX.length()));
        raw[raw.length - 1] ^= 1;
        String tampered = SecretCipher.PREFIX + Base64.getEncoder().encodeToString(raw);
        assertThrows(SecretCipherException.class, () -> c.decrypt(tampered, "secretKey"));
        assertThrows(SecretCipherException.class, () -> c.decrypt(enc, "passphrase"));
        assertThrows(SecretCipherException.class, () -> new SecretCipher(newKey()).decrypt(enc, "secretKey"));
    }

    @Test
    @DisplayName("Aucun repli en clair : valeur non chiffrée refusée")
    void noPlaintextFallback() {
        SecretCipher c = new SecretCipher(newKey());
        assertThrows(SecretCipherException.class, () -> c.decrypt("plain", "apiKey"));
    }

    @Test
    @DisplayName("Sans clé maître : non configuré, chiffrement et déchiffrement échouent explicitement")
    void noMasterKey() {
        SecretCipher c = new SecretCipher("");
        assertFalse(c.isConfigured());
        assertThrows(MasterKeyMissingException.class, () -> c.encrypt("x", "apiKey"));
        assertThrows(MasterKeyMissingException.class, () -> c.decrypt(SecretCipher.PREFIX + "AAAA", "apiKey"));
    }

    @Test
    @DisplayName("Clé maître invalide => échec au démarrage, sans la divulguer")
    void invalidKey() {
        String bad = Base64.getEncoder().encodeToString(new byte[5]);
        RuntimeException e = assertThrows(RuntimeException.class, () -> new SecretCipher(bad));
        assertFalse(String.valueOf(e.getMessage()).contains(bad));
    }

    @Test
    @DisplayName("Messages d'erreur sans secret")
    void noSecretInMessages() {
        SecretCipher c = new SecretCipher(newKey());
        String enc = c.encrypt("topsecret", "apiKey");
        SecretCipherException e = assertThrows(SecretCipherException.class, () -> c.decrypt(enc, "passphrase"));
        assertFalse(e.getMessage().contains("topsecret"));
        assertFalse(e.getMessage().contains(enc));
    }
}
