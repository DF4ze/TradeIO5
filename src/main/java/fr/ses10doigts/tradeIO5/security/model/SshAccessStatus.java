package fr.ses10doigts.tradeIO5.security.model;

/** État de l'accès VPS (tunnel SSH) d'un utilisateur. Le login VPS = {@code username} (compte Linux {@code tio_<username>}). */
public enum SshAccessStatus {
    /** Pas d'accès VPS demandé. */
    NONE,
    /** Compte applicatif créé, clé publique pas encore reçue. */
    PENDING_KEY,
    ACTIVE,
    DISABLED,
    /** Dernière opération VPS en échec (cf. message) : « réessayer » = synchroniser. */
    ERROR
}
