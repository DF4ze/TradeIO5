package fr.ses10doigts.tradeIO5.repository;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Premier test {@code @DataJpaTest} du projet : configuration Spring Boot Test standard + H2
 * (déjà en dépendance de test) suffit, aucune configuration supplémentaire nécessaire.
 * Cf. docs/etude-fallback-multi-provider-marketdata.md §3 (étape 5).
 */
@DataJpaTest
@DisplayName("AssetProviderRepository")
class AssetProviderRepositoryTest {

    @Autowired
    private AssetProviderRepository assetProviderRepository;

    @Autowired
    private AssetRepository assetRepository;

    @Test
    @DisplayName("findByAsset_SymbolOrderByPriorityAsc renvoie les providers triés par priorité croissante")
    void findByAssetSymbolOrderByPriorityAsc_returnsProvidersSortedByPriority() {
        Asset btc = assetRepository.save(Asset.builder()
                .symbol("BTC")
                .name("Bitcoin")
                .decimals(8)
                .build());

        AssetProvider kraken = assetProviderRepository.save(AssetProvider.builder()
                .asset(btc)
                .source(MarketDataSource.KRAKEN)
                .providerSymbol("XXBTZUSD")
                .priority(1)
                .maxHorizonDays(25)
                .build());

        AssetProvider binance = assetProviderRepository.save(AssetProvider.builder()
                .asset(btc)
                .source(MarketDataSource.BINANCE)
                .providerSymbol("BTCUSDT")
                .priority(0)
                .build());

        AssetProvider okx = assetProviderRepository.save(AssetProvider.builder()
                .asset(btc)
                .source(MarketDataSource.OKX)
                .providerSymbol("BTC-USDT")
                .priority(2)
                .build());

        List<AssetProvider> result = assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc("BTC");

        assertEquals(3, result.size());
        assertEquals(List.of(binance.getId(), kraken.getId(), okx.getId()),
                result.stream().map(AssetProvider::getId).toList());
        assertEquals(List.of(0, 1, 2), result.stream().map(AssetProvider::getPriority).toList());
    }

    @Test
    @DisplayName("La contrainte unique (asset_id, source) empêche deux providers pour le même couple asset/source")
    void uniqueConstraint_preventsDuplicateAssetSourcePair() {
        Asset eth = assetRepository.save(Asset.builder()
                .symbol("ETH")
                .name("Ethereum")
                .decimals(18)
                .build());

        assetProviderRepository.saveAndFlush(AssetProvider.builder()
                .asset(eth)
                .source(MarketDataSource.BINANCE)
                .providerSymbol("ETHUSDT")
                .priority(0)
                .build());

        AssetProvider duplicate = AssetProvider.builder()
                .asset(eth)
                .source(MarketDataSource.BINANCE)
                .providerSymbol("ETHUSDT-DUP")
                .priority(1)
                .build();

        assertThrows(DataIntegrityViolationException.class,
                () -> assetProviderRepository.saveAndFlush(duplicate));
    }
}
