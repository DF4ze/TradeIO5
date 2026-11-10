package fr.ses10doigts.tradeIO5.service.connector.keypair;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** Génération d'une paire de clés et encodage PEM : privée PKCS#8 ({@code PRIVATE KEY}), publique X.509 SPKI ({@code PUBLIC KEY}). */
final class PemKeys {

    private static final int PEM_LINE_LENGTH = 64;
    private static final byte[] LINE_SEPARATOR = {'\n'};

    private PemKeys() {
    }

    /** @throws GeneralSecurityException algorithme inconnu du JDK ou taille de clé refusée */
    static KeyPair generate(String algorithm, int keySize) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if (keySize != KeyPairDefaults.DEFAULT_KEY_SIZE) {
            generator.initialize(keySize);
        }
        return generator.generateKeyPair();
    }

    static String privatePem(KeyPair pair) {
        return pem("PRIVATE KEY", pair.getPrivate().getEncoded());
    }

    static String publicPem(KeyPair pair) {
        return pem("PUBLIC KEY", pair.getPublic().getEncoded());
    }

    /** Empreinte SHA-256 de la clé publique (DER), en hexadécimal minuscule. */
    static String fingerprint(KeyPair pair) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded()));
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(PEM_LINE_LENGTH, LINE_SEPARATOR).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }
}
