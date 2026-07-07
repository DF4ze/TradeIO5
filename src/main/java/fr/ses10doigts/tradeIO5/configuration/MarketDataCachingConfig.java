package fr.ses10doigts.tradeIO5.configuration;

import fr.ses10doigts.tradeIO5.repository.market.CandleRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.BinanceMarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.CachingMarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.KrakenMarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.OkxMarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enveloppe chaque {@link MarketDataApiClient} exchange (Binance/Kraken/OKX) dans un
 * {@link CachingMarketDataApiClient} — cf. docs/etude-cache-db-candles-h1.md section 3.
 * <p>
 * Beans nommés explicitement ("cachingXxxMarketDataApiClient") plutôt que {@code @Primary} :
 * les 3 clients bruts (déjà {@code @Component}) et les 3 versions cachées implémentent toutes
 * {@link MarketDataApiClient}, {@code @Primary} seul ne suffirait pas à désambiguïser laquelle
 * des 3 versions cachées correspond à quel exchange.
 */
@Configuration
public class MarketDataCachingConfig {

    @Bean
    public MarketDataApiClient cachingBinanceMarketDataApiClient(
            BinanceMarketDataApiClient delegate, CandleRepository repository, DomainClock clock) {
        return new CachingMarketDataApiClient(delegate, repository, clock);
    }

    @Bean
    public MarketDataApiClient cachingKrakenMarketDataApiClient(
            KrakenMarketDataApiClient delegate, CandleRepository repository, DomainClock clock) {
        return new CachingMarketDataApiClient(delegate, repository, clock);
    }

    @Bean
    public MarketDataApiClient cachingOkxMarketDataApiClient(
            OkxMarketDataApiClient delegate, CandleRepository repository, DomainClock clock) {
        return new CachingMarketDataApiClient(delegate, repository, clock);
    }
}
