package fr.ses10doigts.tradeIO5.model.enumerate.market;

import lombok.Getter;

@Getter
public enum MarketDataSourceType {
    CEX(true),
    DEX(true),
    ORACLE(true),
    HISTORICAL(false),
    IN_MEMORY(false);

    private boolean isLive;

    MarketDataSourceType(boolean isLive){
        this.isLive = isLive;
    }
}