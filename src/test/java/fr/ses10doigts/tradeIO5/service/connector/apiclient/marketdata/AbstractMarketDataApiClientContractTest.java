package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.MarketDataProviderException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.ProviderUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Contrat commun à tout {@link MarketDataApiClient} (cf. Javadoc de
 * {@link MarketDataApiClient#getCandles}) : symbole invalide -> {@link SymbolNotFoundException},
 * erreur transitoire -> {@link ProviderUnavailableException}. La CI casse si un nouveau provider
 * ne mappe pas correctement ses erreurs.
 * <p>
 * Volontairement pas de point d'entrée générique par réflexion : Binance mappe depuis une
 * exception SDK ({@code mapError(...)}), Kraken/OKX mappent depuis un payload JSON
 * ({@code mapXxxResponse(...)}), et OKX a en plus la logique de routing history-candles. Chaque
 * {@code XyzMarketDataApiClientTest} étend cette classe et implémente les deux méthodes hook avec
 * son propre mécanisme, en réutilisant les payloads/fixtures déjà présents dans chacune.
 */
abstract class AbstractMarketDataApiClientContractTest {

    protected abstract MarketDataSource expectedSource();

    /** Doit retourner l'exception levée pour un cas "symbole invalide" représentatif du provider. */
    protected abstract MarketDataProviderException triggerSymbolNotFound();

    /** Doit retourner l'exception levée pour un cas "erreur transitoire" représentatif du provider. */
    protected abstract MarketDataProviderException triggerProviderUnavailable();

    @Test
    void symbolNotFound_isTypedCorrectly() {
        MarketDataProviderException ex = triggerSymbolNotFound();
        assertInstanceOf(SymbolNotFoundException.class, ex);
        assertEquals(expectedSource(), ex.getSource());
    }

    @Test
    void providerUnavailable_isTypedCorrectly() {
        MarketDataProviderException ex = triggerProviderUnavailable();
        assertInstanceOf(ProviderUnavailableException.class, ex);
        assertEquals(expectedSource(), ex.getSource());
    }
}
