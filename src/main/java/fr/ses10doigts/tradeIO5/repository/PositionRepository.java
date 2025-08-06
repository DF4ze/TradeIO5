package fr.ses10doigts.tradeIO5.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import fr.ses10doigts.tradeIO5.model.entity.currency.Position;
import fr.ses10doigts.tradeIO5.model.entity.exchange.Exchange;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.security.model.User;

public interface PositionRepository extends JpaRepository<Position, Long> {

	boolean existsByExternalTradeId(String tradeId);

	@Query("SELECT MAX(p.timestamp) FROM Position p WHERE p.user = :user AND p.exchange = :exchange")
	Optional<LocalDateTime> findLastTradeDateByUserAndExchange(User user, Exchange exchange);

	List<Position> findByUser(User user);

	List<Position> findByAssetAndUserAndExchangeAndSide(String asset, User user, Exchange exchange, TradeSide side);

}