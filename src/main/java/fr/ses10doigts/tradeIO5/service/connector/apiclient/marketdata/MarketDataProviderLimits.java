package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

/**
 * Plafonds d'API par exchange pour les bougies, en un seul endroit (ne pas les recopier dans les
 * clients). Un client dont le plafond est inférieur à la plage demandée doit paginer
 * (cf. {@link CandlePaginator}).
 */
public final class MarketDataProviderLimits {

    /** Binance {@code GET /api/v3/klines} : 1000 bougies max par appel (poids 2). */
    public static final int BINANCE_KLINES_PAGE_SIZE = 1000;

    /** Kraken {@code /public/OHLC} : seules les 720 dernières bougies sont servies, non paginable. */
    public static final int KRAKEN_OHLC_MAX = 720;

    /** Horizon (jours) des bougies H1 d'une ligne {@code asset_provider} Kraken : 720 H1 = 30 j, 25 j de marge. */
    public static final Integer KRAKEN_H1_MAX_HORIZON_DAYS = 25;

    /** OKX {@code /market/candles} (fenêtre récente) : ~300 bougies max. */
    public static final int OKX_RECENT_CANDLES_MAX = 300;

    /** OKX {@code /market/history-candles} : 100 bougies max par page. */
    public static final int OKX_HISTORY_PAGE_SIZE = 100;

    /** Garde-fou : nombre max d'appels réseau d'une même pagination (200 x 1000 H1 > capacité du Bucket). */
    public static final int MAX_PAGES_PER_FETCH = 200;

    /** Espacement entre deux pages d'une même pagination, pour ménager le rate limit. */
    public static final long PAGE_PAUSE_MILLIS = 150;

    private MarketDataProviderLimits() {
    }
}
