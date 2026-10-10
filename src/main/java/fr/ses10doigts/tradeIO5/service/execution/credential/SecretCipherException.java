package fr.ses10doigts.tradeIO5.service.execution.credential;

/** Valeur chiffrée illisible (altérée, mauvaise clé, format inconnu, ou stockée en clair). Message générique : jamais de secret. */
public class SecretCipherException extends RuntimeException {

    public SecretCipherException(String message) {
        super(message);
    }

    public SecretCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
