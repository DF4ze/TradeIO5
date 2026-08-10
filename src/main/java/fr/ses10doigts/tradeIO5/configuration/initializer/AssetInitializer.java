package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.currency.Asset;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.AssetRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seed des {@link Asset} et de leur table de jointure {@link AssetProvider} (fallback
 * multi-provider, cf. docs/etude-fallback-multi-provider-marketdata.md §3 étape 6).
 * <p>
 * Upsert idempotent, rejouable à chaque démarrage (remplace l'ancien seed one-shot
 * {@code if (assetRepository.count() == 0)}) :
 * <ul>
 *     <li>{@link Asset} : créé s'il n'existe pas encore (recherché par {@code symbol}) ; jamais
 *     modifié s'il existe déjà, {@code symbol}/{@code name}/{@code decimals} ne changent pas
 *     dynamiquement.</li>
 *     <li>{@link AssetProvider} : créé s'il n'existe pas encore pour le couple
 *     {@code (asset, source)} (avec {@code enabled = true} par défaut). S'il existe déjà,
 *     {@code providerSymbol}/{@code priority}/{@code maxHorizonDays} sont resynchronisés sur la
 *     définition de seed, mais {@code enabled} n'est JAMAIS touché : c'est un kill switch
 *     opérationnel qui a pu être basculé manuellement (ex: après un incident), un redéploiement
 *     ne doit pas l'annuler silencieusement.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Order(1)
