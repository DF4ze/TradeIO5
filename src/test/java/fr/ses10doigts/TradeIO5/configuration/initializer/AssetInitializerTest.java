package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cf. docs/etude-fallback-multi-provider-marketdata.md §3 (étape 6) : l'upsert doit être
 * rejouable sans dupliquer ni écraser un réglage opérationnel (`enabled`), mais doit resynchroniser
 * les champs déclaratifs (`priority`, `providerSymbol`, `maxHorizonDays`).
 */
@DataJpaTest
@DisplayName("AssetInitializer")
class AssetInitializerTest {

    @Autowired
    private AssetRepository assetRepository;

    @Autowired
    private AssetProviderRepository assetProviderRepository;

    private AssetInitializer initializer;

    @BeforeEach
    void setup() {
        initializer = new AssetInitializer(assetRepository, assetProviderRepository);
    }

    @Test
    @DisplayName("Relancer le runner deux fois de suite ne duplique rien")
    void runningTwiceDoesNotDuplicateAnything() {
        initializer.run();
        long assetCountAfterFirstRun = assetRepository.count();
        long providerCountAfterFirstRun = assetProviderRepository.count();
        assertTrue(assetCountAfterFirstRun > 0);
        assertTrue(providerCountAfterFirstRun > 0);

        initializer.run();

        assertEquals(assetCountAfterFirstRun, assetRepository.count());
        assertEquals(providerCountAfterFirstRun, assetProviderRepository.count());
    }

    @Test
    @DisplayName("PAXG est bien seedé (asset + les 3 providers), absent du seed one-shot précédent")
    void paxgIsSeededWithItsThreeProviders() {
        initializer.run();

        assertTrue(assetRepository.findBySymbol("PAXG").isPresent());
        assertTrue(assetProviderRepository.findByAsset_SymbolAndSource("PAXG", MarketDataSource.BINANCE).isPresent());
        assertTrue(assetProviderRepository.findByAsset_SymbolAndSource("PAXG", MarketDataSource.KRAKEN).isPresent());
        assertTrue(assetProviderRepository.findByAsset_SymbolAndSource("PAXG", MarketDataSource.OKX).isPresent());
    }

    @Test
    @DisplayName("USDT n'a volontairement pas de ligne OKX (paire USDT-USDT inexistante chez OKX)")
    void usdtHasNoOkxProvider() {
        initializer.run();

        assertTrue(assetProviderRepository.findByAsset_SymbolAndSource("USDT", MarketDataSource.BINANCE).isPresent());
        assertTrue(assetProviderRepository.findByAsset_SymbolAndSource("USDT", MarketDataSource.KRAKEN).isPresent());
        assertTrue(assetProviderRepository.findByAsset_SymbolAndSource("USDT", MarketDataSource.OKX).isEmpty());
    }

    @Test
    @DisplayName("Basculer enabled=false manuellement puis relancer le runner ne le remet pas à true")
    void manuallyDisablingProviderSurvivesRerun() {
        initializer.run();
        AssetProvider btcBinance = assetProviderRepository
                .findByAsset_SymbolAndSource("BTC", MarketDataSource.BINANCE).orElseThrow();
        btcBinance.setEnabled(false);
        assetProviderRepository.save(btcBinance);

        initializer.run();

        AssetProvider reloaded = assetProviderRepository
                .findByAsset_SymbolAndSource("BTC", MarketDataSource.BINANCE).orElseThrow();
        assertFalse(reloaded.isEnabled(), "enabled=false est un kill switch manuel, un redéploiement ne doit pas l'annuler");
    }

    @Test
    @DisplayName("Modifier priority en base puis relancer le runner la resynchronise sur la valeur de seed")
    void manuallyChangedPriorityIsResynchronizedFromSeed() {
        initializer.run();
        AssetProvider btcBinance = assetProviderRepository
                .findByAsset_SymbolAndSource("BTC", MarketDataSource.BINANCE).orElseThrow();
        int seedPriority = btcBinance.getPriority();
        btcBinance.setPriority(seedPriority + 99);
        assetProviderRepository.save(btcBinance);

        initializer.run();

        AssetProvider reloaded = assetProviderRepository
                .findByAsset_SymbolAndSource("BTC", MarketDataSource.BINANCE).orElseThrow();
        assertEquals(seedPriority, reloaded.getPriority());
    }
}
