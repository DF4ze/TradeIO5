# Données de marché (candles)

Vérifié le : 2026-10-04 (`service/dca/atr/**` ; autres références : 2026-09-12 — `service/market/dataset/MarketDatasetEngine`, `service/market/provider/MarketDataProviderRegistry`, `configuration/MarketDataCachingConfig`, `model/enumerate/market/MarketDataSource`).

## Deux chemins de résolution, ne pas les confondre

1. **Résolution par table `asset_provider` (chemin courant)** : priorité configurable en base, fallback en cascade entre exchanges (Binance/Kraken/OKX), filtrée par `maxHorizonDays` (un provider n'ayant pas assez d'historique pour la requête est sauté). C'est le chemin utilisé par `MarketDatasetEngine` pour résoudre quel provider interroger pour un asset donné.
2. **`MarketDataSource` (enum) — chemin explicite plus ancien**, conservé principalement pour les tests. Déclare `COINBASE`, `UNISWAP`, `SUSHISWAP`, `CHAINLINK` en plus de `BINANCE`/`KRAKEN`/`OKX`/`MEMORY`/`FILE`/`DATABASE` — **mais `MarketDataProviderRegistry` n'a de factory que pour `MEMORY`/`FILE`/`DATABASE`/`BINANCE`/`KRAKEN`/`OKX`**. Les 4 valeurs `COINBASE`/`UNISWAP`/`SUSHISWAP`/`CHAINLINK` sont des branches mortes de l'enum, non implémentées — à ne pas présenter comme des exchanges supportés.

## Cache

Chaque `MarketDataApiClient` par exchange (Binance/Kraken/OKX, déjà `@Component`) est enveloppé dans un `CachingMarketDataApiClient` par `MarketDataCachingConfig`, avec persistance des candles en base (`CandleRepository`) et horodatage via `DomainClock` (jamais `Instant.now()` en dur — convention appliquée dans tout le projet, y compris hors de ce module). Les 3 beans cachés sont nommés explicitement (`cachingBinanceMarketDataApiClient`, etc.) plutôt que via `@Primary`, car les 6 implémentations (3 brutes + 3 cachées) partagent la même interface `MarketDataApiClient`.

## DCA

`service/dca/DcaCalculatorService` + `DcaMcpTools` (tool MCP `calculate_dca`, doc de référence citée dans le code : `docs/etudes/etude-dca-tool-mcp.md`). Non ré-audité en détail dans ce lot au-delà du point d'enregistrement MCP (`configuration/McpServerConfig`).

### Rainbow DCA ATR v3 (moteur pur, `service/dca/atr/`)

Port Java fidèle de `tools/pine/rainbow_dca_v4_atr_moon.pine` (**le pine est la source de vérité**), sans Spring ni BigDecimal (double, µs par bougie) pour pouvoir être rejoué des centaines de milliers de fois par un bench :

- `RainbowAtrDataset` : série D1 en `double[]` + caches SMA (`ta.sma`), ATR (`ta.atr` = RMA, TR[0]=high-low), ATH et drapeaux To the moon, tous calculés **depuis la 1re bougie de la série** (jamais depuis le début de la fenêtre rejouée).
- `RainbowAtrTuning` (bornes ATR + mécanisme : réentrées, cooldown, verrou DOWN2…) et `RainbowAtrGlobals` (modulation ATH, To the moon, multiplicateurs de zone, base) — records immuables avec `toBuilder().set(nom, valeur)`.
- `RainbowAtrEngine.simulate(...)` : rejeu d'une fenêtre. Accepte un tableau de `RainbowAtrTuning` + un régime par bougie (état de la machine conservé lors des changements de jeu). Ordre des opérations et finesses (réserve moon plafonnant les ventes, stop moon, cliquet, facteurs ATH neutralisés en moon, verrou d'achat levé par un nouveau franchissement sous DOWN2, cooldown / `allowSellDuringCooldown` / `cooldownAfterSellOn`, armement achat gelé tant que vente armée ou cooldown) documentés dans la javadoc de la classe.
- Indépendant de `RainbowDcaBacktestService` (BigDecimal, DB/Binance, pas d'ATH/moon). Les deux coexistent ; seul le moteur `atr` suit le pine v4.

Pine : sélecteur `Preset` nommé (Perso Generic commun aux actifs ; « <ACTIF> Bench global » = jeu global du bench ; « <ACTIF> Perso Bear/Bull » = jeux par trend réglés à la main) / CUSTOM ; pas de mode Auto ni de sélection automatique par la Trend (choix Bull/Bear manuel ; branchement Trend → jeu = chantier ouvert, cf. [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md)). Histogramme « taille de position » sur toute la largeur de la vue (table ancrée en bas en pixels, colonnes réparties sur la plage visible, recalculé au zoom/déplacement ; lignes vides sous le tableau pour éviter le chevauchement ; métrique valeur de marché / coût / quantité ; vert = achat, rouge = vente).

Parité vérifiée contre un port Python indépendant du pine (`tools/pine/rainbow_atr_pine_port.py`, 60 cas aléatoires identiques, + 6 cas golden dans `RainbowAtrEngineTest`). Persistance/presets du bench grandeur nature : [`08-rainbow-bench-grandeur-nature.md`](08-rainbow-bench-grandeur-nature.md). Bench et résultats : [`calibration/calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md).
