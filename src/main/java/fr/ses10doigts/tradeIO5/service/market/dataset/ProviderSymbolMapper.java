package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Paire d'un actif chez un exchange, pour la création à la volée d'une ligne {@code asset_provider}. Convention
 * des lignes semées par {@code AssetInitializer} : Binance {@code <ACTIF>USDT}, Kraken {@code <ACTIF>USD}
 * ({@code BTC} -> {@code XBT}), OKX {@code <ACTIF>-USDT}. Une source hors de ces trois exchanges n'est pas de
 * confiance : {@code null}.
 */
public final class ProviderSymbolMapper {

    private ProviderSymbolMapper() {
    }

    public static String forSource(String assetSymbol, MarketDataSource source) {
        return switch (source) {
            case BINANCE -> assetSymbol + "USDT";
            case KRAKEN -> ("BTC".equals(assetSymbol) ? "XBT" : assetSymbol) + "USD";
            case OKX -> assetSymbol + "-USDT";
            default -> null;
        };
    }
}
