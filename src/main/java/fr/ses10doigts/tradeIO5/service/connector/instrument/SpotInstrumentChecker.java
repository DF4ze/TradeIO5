package fr.ses10doigts.tradeIO5.service.connector.instrument;

import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

/**
 * Existence d'une paire spot sur un exchange, via son endpoint <b>public</b> (aucune clé, aucun ordre).
 */
public interface SpotInstrumentChecker {

    WebProviderCode getProviderCode();

    /**
     * @return {@code true} si la paire {@code base/quote} existe et est négociable ; {@code false} si elle est absente
     * ou suspendue
     * @throws InstrumentLookupException si l'exchange est injoignable ou répond de façon illisible
     */
    boolean isTradable(WebProvider provider, String base, String quote);
}
