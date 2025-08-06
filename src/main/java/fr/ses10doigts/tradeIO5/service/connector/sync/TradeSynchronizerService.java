package fr.ses10doigts.tradeIO5.service.connector.sync;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.dto.TradeDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Position;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.repository.PositionRepository;
import fr.ses10doigts.tradeIO5.service.connector.ExchangeApiService;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.ExchangeApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TradeSynchronizerService {

    private final PositionRepository positionRepository; // Ton repo Position
	private final ExchangeApiService apiClientService; // Pour appeler l'API Exchange

    private final Logger logger = LoggerFactory.getLogger(TradeSynchronizerService.class);

    /**
     * Synchronisation complète (import historique) des positions
     */
    @Transactional
    public void fullSync(ApiCredential credential) {
        logger.info("🔄 Full sync started for user={} on exchange={}", credential.getUser().getUsername(), credential.getExchange().getCode());

        ExchangeApiClient client = apiClientService.getClient(credential.getExchange().getCode());

		// Retrieve pairs from Balance account
		Map<String, BigDecimal> allBalances = client.getAllBalances(credential);
		Set<String> pairs = new HashSet<>();
		for (Entry<String, BigDecimal> bal : allBalances.entrySet()) {
			if (bal.getValue().compareTo(BigDecimal.ZERO) != 0) {
				pairs.add(bal.getKey() + "USDC");
				pairs.add(bal.getKey() + "USDT");
			}
		}

		List<TradeDto> trades;

		try {
			trades = client.getHistoricalTrades(credential, pairs);
            int inserted = 0;

            for (TradeDto trade : trades) {
                // Check doublon via externalTradeId
                if (positionRepository.existsByExternalTradeId(trade.getTradeId())) {
                    continue;
                }

                Position position = mapTradeToPosition(trade, credential);
                positionRepository.save(position);
                inserted++;
            }

            logger.info("✅ Full sync completed: {} new positions imported", inserted);

        } catch (Exception e) {
            logger.error("❌ Error during full sync for user={} exchange={}", credential.getUser().getUsername(), credential.getExchange().getCode(), e);
            throw new RuntimeException("Full sync failed", e);
        }
    }

    /**
     * Synchronisation incrémentale (nouveaux trades depuis dernière sync)
     */
    @Transactional
    public void incrementalSync(ApiCredential credential) {
        logger.info("⏳ Incremental sync started for user={} on exchange={}", credential.getUser().getUsername(), credential.getExchange().getCode());

        ExchangeApiClient client = apiClientService.getClient(credential.getExchange().getCode());

        try {
			// Retrieve pairs from Balance account
			Map<String, BigDecimal> allBalances = client.getAllBalances(credential);
			Set<String> pairs = new HashSet<>();
			for (Entry<String, BigDecimal> bal : allBalances.entrySet()) {
				if (bal.getValue().compareTo(BigDecimal.ZERO) != 0) {
					pairs.add(bal.getKey() + "USDC");
					// pairs.add(bal.getKey() + "USDT");
				}
			}

            Optional<LocalDateTime> lastSync = positionRepository.findLastTradeDateByUserAndExchange(credential.getUser(), credential.getExchange());

            List<TradeDto> trades;

            if (lastSync.isPresent()) {
				trades = client.getTradesSince(credential, lastSync.get(), pairs);
            } else {
				trades = client.getHistoricalTrades(credential, pairs);
            }

			logger.debug(trades.size() + " trades received");
            int inserted = 0;
            for (TradeDto trade : trades) {
                if (positionRepository.existsByExternalTradeId(trade.getTradeId())) {
                    continue;
                }
                Position position = mapTradeToPosition(trade, credential);
                positionRepository.save(position);
                inserted++;
            }

            logger.info("✅ Incremental sync completed: {} new positions imported", inserted);

        } catch (Exception e) {
            logger.error("❌ Error during incremental sync for user={} exchange={}", credential.getUser().getUsername(), credential.getExchange().getCode(), e);
            throw new RuntimeException("Incremental sync failed", e);
        }
    }

    /**
     * Mappe un DTO Trade provenant de l'API vers une Position persistable
     */
    private Position mapTradeToPosition(TradeDto trade, ApiCredential credential) {
        Position position = new Position();

        position.setExternalTradeId(trade.getTradeId());
        position.setUser(credential.getUser());
        position.setExchange(credential.getExchange());
        position.setAsset(trade.getAsset());
        position.setQuantity(trade.getQuantity());
        position.setPrice(trade.getPrice());
        position.setTimestamp(trade.getTimestamp());
		position.setSide(trade.getSide());
        position.setFee(trade.getFee());

        // autres champs selon ta classe Position

        return position;
    }
}