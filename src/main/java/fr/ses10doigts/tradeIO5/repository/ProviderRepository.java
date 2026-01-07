package fr.ses10doigts.tradeIO5.repository;

import java.util.Optional;

import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.exchange.Provider;

public interface ProviderRepository extends JpaRepository<Provider, Long> {
    Optional<Provider> findByCode(ProviderCode code);
}