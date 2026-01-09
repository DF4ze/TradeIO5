package fr.ses10doigts.tradeIO5.model.dto;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.TimeFrame;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MarketDataSeries {

    private final String pair;
    private final TimeFrame timeFrame;
    private final List<MarketData> marketDatas;
    private final int size;
}
