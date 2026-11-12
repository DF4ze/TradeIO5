package fr.ses10doigts.tradeIO5.service.ssh;

import lombok.Getter;

/** Échec métier d'une opération d'accès VPS. Le message ne contient jamais de secret. */
@Getter
public class SshProvisioningException extends RuntimeException {

    public enum Kind {
        /** Entrée invalide (login, clé). */
        VALIDATION,
        /** Compte VPS déjà existant. */
        CONFLICT,
        /** Compte VPS inconnu. */
        UNKNOWN,
        /** Helper/sudo en échec, timeout, sortie inattendue. */
        INFRA
    }

    private final Kind kind;

    public SshProvisioningException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }
}
