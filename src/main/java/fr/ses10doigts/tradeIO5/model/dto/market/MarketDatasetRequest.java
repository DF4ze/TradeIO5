package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

import java.time.Instant;

/**
 * @param symbol        Actif ou marché concerné (BTC/USDT, AAPL, etc.)
 * @param timeFrame     Timeframe des bougies
 * @param lookback      Nombre de points attendus (ex: 100 bougies)
 * @param endTime       Fin de la série (null = now)
 * @param source        Origine du market
 * @param providerParam Paramètre du MarketDataProvider
 */

public record MarketDatasetRequest(
        String symbol,
        TimeFrame timeFrame,
        int lookback,
        Instant endTime,
        MarketDataSource source,
        Object providerParam
) { }
