package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.exception.SymbolNotFoundException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.market.dataset.NoProviderAvailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Historique D1 d'un actif chez un provider via {@link MarketDatasetEngine#getDailyDatasetOnSource} (pipeline à cache DB : la lecture ne
 * rappelle l'exchange que pour les bougies absentes ; ni ligne {@code asset_provider} ni écriture de configuration). Borne haute = la veille 00:00
 * UTC (dernière bougie close) : une fois les {@value AssetEligibilityDefaults#MIN_D1_DAYS} bougies en base, les vérifications suivantes ne font plus
 * aucun appel réseau. Provider sans client de bougies (Bitvavo, Gate EU, One Trading) : 0 jour, donc jamais éligible.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CachedD1HistoryProbe implements D1HistoryProbe {

    /** Exchanges disposant d'un client de bougies publiques ({@code MarketDatasetEngine}). */
    private static final Map<WebProviderCode, MarketDataSource> SOURCES = new EnumMap<>(WebProviderCode.class);

    static {
        SOURCES.put(WebProviderCode.BINANCE, MarketDataSource.BINANCE);
        SOURCES.put(WebProviderCode.KRAKEN, MarketDataSource.KRAKEN);
        SOURCES.put(WebProviderCode.OKX, MarketDataSource.OKX);
        SOURCES.put(WebProviderCode.REVOLUT_X, MarketDataSource.REVOLUT_X);
    }

    private final MarketDatasetEngine engine;
    private final DomainClock clock;

    @Override
    public OptionalInt closedDays(WebProviderCode provider, String assetSymbol) {
        MarketDataSource source = SOURCES.get(provider);
        if (source == null) {
            return OptionalInt.of(0);
        }
        Instant lastClosed = clock.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS);
        try {
            DailyDataset daily = engine.getDailyDatasetOnSource(assetSymbol, source, AssetEligibilityDefaults.MIN_D1_DAYS, lastClosed);
            long days = daily.dataset().getMarketDatas().stream().map(MarketData::getTimestamp)
                    .filter(t -> !t.isAfter(lastClosed)).count();
            return OptionalInt.of((int) Math.min(days, AssetEligibilityDefaults.MIN_D1_DAYS));
        } catch (NoProviderAvailableException e) {
            if (e.getLastError() == null || e.getLastError() instanceof SymbolNotFoundException) {
                log.debug("D1 {} chez {} : paire absente", assetSymbol, provider);
                return OptionalInt.of(0);
            }
            log.warn("D1 {} chez {} : lecture impossible ({})", assetSymbol, provider, e.getMessage());
            return OptionalInt.empty();
        }
    }
}
