package fr.ses10doigts.tradeIO5.service.agregation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.service.WalletService;
import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.dto.AssetOverview;
import fr.ses10doigts.tradeIO5.service.TransactionService;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AssetOverviewService {

	private final ProviderApiService apiService;
	private final TransactionService transactionService;
	private final WalletService walletService;

	public List<AssetOverview> getUserHoldings(String quoteCurrency, Optional<String> exchangeCodeFilter) {

		// Récupère tous les wallets de l'utilisateur courant
		List<Wallet> wallets = walletService.getWalletsForCurrentUser();

		// Applique le filtre sur providerCode si présent
		if (exchangeCodeFilter.isPresent()) {
			wallets = wallets.stream()
					.filter(w -> w.getProviderCode().equals(exchangeCodeFilter.get()))
					.toList();
		}

		List<AssetOverview> overviews = new ArrayList<>();

		BigDecimal superTotaltotalValue = BigDecimal.ZERO;
		BigDecimal superTotalInvest = BigDecimal.ZERO;
		BigDecimal superTotalSold = BigDecimal.ZERO;
		for (Wallet wallet : wallets) {
			Map<String, BigDecimal> balances = apiService.getAllBalances(wallet); // BTC → 0.2

			for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
				String asset = entry.getKey();
				BigDecimal quantity = entry.getValue();

//				if (asset.startsWith("EUR"))
//					continue;

				if (quantity.compareTo(BigDecimal.ZERO) == 0)
					continue;

				BigDecimal marketPrice = apiService.getMarketPrice(wallet, asset, quoteCurrency);
				if (asset.endsWith("USDC"))
					marketPrice = BigDecimal.ONE;
				BigDecimal currentValue = marketPrice.multiply(quantity);
				superTotaltotalValue = superTotaltotalValue.add(currentValue);

				// Moyennes pondérées achat / vente + prix de revient
				BigDecimal averageBuyPrice = transactionService.getWeightedAverageBuyPrice(asset, wallet);
				BigDecimal averageSellPrice = transactionService.getWeightedAverageSellPrice(asset, wallet);

				BigDecimal totalSold = transactionService.getTotalSellValue(asset, wallet); // qte * prix
				superTotalSold = superTotalSold.add(totalSold);
				BigDecimal totalInvested = transactionService.getTotalBuyValue(asset, wallet); // qte * prix
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

		List<AssetOverview> aggOverviews = aggregateAssets(overviews);

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
		aggOverviews.add(overview);
		return aggOverviews;
	}

	public List<AssetOverview> aggregateAssets(List<AssetOverview> assets) {
		Map<String, String> assetAliases = Map.of(
				"XBT", "BTC",
				"LDUSDC", "USDC",
				"LDBNB", "BNB",
				"LDXRP", "XRP",
				"LDETH", "ETH"
		);

		Map<String, AssetOverview> groupedMap = assets.stream()
				.collect(Collectors.groupingBy(asset -> {
					String alias = assetAliases.getOrDefault(asset.getAsset(), asset.getAsset());
					if( alias.startsWith("LD") && alias.length() > 3 )
						alias = alias.replace("LD", "");
					return alias;
				}, Collectors.collectingAndThen(Collectors.toList(), groupedList -> {
					AssetOverview result = new AssetOverview();
					String asset = groupedList.get(0).getAsset();
					result.setAsset(assetAliases.getOrDefault(asset, asset));
					result.setQuoteCurrency(groupedList.get(0).getQuoteCurrency());

					BigDecimal totalQuantity = BigDecimal.ZERO;
					BigDecimal totalValue = BigDecimal.ZERO;
					BigDecimal totalBuyAmount = BigDecimal.ZERO;
					BigDecimal totalBuyQty = BigDecimal.ZERO;
					BigDecimal totalSellAmount = BigDecimal.ZERO;
					BigDecimal totalSellQty = BigDecimal.ZERO;
					BigDecimal marketPrice = BigDecimal.ZERO;

					for (AssetOverview a : groupedList) {
						totalQuantity = totalQuantity.add(a.getQuantity());
						totalValue = totalValue.add(a.getValue());
						marketPrice = a.getMarketPrice();

						/*if (a.getAverageBuyPrice() != null && a.getQuantity() != null) {
							totalBuyAmount = totalBuyAmount.add(a.getAverageBuyPrice().multiply(a.getQuantity()));
							totalBuyQty = totalBuyQty.add(a.getQuantity());
						}

						if (a.getAverageSellPrice() != null && a.getQuantity() != null) {
							totalSellAmount = totalSellAmount.add(a.getAverageSellPrice().multiply(a.getQuantity()));
							totalSellQty = totalSellQty.add(a.getQuantity());
						}

						if (a.getTotalInvested() != null) {
							result.setTotalInvested(result.getTotalInvested() == null ? a.getTotalInvested() : result.getTotalInvested().add(a.getTotalInvested()));
						}

						if (a.getTotalSold() != null) {
							result.setTotalSold(result.getTotalSold() == null ? a.getTotalSold() : result.getTotalSold().add(a.getTotalSold()));
						}

						if (a.getCostBasis() != null) {
							result.setCostBasis(result.getCostBasis() == null ? a.getCostBasis() : result.getCostBasis().add(a.getCostBasis()));
						}*/
					}

					result.setQuantity(totalQuantity);
					result.setValue(totalValue);
					result.setMarketPrice(marketPrice);

					result.setAverageBuyPrice(totalBuyQty.signum() > 0 ? totalBuyAmount.divide(totalBuyQty, 8, RoundingMode.HALF_UP) : null);
					result.setAverageSellPrice(totalSellQty.signum() > 0 ? totalSellAmount.divide(totalSellQty, 8, RoundingMode.HALF_UP) : null);

					return result;
				})));

		return new ArrayList<>(groupedMap.values());
	}

}