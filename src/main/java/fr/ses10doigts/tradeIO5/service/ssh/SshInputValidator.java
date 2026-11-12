package fr.ses10doigts.tradeIO5.service.ssh;

import java.util.regex.Pattern;

/** Validation stricte login + clé publique AVANT tout appel au helper (défense en profondeur : le script revalide). */
public final class SshInputValidator {

    public static final Pattern LOGIN = Pattern.compile("^[a-z][a-z0-9]{2,20}$");
    public static final Pattern PUBLIC_KEY = Pattern.compile("^ssh-ed25519 [A-Za-z0-9+/=]+( [A-Za-z0-9._@-]+)?$");
    public static final int PUBLIC_KEY_MAX = 512;

    private SshInputValidator() {
    }

    public static boolean isValidLogin(String login) {
        return login != null && LOGIN.matcher(login).matches();
    }

    public static String requireLogin(String login) {
        if (!isValidLogin(login)) {
            throw new SshProvisioningException(SshProvisioningException.Kind.VALIDATION,
                    "Login invalide (a-z0-9, 3 à 21 caractères, commence par une lettre)");
        }
        return login;
    }

    /** Clé nettoyée (trim) ; une seule ligne {@code ssh-ed25519} exigée. */
    public static String requirePublicKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty() || k.length() > PUBLIC_KEY_MAX || !PUBLIC_KEY.matcher(k).matches()) {
            throw new SshProvisioningException(SshProvisioningException.Kind.VALIDATION,
                    "Clé publique invalide (une seule ligne « ssh-ed25519 AAAA… [commentaire] »)");
        }
        return k;
    }
}
