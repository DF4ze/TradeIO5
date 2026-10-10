package fr.ses10doigts.tradeIO5.service.connector.instrument;

import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Liste de <b>toutes</b> les paires spot d'un exchange en un seul appel public (aucune clé, aucun ordre).
 */
public interface InstrumentListClient {

    /** Paire normalisée : symboles standard, {@code instId = BASE-QUOTE}, {@code live} = négociable. */
    record Listed(String instId, String base, String quote, boolean live, BigDecimal minSz, BigDecimal lotSz,
                  BigDecimal tickSz) {
    }

    WebProviderCode getProviderCode();

    /**
     * @throws InstrumentLookupException exchange injoignable, erreur API ou réponse illisible/vide
     */
    List<Listed> fetchAll(WebProvider provider);
}
