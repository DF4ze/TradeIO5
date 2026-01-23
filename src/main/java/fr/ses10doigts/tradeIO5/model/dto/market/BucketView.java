package fr.ses10doigts.tradeIO5.model.dto.market;

import java.util.List;

public record BucketView(List<MarketData> data, boolean complete) {}