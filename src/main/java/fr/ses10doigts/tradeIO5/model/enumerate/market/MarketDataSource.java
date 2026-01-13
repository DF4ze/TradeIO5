package fr.ses10doigts.tradeIO5.model.enumerate.market;

import lombok.Getter;

import static fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSourceType.*;

@Getter
public enum MarketDataSource {

    BINANCE(CEX),
    KRAKEN(CEX),
    COINBASE(CEX),
    OKX(CEX),

    UNISWAP(DEX),
    SUSHISWAP(DEX),

    CHAINLINK(ORACLE),

    DATABASE(HISTORICAL),
    FILE(HISTORICAL),

    MEMORY(IN_MEMORY);

    private MarketDataSourceType type;

    MarketDataSource( MarketDataSourceType type ){
        this.type = type;
    }
}
