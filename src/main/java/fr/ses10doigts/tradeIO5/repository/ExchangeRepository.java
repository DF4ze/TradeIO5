package fr.ses10doigts.tradeIO5.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.Exchange;

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {
    Optional<Exchange> findByCodeIgnoreCase(String code);
}