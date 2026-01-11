package fr.ses10doigts.tradeIO5.service.marketdataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataSeries;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;


/**
 * Traducteur entre la request et la récupération des datas
 */
public interface MarketDatasetProvider {

    boolean supports(MarketDataSource source);

    MarketDataSeries load(MarketDataRequest request);

}
