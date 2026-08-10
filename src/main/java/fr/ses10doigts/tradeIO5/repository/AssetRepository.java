package fr.ses10doigts.tradeIO5.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;

import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    // Cf. docs/etude-fallback-multi-provider-marketdata.md §3 (étape 6) : upsert idempotent par
    // symbole dans AssetInitializer.
    Optional<Asset> findBySymbol(String symbol);
}
