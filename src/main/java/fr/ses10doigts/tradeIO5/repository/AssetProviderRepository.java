package fr.ses10doigts.tradeIO5.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;

import java.util.List;
import java.util.Optional;

public interface AssetProviderRepository extends JpaRepository<AssetProvider, Long> {

    // Cf. docs/etudes/etude-fallback-multi-provider-marketdata.md §3 (étape 7) : liste ordonnée de
    // candidats provider pour un asset, du plus prioritaire au moins prioritaire.
    List<AssetProvider> findByAsset_SymbolOrderByPriorityAsc(String symbol);

    // Cf. docs/etudes/etude-fallback-multi-provider-marketdata.md §3 (étape 6) : lookup ciblé utilisé
    // par l'upsert idempotent d'AssetInitializer pour savoir si la ligne (asset, source) existe déjà.
    Optional<AssetProvider> findByAsset_SymbolAndSource(String symbol, MarketDataSource source);
}
