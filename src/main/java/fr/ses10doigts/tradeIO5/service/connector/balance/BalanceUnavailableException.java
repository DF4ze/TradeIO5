package fr.ses10doigts.tradeIO5.service.connector.balance;

/**
 * Lecture des soldes impossible (réseau, erreur API, réponse illisible, credential incomplète).
 * Signale une panne : ne jamais la convertir en « aucun solde » (une map vide valide = compte sans solde).
 */
public class BalanceUnavailableException extends RuntimeException {

    public BalanceUnavailableException(String message) {
        super(message);
    }

    public BalanceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
