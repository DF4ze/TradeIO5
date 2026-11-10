package fr.ses10doigts.tradeIO5.service.connector.keypair;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;

import java.util.Set;

/**
 * Point d'extension du moteur de clés ({@link ProviderKeyPairService}) : un bean par provider dont l'authentification repose sur une paire
 * de clés générée par l'utilisateur (clé publique enregistrée chez l'exchange, signature des requêtes avec la clé privée). Le moteur
 * génère, chiffre, garde en attente, vérifie puis promeut ; le provider ne fournit que ce qui lui est propre.
 * <p>
 * Pour brancher un nouveau provider : un {@code @Component} qui implémente cette interface (au minimum {@link #provider()},
 * {@link #scopes()}, {@link #registrationHint()} et {@link #verify(ApiCredential)}), sans autre modification du moteur. La clé privée est
 * stockée dans le champ {@code secretKey} de l'{@link ApiCredential} sauf si {@link #fields} en décide autrement.
 */
public interface ProviderKeyPairSupport {

    /** Provider géré. */
    WebProviderCode provider();

    /** Portées pour lesquelles une paire peut être générée (ex. lecture seule et trading). */
    Set<CredentialScope> scopes();

    /** Algorithme JCA de la paire ({@code KeyPairGenerator}). */
    default String algorithm() {
        return KeyPairDefaults.DEFAULT_ALGORITHM;
    }

    /** Taille de clé pour les algorithmes qui en ont une (RSA) ; {@link KeyPairDefaults#DEFAULT_KEY_SIZE} = défaut de l'algorithme. */
    default int keySize() {
        return KeyPairDefaults.DEFAULT_KEY_SIZE;
    }

    /** Consigne affichée à l'utilisateur : où enregistrer la clé publique, quels droits donner, quoi saisir ensuite. */
    String registrationHint();

    /**
     * Champs de la credential à stocker, à partir de la clé API saisie et de la clé privée (PEM PKCS#8). Par défaut : clé API, clé privée en
     * {@code secretKey}, pas de passphrase.
     */
    default CredentialFields fields(String apiKey, String privateKeyPem) {
        return new CredentialFields(apiKey, privateKeyPem, null);
    }

    /**
     * Appel réel signé avec la credential candidate (non enregistrée, portée renseignée) : la promotion n'a lieu que s'il réussit.
     *
     * @throws CredentialRejectedException clé refusée ou clé privée illisible
     * @throws BalanceUnavailableException exchange injoignable, provider non déclaré ou désactivé (issue inconnue)
     */
    void verify(ApiCredential candidate);

    /** Champs d'une credential. {@code toString} masqué. */
    record CredentialFields(String apiKey, String secretKey, String passphrase) {

        @Override
        public String toString() {
            return "CredentialFields[***]";
        }
    }
}
