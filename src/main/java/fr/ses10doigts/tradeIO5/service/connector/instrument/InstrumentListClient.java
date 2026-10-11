package fr.ses10doigts.tradeIO5.service.connector.instrument;

import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Liste de <b>toutes</b> les paires spot d'un exchange en un seul appel public (aucune clé, aucun ordre).
 */
public interface InstrumentListClient {

    /**
     * Paire normalisée : symboles standard, {@code instId = BASE-QUOTE}, {@code live} = négociable. Facultatifs (nuls si
     * l'exchange ne les porte pas, ex. OKX) : {@code costMin} = coût minimum en cotation, {@code nativePair} = nom de la paire
     * dans l'API de trading (Kraken : {@code XBTUSDC}), {@code feeCurrency} = devise des remises de frais au volume.
     */
    record Listed(String instId, String base, String quote, boolean live, BigDecimal minSz, BigDecimal lotSz,
                  BigDecimal tickSz, BigDecimal costMin, String nativePair, String feeCurrency) {

        public Listed(String instId, String base, String quote, boolean live, BigDecimal minSz, BigDecimal lotSz,
                      BigDecimal tickSz) {
            this(instId, base, quote, live, minSz, lotSz, tickSz, null, null, null);
        }
    }

    WebProviderCode getProviderCode();

    /**
     * @throws InstrumentLookupException exchange injoignable, erreur API ou réponse illisible/vide
     */
    List<Listed> fetchAll(WebProvider provider);
}
