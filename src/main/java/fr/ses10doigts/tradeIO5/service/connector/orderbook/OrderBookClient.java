package fr.ses10doigts.tradeIO5.service.connector.orderbook;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;

/** Carnet d'ordres <b>public</b> d'un instrument (aucune clé, aucun ordre). Un appel par devis, pas de cache long. */
public interface OrderBookClient {

    WebProviderCode getProviderCode();

    /**
     * @param depth nombre de niveaux demandés par côté
     * @return le carnet, ou à défaut le meilleur bid/ask du ticker (estimation {@code TICKER})
     * @throws InstrumentLookupException carnet ET ticker indisponibles
     */
    BookResult fetchBook(WebProvider provider, String instId, int depth);
}
