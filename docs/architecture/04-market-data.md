# Données de marché (candles)

Vérifié le : 2026-09-12 (`service/market/dataset/MarketDatasetEngine`, `service/market/provider/MarketDataProviderRegistry`, `configuration/MarketDataCachingConfig`, `model/enumerate/market/MarketDataSource`).

## Deux chemins de résolution, ne pas les confondre

1. **Résolution par table `asset_provider` (chemin courant)** : priorité configurable en base, fallback en cascade entre exchanges (Binance/Kraken/OKX), filtrée par `maxHorizonDays` (un provider n'ayant pas assez d'historique pour la requête est sauté). C'est le chemin utilisé par `MarketDatasetEngine` pour résoudre quel provider interroger pour un asset donné.
2. **`MarketDataSource` (enum) — chemin explicite plus ancien**, conservé principalement pour les tests. Déclare `COINBASE`, `UNISWAP`, `SUSHISWAP`, `CHAINLINK` en plus de `BINANCE`/`KRAKEN`/`OKX`/`MEMORY`/`FILE`/`DATABASE` — **mais `MarketDataProviderRegistry` n'a de factory que pour `MEMORY`/`FILE`/`DATABASE`/`BINANCE`/`KRAKEN`/`OKX`**. Les 4 valeurs `COINBASE`/`UNISWAP`/`SUSHISWAP`/`CHAINLINK` sont des branches mortes de l'enum, non implémentées — à ne pas présenter comme des exchanges supportés.

## Cache

Chaque `MarketDataApiClient` par exchange (Binance/Kraken/OKX, déjà `@Component`) est enveloppé dans un `CachingMarketDataApiClient` par `MarketDataCachingConfig`, avec persistance des candles en base (`CandleRepository`) et horodatage via `DomainClock` (jamais `Instant.now()` en dur — convention appliquée dans tout le projet, y compris hors de ce module). Les 3 beans cachés sont nommés explicitement (`cachingBinanceMarketDataApiClient`, etc.) plutôt que via `@Primary`, car les 6 implémentations (3 brutes + 3 cachées) partagent la même interface `MarketDataApiClient`.

## DCA

`service/dca/DcaCalculatorService` + `DcaMcpTools` (tool MCP `calculate_dca`, doc de référence citée dans le code : `docs/etudes/etude-dca-tool-mcp.md`). Non ré-audité en détail dans ce lot au-delà du point d'enregistrement MCP (`configuration/McpServerConfig`).
