package fr.ses10doigts.TradeIO5.service.support.dataset.dto;

import fr.ses10doigts.tradeIO5.model.dto.MarketDataSeries;

public record MarketDataset(
        String name,
        MarketDataSeries series
) {}