public class AssetInitializer implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AssetInitializer.class);

    private final AssetRepository assetRepository;
    private final AssetProviderRepository assetProviderRepository;

    private record AssetSeed(String symbol, String name, int decimals) {
    }

    private record ProviderSeed(String assetSymbol, MarketDataSource source, String providerSymbol,
                                 int priority, Integer maxHorizonDays) {
    }

    /**
     * Kraken/OKX ne renvoient en pratique que les ~25 derniers jours de bougies H1
     * (cf. {@code DcaCalculatorService.NON_BINANCE_MAX_HORIZON_DAYS}) — valeur reprise ici pour
     * Kraken tant que cette constante existe. OKX est {@code null} (illimité) : le routing
     * {@code /market/history-candles} de l'étape 2 (docs/prompt-implementation-fallback-etapes-0-3.md)
     * est mergé (cf. tableau §3 de l'étude, étape 2 = ✅ Fait), donc plus de limite bornée côté OKX.
     */
    private static final Integer KRAKEN_MAX_HORIZON_DAYS = 25;

    // 7 assets déjà présents en prod + PAXG (jamais seedé jusqu'ici malgré une utilisation prod
    // déjà existante, cf. docs/etude-fallback-multi-provider-marketdata.md §1). Decimals PAXG
    // vérifié (pas supposé) : PAXG est un ERC-20 à 18 décimales, cf. contrat
    // 0x45804880De22913dAFE09f4980848ECE6EcbAf78 (`decimals = 18`, Etherscan/GitHub paxosglobal/paxos-gold-contract).
    private static final List<AssetSeed> ASSET_SEEDS = List.of(
            new AssetSeed("BTC", "Bitcoin", 8),
            new AssetSeed("ETH", "Ethereum", 18),
            new AssetSeed("SOL", "Solana", 9),
            new AssetSeed("BNB", "Binance", 18),
            new AssetSeed("XRP", "Ripple", 6),
            new AssetSeed("USDT", "USD Tether", 6),
            new AssetSeed("USDC", "USD Circle", 6),
            new AssetSeed("PAXG", "PAX Gold", 18)
    );

    /**
     * {@code provider_symbol} vérifiés empiriquement (pas devinés), le 2026-08-10 :
     * <ul>
     *     <li>BTC : déjà confirmé par les fixtures de test existantes
     *     ({@code KrakenMarketDataApiClientTest}, {@code OkxMarketDataApiClientTest}).</li>
     *     <li>Kraken (ETH/SOL/BNB/XRP/USDT/USDC/PAXG) : {@code GET /0/public/AssetPairs} —
     *     clés internes retournées par l'API (pas les {@code altname}, par cohérence avec BTC qui
     *     utilise déjà la clé interne {@code XXBTZUSD} et pas l'altname {@code XBTUSD}) :
     *     {@code XETHZUSD}, {@code SOLUSD}, {@code BNBUSD}, {@code XXRPZUSD}, {@code USDTZUSD},
     *     {@code USDCUSD}, {@code PAXGUSD}.</li>
     *     <li>OKX (ETH/SOL/BNB/XRP/USDC/PAXG) : {@code GET /api/v5/public/instruments?instType=SPOT&instId=...} —
     *     tous confirmés {@code code=0} (instrument existant) avec le pattern {@code BASE-USDT}.
     *     Exception : {@code USDT-USDT} renvoie {@code code=51001} ("Instrument ID ... doesn't
     *     exist") — logique, USDT ne se trade pas contre lui-même. Pas de ligne OKX seedée pour
     *     USDT plutôt que d'écrire un {@code provider_symbol} inventé (cf. consigne "ne pas
     *     deviner").</li>
     *     <li>Binance : convention {@code symbol + "USDT"} déjà en place dans le reste du code,
     *     considérée fiable telle quelle (confirmée pour {@code PAXGUSDT} en prod) — non
     *     re-vérifiée ici, y compris pour {@code USDTUSDT} (cf. même remarque que ci-dessus :
     *     paire qui n'a probablement pas de sens réel sur Binance non plus, mais hors scope de
     *     cette vérification empirique qui ne portait que sur Kraken/OKX).</li>
     * </ul>
     */
    private static final List<ProviderSeed> PROVIDER_SEEDS = List.of(
            new ProviderSeed("BTC", MarketDataSource.BINANCE, "BTCUSDT", 0, null),
            new ProviderSeed("BTC", MarketDataSource.KRAKEN, "XXBTZUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("BTC", MarketDataSource.OKX, "BTC-USDT", 2, null),

            new ProviderSeed("ETH", MarketDataSource.BINANCE, "ETHUSDT", 0, null),
            new ProviderSeed("ETH", MarketDataSource.KRAKEN, "XETHZUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("ETH", MarketDataSource.OKX, "ETH-USDT", 2, null),

            new ProviderSeed("SOL", MarketDataSource.BINANCE, "SOLUSDT", 0, null),
            new ProviderSeed("SOL", MarketDataSource.KRAKEN, "SOLUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("SOL", MarketDataSource.OKX, "SOL-USDT", 2, null),

            new ProviderSeed("BNB", MarketDataSource.BINANCE, "BNBUSDT", 0, null),
            new ProviderSeed("BNB", MarketDataSource.KRAKEN, "BNBUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("BNB", MarketDataSource.OKX, "BNB-USDT", 2, null),

            new ProviderSeed("XRP", MarketDataSource.BINANCE, "XRPUSDT", 0, null),
            new ProviderSeed("XRP", MarketDataSource.KRAKEN, "XXRPZUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("XRP", MarketDataSource.OKX, "XRP-USDT", 2, null),

            // Pas de ligne OKX pour USDT : USDT-USDT n'existe pas chez OKX (vérifié, code 51001).
            new ProviderSeed("USDT", MarketDataSource.BINANCE, "USDTUSDT", 0, null),
            new ProviderSeed("USDT", MarketDataSource.KRAKEN, "USDTZUSD", 1, KRAKEN_MAX_HORIZON_DAYS),

            new ProviderSeed("USDC", MarketDataSource.BINANCE, "USDCUSDT", 0, null),
            new ProviderSeed("USDC", MarketDataSource.KRAKEN, "USDCUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("USDC", MarketDataSource.OKX, "USDC-USDT", 2, null),

            new ProviderSeed("PAXG", MarketDataSource.BINANCE, "PAXGUSDT", 0, null),
            new ProviderSeed("PAXG", MarketDataSource.KRAKEN, "PAXGUSD", 1, KRAKEN_MAX_HORIZON_DAYS),
            new ProviderSeed("PAXG", MarketDataSource.OKX, "PAXG-USDT", 2, null)
    );

    @Override
    public void run(String... args) {
        Map<String, Asset> assetsBySymbol = upsertAssets();
        upsertProviders(assetsBySymbol);
    }

    private Map<String, Asset> upsertAssets() {
        Map<String, Asset> assetsBySymbol = new HashMap<>();
        for (AssetSeed seed : ASSET_SEEDS) {
            Optional<Asset> existing = assetRepository.findBySymbol(seed.symbol());
            if (existing.isPresent()) {
                assetsBySymbol.put(seed.symbol(), existing.get());
                continue;
            }
            Asset created = assetRepository.save(Asset.builder()
                    .symbol(seed.symbol())
                    .name(seed.name())
                    .decimals(seed.decimals())
                    .build());
            logger.info("✅ Asset créé : {}", seed.symbol());
            assetsBySymbol.put(seed.symbol(), created);
        }
        return assetsBySymbol;
    }

    private void upsertProviders(Map<String, Asset> assetsBySymbol) {
        for (ProviderSeed seed : PROVIDER_SEEDS) {
            Asset asset = assetsBySymbol.get(seed.assetSymbol());
            if (asset == null) {
                logger.warn("Aucun Asset '{}' résolu, AssetProvider {} ignoré.", seed.assetSymbol(), seed.source());
                continue;
            }

            Optional<AssetProvider> existing = assetProviderRepository
                    .findByAsset_SymbolAndSource(seed.assetSymbol(), seed.source());

            if (existing.isPresent()) {
                AssetProvider provider = existing.get();
                provider.setProviderSymbol(seed.providerSymbol());
                provider.setPriority(seed.priority());
                provider.setMaxHorizonDays(seed.maxHorizonDays());
                // enabled volontairement non touché ici : kill switch opérationnel manuel.
                assetProviderRepository.save(provider);
            } else {
                assetProviderRepository.save(AssetProvider.builder()
                        .asset(asset)
                        .source(seed.source())
                        .providerSymbol(seed.providerSymbol())
                        .priority(seed.priority())
                        .maxHorizonDays(seed.maxHorizonDays())
                        .enabled(true)
                        .build());
                logger.info("✅ AssetProvider créé : {} / {}", seed.assetSymbol(), seed.source());
            }
        }
    }
}
