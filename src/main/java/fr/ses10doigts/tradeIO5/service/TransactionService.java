package fr.ses10doigts.tradeIO5.service;

import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.ApiCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class TransactionService {

	private final Logger logger = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repository;
	private final ProviderApiService apiClientService;
	private final ApiCredentialService apiCredentialService;


	public List<Transaction> getByUser(User user) {
		return repository.findByUser(user);
	}

    public Optional<Transaction> getById(Long id) {
        return repository.findById(id);
    }

    public Transaction save(Transaction asset) {
        return repository.save(asset);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }


	public BigDecimal getWeightedAverageBuyPrice(String asset, Wallet wallet) {
		List<Transaction> transactions = repository.findByAssetAndUserAndProviderAndSide(asset, wallet.getUser(),
				wallet.getProvider(), TradeSide.BUY);

		BigDecimal totalValue = BigDecimal.ZERO;
		BigDecimal totalQuantity = BigDecimal.ZERO;

		for (Transaction transaction : transactions) {
			totalValue = totalValue.add(transaction.getQuantity().multiply(transaction.getPrice()));
			totalQuantity = totalQuantity.add(transaction.getQuantity());
		}

		return totalQuantity.compareTo(BigDecimal.ZERO) > 0 ? totalValue.divide(totalQuantity, 8, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
	}


	public BigDecimal getWeightedAverageSellPrice(String asset, Wallet wallet) {
		List<Transaction> transactions = repository.findByAssetAndUserAndProviderAndSide(asset, wallet.getUser(),
				wallet.getProvider(), TradeSide.SELL);

		BigDecimal totalValue = BigDecimal.ZERO;
		BigDecimal totalQuantity = BigDecimal.ZERO;

		for (Transaction transaction : transactions) {
			BigDecimal absQty = transaction.getQuantity().abs();
			totalValue = totalValue.add(absQty.multiply(transaction.getPrice()));
			totalQuantity = totalQuantity.add(absQty);
		}

		return totalQuantity.compareTo(BigDecimal.ZERO) > 0 ? totalValue.divide(totalQuantity, 8, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
	}


	public BigDecimal getTotalBuyValue(String asset, Wallet wallet) {
		List<Transaction> transactions = repository.findByAssetAndUserAndProviderAndSide(asset, wallet.getUser(),
				wallet.getProvider(), TradeSide.BUY);

		return transactions.stream().map(p -> p.getQuantity().multiply(p.getPrice())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
	}


	public BigDecimal getTotalSellValue(String asset, Wallet wallet) {
		List<Transaction> transactions = repository.findByAssetAndUserAndProviderAndSide(asset, wallet.getUser(),
				wallet.getProvider(), TradeSide.SELL);

		return transactions.stream().map(p -> p.getQuantity().abs().multiply(p.getPrice())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
	}


	public BigDecimal getNetQuantity(String asset, Wallet wallet) {
		List<Transaction> buyTransactions = repository.findByAssetAndUserAndProviderAndSide(asset, wallet.getUser(),
				wallet.getProvider(), TradeSide.BUY);

		List<Transaction> sellTransactions = repository.findByAssetAndUserAndProviderAndSide(asset, wallet.getUser(),
				wallet.getProvider(), TradeSide.SELL);

		BigDecimal totalBuyQty = buyTransactions.stream()
				.map(Transaction::getQuantity)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal totalSellQty = sellTransactions.stream()
				.map(p -> p.getQuantity().abs())
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		return totalBuyQty.subtract(totalSellQty);
	}

	public BigDecimal getNetQuantityByWallet(String asset, Wallet wallet) {
		List<Transaction> buyTransactions = repository.findByWalletAndAssetAndSide(wallet, asset, TradeSide.BUY);

		List<Transaction> sellTransactions = repository.findByWalletAndAssetAndSide(wallet, asset, TradeSide.SELL);

		BigDecimal totalBuyQty = buyTransactions.stream()
				.map(Transaction::getQuantity)
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		BigDecimal totalSellQty = sellTransactions.stream()
				.map(p -> p.getQuantity().abs())
				.reduce(BigDecimal.ZERO, BigDecimal::add);

		return totalBuyQty.subtract(totalSellQty);
	}

	/**
	 * Synchronisation incrémentale (nouveaux trades depuis dernière sync)
	 */
	@Transactional
	public void incrementalSync(Wallet wallet) {
		// Force le chargement du User avant usage
		Hibernate.initialize(wallet.getUser());

		logger.info("⏳ Incremental sync started for wallet={} of user={} on exchange={}", wallet.getName(), wallet.getUser().getUsername(), wallet.getProviderCode());

        ApiCredential credential = wallet.getCredential();
		if( credential == null ){
			logger.warn("Wallet '{}' for user {} doesn't have credential...", wallet.getName(), wallet.getUser().getUsername());
			return;
		}

		try {
			// Retrieve pairs from Balance account
			Map<String, BigDecimal> allBalances = apiClientService.getAllBalances(wallet);
			Set<String> pairs = new HashSet<>();
			for (Map.Entry<String, BigDecimal> bal : allBalances.entrySet()) {
				if (bal.getValue().compareTo(BigDecimal.ZERO) != 0) {
					pairs.add(bal.getKey() + "USDC");
					// pairs.add(bal.getKey() + "USDT");
				}
			}

			Optional<LocalDateTime> lastSync = repository.findLastTransactionDateByUserAndProvider(credential.getUser(), credential.getProvider());

			List<TradeDto> trades;

			if (lastSync.isPresent()) {
				trades = apiClientService.getTradesSince( wallet, lastSync.get(), pairs);
			} else {
				trades = apiClientService.getHistoricalTrades( wallet, pairs, wallet.getCredential());
			}

			logger.debug(trades.size() + " trades received");
			int inserted = 0;
			for (TradeDto trade : trades) {
				if (repository.existsByExternalTransactionId(trade.getTradeId())) {
					continue;
				}
				Transaction transaction = mapTradeToTransaction(trade, wallet);
				repository.save(transaction);
				inserted++;
			}

			logger.info("✅ Incremental sync completed: {} new positions imported", inserted);

		} catch (Exception e) {
			logger.error("❌ Error during incremental sync for user={} exchange={}", credential.getUser().getUsername(), credential.getProvider().getCode(), e);
			throw new RuntimeException("Incremental sync failed", e);
		}
	}

	/**
	 * Mappe un DTO Trade provenant de l'API vers une Position persistable
	 */
	private Transaction mapTradeToTransaction(TradeDto trade, Wallet wallet) {
		Transaction transaction = new Transaction();

		transaction.setExternalTransactionId(trade.getTradeId());
		transaction.setUser(wallet.getUser());
		transaction.setProvider(wallet.getProvider());
		transaction.setWallet(wallet);
		transaction.setAsset(trade.getAsset());
		transaction.setQuantity(trade.getQuantity());
		transaction.setPrice(trade.getPrice());
		transaction.setTimestamp(trade.getTimestamp());
		transaction.setSide(trade.getSide());
		transaction.setFee(trade.getFee());

		// autres champs selon ta classe Position

		return transaction;
	}
}