package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class MarketDataSeries {

    private final String pair;
    private final TimeFrame timeFrame;
    private final List<MarketData> marketDatas;
    private final int size;
    private final MarketDataRequest request;
    private final Instant lastUpdate;
}
