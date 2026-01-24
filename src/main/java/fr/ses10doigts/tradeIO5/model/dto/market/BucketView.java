package fr.ses10doigts.tradeIO5.model.dto.market;

import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

import java.util.List;

public record BucketView(
        List<MarketData> data,
        CompletenessLevel completeness,
        TimeFrame timeFrame
) {
    public int size() {
        return data.size();
    }
}