package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

/**
 * Paire d'un actif chez un exchange, pour la création à la volée d'une ligne {@code asset_provider}. Convention
 * des lignes semées par {@code AssetInitializer} : Binance {@code <ACTIF>USDT}, Kraken {@code <ACTIF>USD}
 * ({@code BTC} -> {@code XBT}), OKX {@code <ACTIF>-USDT}, Revolut X {@code <ACTIF>-USDC} (cotation USDC). Une source hors de ces exchanges n'est pas de
 * confiance : {@code null}.
 */
public final class ProviderSymbolMapper {

    private ProviderSymbolMapper() {
    }

    /**
     * Paire Binance d'un symbole : {@code BTC} -> {@code BTCUSDT} ; une paire déjà suffixée ({@code BTCUSDT}) est conservée.
     * Pour les composants qui interrogent Binance/Coinalyze directement (carnet d'ordres, futures, périmètre ETF) alors que
     * {@code context.symbol()} porte le symbole nu de l'actif (cf. {@code asset_provider}).
     */
    public static String binancePair(String symbol) {
        if (symbol == null) {
            return null;
        }
        return symbol.endsWith("USDT") ? symbol : symbol + "USDT";
    }

    /** Paire de convention (cotation par défaut de l'exchange) : sert aux lignes semées et aux exchanges sans catalogue. */
    public static String forSource(String assetSymbol, MarketDataSource source) {
        String quote = defaultQuote(source);
        return quote == null ? null : forSource(assetSymbol, source, quote);
    }

    /** Cotation de la convention : Binance USDT, Kraken USD, OKX USDT, Revolut X USDC ; {@code null} hors de ces exchanges. */
    public static String defaultQuote(MarketDataSource source) {
        return switch (source) {
            case BINANCE, OKX -> "USDT";
            case KRAKEN -> "USD";
            case REVOLUT_X -> "USDC";
            default -> null;
        };
    }

    /** Paire de l'actif cotée {@code quote} chez l'exchange, au format de son API de bougies ({@code null} hors des exchanges de confiance). */
    public static String forSource(String assetSymbol, MarketDataSource source, String quote) {
        return switch (source) {
            case BINANCE -> assetSymbol + quote;
            case KRAKEN -> ("BTC".equals(assetSymbol) ? "XBT" : assetSymbol) + quote;
            case OKX, REVOLUT_X -> assetSymbol + "-" + quote;
            default -> null;
        };
    }
}
