package fr.ses10doigts.tradeIO5.service.agregation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.dto.AssetOverview;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.service.PositionService;
import fr.ses10doigts.tradeIO5.service.connector.ApiCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.ExchangeApiService;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ExchangeApiClient;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssetOverviewService {

    private final ApiCredentialService credentialService;
	private final ExchangeApiService apiClientService;
	private final PositionService positionService;

	public List<AssetOverview> getUserHoldings(String quoteCurrency, Optional<String> exchangeCodeFilter) {

		List<ApiCredential> credentials = credentialService.getAllCredentialsForCurrentUser();

		if (exchangeCodeFilter.isPresent()) {
			credentials = credentials.stream().filter(c -> c.getExchange().getCode().equals(exchangeCodeFilter.get()))
					.toList();
		}

		List<AssetOverview> overviews = new ArrayList<>();

		BigDecimal superTotaltotalValue = BigDecimal.ZERO;
		BigDecimal superTotalInvest = BigDecimal.ZERO;
		BigDecimal superTotalSold = BigDecimal.ZERO;
		for (ApiCredential credential : credentials) {
			ExchangeApiClient client = apiClientService.getClient(credential.getExchange().getCode());

			Map<String, BigDecimal> balances = client.getAllBalances(credential); // BTC → 0.2

			for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
				String asset = entry.getKey();
				BigDecimal quantity = entry.getValue();

//				if (asset.startsWith("EUR"))
//					continue;

				if (quantity.compareTo(BigDecimal.ZERO) == 0)
					continue;

				BigDecimal marketPrice = client.getMarketPrice(asset, quoteCurrency, credential);
				if (asset.endsWith("USDC"))
					marketPrice = BigDecimal.ONE;
				BigDecimal currentValue = marketPrice.multiply(quantity);
				superTotaltotalValue = superTotaltotalValue.add(currentValue);

				// Moyennes pondérées achat / vente + prix de revient
				BigDecimal averageBuyPrice = positionService.getWeightedAverageBuyPrice(asset, credential);
				BigDecimal averageSellPrice = positionService.getWeightedAverageSellPrice(asset, credential);

				BigDecimal totalSold = positionService.getTotalSellValue(asset, credential); // qte * prix
				superTotalSold = superTotalSold.add(totalSold);
				BigDecimal totalInvested = positionService.getTotalBuyValue(asset, credential); // qte * prix
				if (asset.startsWith("EUR")) {
					totalInvested = totalSold;
					totalSold = BigDecimal.ZERO;
					superTotalSold = superTotalSold.subtract(totalInvested);
				}
				superTotalInvest = superTotalInvest.add(totalInvested);
				BigDecimal costBasis = currentValue.add(totalSold).subtract(totalInvested); // somme investie - somme
																							// récupérée

				//@formatter:off
				AssetOverview overview = AssetOverview.builder()
						.asset(asset)
						.value(currentValue)
						.quantity(quantity)
						.marketPrice(marketPrice)
						.averageBuyPrice(averageBuyPrice)
						.averageSellPrice(averageSellPrice)
						.totalInvested(totalInvested)
						.totalSold(totalSold)
						.costBasis(costBasis)
						.quoteCurrency(quoteCurrency)
						.build();
				//formatter:on
				
				if( currentValue.compareTo(BigDecimal.ONE) > 0  )
					overviews.add(overview);
			}
		}
		//@formatter:off
		AssetOverview overview = AssetOverview.builder()
				.asset("Total")
				.value(superTotaltotalValue)
				.quantity(BigDecimal.ZERO)
				.marketPrice(BigDecimal.ZERO)
				.averageBuyPrice(BigDecimal.ZERO)
				.averageSellPrice(BigDecimal.ZERO)
				.totalInvested(superTotalInvest)
				.totalSold(superTotalSold)
				.costBasis(superTotaltotalValue.add(superTotalSold).subtract(superTotalInvest))
				.quoteCurrency(quoteCurrency)
				.build();
		//formatter:on
		overviews.add(overview);
		return overviews;
	}

}