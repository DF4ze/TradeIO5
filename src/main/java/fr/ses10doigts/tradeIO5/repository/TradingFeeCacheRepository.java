package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.TradingFeeCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradingFeeCacheRepository extends JpaRepository<TradingFeeCache, Long> {

    Optional<TradingFeeCache> findByCredentialIdAndInstKey(Long credentialId, String instKey);
}
