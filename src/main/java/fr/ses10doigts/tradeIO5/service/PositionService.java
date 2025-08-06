package fr.ses10doigts.tradeIO5.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.currency.Position;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.repository.PositionRepository;
import fr.ses10doigts.tradeIO5.security.model.User;

@Service
public class PositionService {

    private final PositionRepository repository;

    public PositionService(PositionRepository repository) {
        this.repository = repository;
    }

	public List<Position> getByUser(User user) {
		return repository.findByUser(user);
	}

    public Optional<Position> getById(Long id) {
        return repository.findById(id);
    }

    public Position save(Position asset) {
        return repository.save(asset);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }


	public BigDecimal getWeightedAverageBuyPrice(String asset, ApiCredential credential) {
		List<Position> positions = repository.findByAssetAndUserAndExchangeAndSide(asset, credential.getUser(),
				credential.getExchange(), TradeSide.BUY);

		BigDecimal totalValue = BigDecimal.ZERO;
		BigDecimal totalQuantity = BigDecimal.ZERO;

		for (Position position : positions) {
			totalValue = totalValue.add(position.getQuantity().multiply(position.getPrice()));
			totalQuantity = totalQuantity.add(position.getQuantity());
		}

		return totalQuantity.compareTo(BigDecimal.ZERO) > 0 ? totalValue.divide(totalQuantity, 8, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
	}


	public BigDecimal getWeightedAverageSellPrice(String asset, ApiCredential credential) {
		List<Position> positions = repository.findByAssetAndUserAndExchangeAndSide(asset, credential.getUser(),
				credential.getExchange(), TradeSide.SELL);

		BigDecimal totalValue = BigDecimal.ZERO;
		BigDecimal totalQuantity = BigDecimal.ZERO;

		for (Position position : positions) {
			BigDecimal absQty = position.getQuantity().abs();
			totalValue = totalValue.add(absQty.multiply(position.getPrice()));
			totalQuantity = totalQuantity.add(absQty);
		}

		return totalQuantity.compareTo(BigDecimal.ZERO) > 0 ? totalValue.divide(totalQuantity, 8, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;
	}


	public BigDecimal getTotalBuyValue(String asset, ApiCredential credential) {
		List<Position> positions = repository.findByAssetAndUserAndExchangeAndSide(asset, credential.getUser(),
				credential.getExchange(), TradeSide.BUY);

		return positions.stream().map(p -> p.getQuantity().multiply(p.getPrice())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
	}


	public BigDecimal getTotalSellValue(String asset, ApiCredential credential) {
		List<Position> positions = repository.findByAssetAndUserAndExchangeAndSide(asset, credential.getUser(),
				credential.getExchange(), TradeSide.SELL);

		return positions.stream().map(p -> p.getQuantity().abs().multiply(p.getPrice())).reduce(BigDecimal.ZERO,
				BigDecimal::add);
	}

}