package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

@Data
@AllArgsConstructor
public final class MarketDataRequest {

    /** Actif ou marché concerné (BTC/USDT, AAPL, etc.) */
    private final String symbol;

    /** Timeframe des bougies */
    private final TimeFrame timeFrame;

    /** Nombre de points attendus (ex: 100 bougies) */
    private final int lookback;

    /** Fin de la série (null = now) */
    private final Instant endTime;

    /** Origine du market */
    private final MarketDataSource source;

}
