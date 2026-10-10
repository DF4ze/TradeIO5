package fr.ses10doigts.tradeIO5.service.execution.credential;

/** Clé maître de chiffrement absente (variable d'environnement {@code TRADEIO_MASTER_KEY}) : aucun secret TRADE ne peut être chiffré ni lu. */
public class MasterKeyMissingException extends IllegalStateException {

    public MasterKeyMissingException() {
        super("Clé maître de chiffrement absente (variable d'environnement TRADEIO_MASTER_KEY) : credentials TRADE inutilisables");
    }
}
