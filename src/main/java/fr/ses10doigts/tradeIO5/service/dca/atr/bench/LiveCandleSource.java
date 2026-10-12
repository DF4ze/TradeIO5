package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/** Exchange des candles du signal d'un lien = exchange du wallet (le signal est lu là où l'ordre sera passé). */
public final class LiveCandleSource {

    private LiveCandleSource() {
    }

    /** {@code null} si le wallet n'est pas rattaché à un exchange de marché de confiance (testnet inclus : prix non réels). */
    public static MarketDataSource of(Wallet wallet) {
        WebProviderCode code = wallet == null ? null : wallet.getWebProviderCode();
        if (code == null) {
            return null;
        }
        return switch (code) {
            case BINANCE -> MarketDataSource.BINANCE;
            case KRAKEN -> MarketDataSource.KRAKEN;
            case OKX -> MarketDataSource.OKX;
            default -> null;
        };
    }
}
