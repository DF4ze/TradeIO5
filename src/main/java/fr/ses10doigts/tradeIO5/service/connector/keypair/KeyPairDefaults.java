package fr.ses10doigts.tradeIO5.service.connector.keypair;

import java.time.Duration;

/** Constantes du moteur de clés par utilisateur (paires générées par le serveur). */
public final class KeyPairDefaults {

    private KeyPairDefaults() {
    }

    /** Durée de vie d'une paire non promue : au-delà, elle est purgée et l'utilisateur doit en générer une autre. */
    public static final Duration PENDING_TTL = Duration.ofHours(24);

    /** Purge des paires expirées : une passe par heure, première passe 2 minutes après le démarrage. */
    public static final String PURGE_INTERVAL_MS = "${tradeio.keypair.purge-interval-ms:3600000}";
    public static final String PURGE_INITIAL_DELAY_MS = "${tradeio.keypair.purge-initial-delay-ms:120000}";

    /** Algorithme par défaut d'un {@link ProviderKeyPairSupport}. */
    public static final String DEFAULT_ALGORITHM = "Ed25519";

    /** Valeur de {@link ProviderKeyPairSupport#keySize()} pour « taille par défaut de l'algorithme ». */
    public static final int DEFAULT_KEY_SIZE = 0;
}
