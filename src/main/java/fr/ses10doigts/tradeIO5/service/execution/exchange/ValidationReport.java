package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;

/**
 * Verdict d'une validation d'ordre sans envoi. {@code exchangeCode} : erreur de l'exchange ({@code EOrder:...}) si refus ;
 * {@code rawFiltered} : réponse brute réduite à une liste blanche de champs (jamais de clé, de signature ni de nonce),
 * tronquée à {@link ExecutionDefaults#RAW_RESPONSE_MAX_CHARS}.
 */
public record ValidationReport(boolean accepted, String exchangeCode, String message, String rawFiltered) {

    public ValidationReport {
        if (rawFiltered != null && rawFiltered.length() > ExecutionDefaults.RAW_RESPONSE_MAX_CHARS) {
            rawFiltered = rawFiltered.substring(0, ExecutionDefaults.RAW_RESPONSE_MAX_CHARS);
        }
    }

    public static ValidationReport accepted(String message, String rawFiltered) {
        return new ValidationReport(true, null, message, rawFiltered);
    }

    public static ValidationReport refused(String exchangeCode, String message, String rawFiltered) {
        return new ValidationReport(false, exchangeCode, message, rawFiltered);
    }
}
