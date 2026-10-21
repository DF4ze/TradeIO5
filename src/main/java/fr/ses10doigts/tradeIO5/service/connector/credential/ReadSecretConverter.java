package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

/**
 * Chiffrement au repos transparent des secrets de portée {@code READ} ({@code apiKey}, {@code secretKey}, {@code passphrase}) :
 * en base {@code encr:v1:...} (AES-256-GCM, même clé maître que TRADE, contexte authentifié distinct), côté entité la valeur
 * en clair — les lecteurs existants n'ont rien à changer.
 * <ul>
 *   <li>Écriture : vide, déjà chiffré (READ {@code encr:} ou TRADE {@code enc:}) => inchangé ; sinon chiffré si la clé maître
 *   est chargée, en clair sinon (environnements sans clé : tests, dev).</li>
 *   <li>Lecture : {@code encr:} => déchiffré ; tout le reste (TRADE {@code enc:}, ancien clair) => inchangé. Illisible
 *   (clé différente, altéré) => valeur brute renvoyée (l'authentification exchange échouera) et alerte, jamais d'exception.</li>
 * </ul>
 * Hibernate instancie ce convertisseur lui-même : le chiffreur est donc posé en statique par {@link ReadSecretCipherBinder}.
 */
@Slf4j
@Converter
public class ReadSecretConverter implements AttributeConverter<String, String> {

    public static final String PREFIX = "encr:v1:";
    private static final String CONTEXT = "read";

    private static volatile SecretCipher cipher;

    static void bind(SecretCipher secretCipher) {
        cipher = secretCipher;
    }

    /** Vrai si la valeur est déjà au format de stockage READ. */
    public static boolean isSealed(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Valeur telle qu'elle sera écrite en base ; identique à l'entrée si rien à chiffrer. */
    public static String seal(String plain) {
        SecretCipher c = cipher;
        if (plain == null || plain.isBlank() || isSealed(plain) || SecretCipher.isEncrypted(plain) || c == null || !c.isConfigured()) {
            return plain;
        }
        return PREFIX + c.encrypt(plain, CONTEXT).substring(SecretCipher.PREFIX.length());
    }

    /** Vrai si le chiffrement est actif (clé maître chargée). */
    public static boolean active() {
        SecretCipher c = cipher;
        return c != null && c.isConfigured();
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return seal(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        SecretCipher c = cipher;
        if (!isSealed(dbData) || c == null || !c.isConfigured()) {
            return dbData;
        }
        try {
            return c.decrypt(SecretCipher.PREFIX + dbData.substring(PREFIX.length()), CONTEXT);
        } catch (RuntimeException e) {
            log.warn("Secret READ illisible (clé maître différente ou valeur altérée) : {}", e.getMessage());
            return dbData;
        }
    }
}
