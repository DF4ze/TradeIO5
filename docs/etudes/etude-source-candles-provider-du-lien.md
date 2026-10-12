# Étude — Source des candles du Rainbow live : provider du lien, sans fallback silencieux

Date : 2026-10-12. Statut : implémentée le 2026-10-12 (chemin D1 natif, signal par lien, fallback bruyant, `asset_provider` à la volée). État courant : [`../architecture/04-market-data.md`](../architecture/04-market-data.md) et [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md). Décision finale sur le point bloquant Kraken : chemin D1 natif retenu ; la bascule des bougies manquantes sur Binance au-delà de 720 j est documentée comme extension (known-gaps §8bis), non faite.

## Besoin
Le signal Rainbow d'un lien doit être calculé sur les candles **de l'exchange où l'ordre sera passé**. Constat de Clem sous TradingView : le Rainbow PAXG se comporte très différemment selon le provider.

## Constat (code, 2026-10-12)
- `RainbowLiveExecutionService.loadSeries` appelle `MarketDatasetEngine.getDatasetForAsset(asset, D1, 4000, asOf)` : **par actif seul**, une fois par passe, le même dataset pour tous les liens de l'actif.
- `getDatasetForAsset` parcourt les lignes `asset_provider` activées par `priority`, écarte celles dont `maxHorizonDays` < fenêtre demandée, bascule sur la suivante sur `SymbolNotFound` / `ProviderUnavailable`.
- Semis `AssetInitializer` (BTC, PAXG) : Binance prio 0 (`BTCUSDT`, `PAXGUSDT`), Kraken prio 1 (`XXBTZUSD`, `PAXGUSD`, `maxHorizonDays`=25), OKX prio 2 (`…-USDT`). Avec 4000 j demandés, **Kraken est toujours écarté** : le Rainbow tourne aujourd'hui sur Binance (cotation USDT), quel que soit l'exchange d'exécution.
- `MarketDataApiClient` ne lit que des données publiques : aucun credential n'intervient dans le choix des candles.
- `Wallet` porte `source` / code interne (BINANCE, KRAKEN, …) : c'est le lien lien -> exchange.
- L'ATH de référence (`rainbow_ath_reference`) est **par actif**, semé une fois sur l'historique D1 complet puis alimenté par la passe 23:55 (à passer par (actif, source), fenêtre 720 j).
- Kraken `/public/OHLC` : 720 dernières bougies, non paginable (`MarketDataProviderLimits`).

## Décisions de Clem (2026-10-12)
1. **Un signal par lien**, calculé sur la source du lien (exchange du wallet), pas une source de référence par preset.
2. **Pas de fallback silencieux** quand la source est imposée. Mais l'échec ne doit pas bloquer tout : **fallback autorisé avec un gros WARNING** (visible log + page Admin), signal marqué « source de substitution ».
3. **Ligne `asset_provider` manquante pour (actif, source du lien)** : créée à la volée, **uniquement pour une source de confiance** (liste blanche : Binance, Kraken, OKX).
4. **Actif seul (sans lien)** : comportement actuel (priorités, fallback silencieux = défauts).
5. **Cotation PAXG** : on reste sur PAXG/USDC pour le signal et l'exécution (route `USDC -> BTC -> PAXG`). La lecture du signal sur PAXG/BTC est une étude à part entière (voir `known-gaps/rainbow-dca-chantiers-ouverts.md` §9).

## Conception retenue
- `getDatasetForAsset(symbol, tf, lookBack, endTime, MarketDataSource preferred)` : `preferred` non nul => ce candidat seul est tenté. Échec => fallback par priorité **avec WARN explicite** et indicateur `substituted=true` (source réellement utilisée) renvoyé au service Rainbow. `preferred` nul => comportement actuel inchangé.
- Création à la volée : `AssetProvider(actif, source, providerSymbol, priority haute, maxHorizonDays du provider)` si la source est dans la liste blanche ; `providerSymbol` par table de mapping connue (pas de devinette).
- `RainbowLiveExecutionService` : regroupement des liens par (actif, source), un `loadSeries` par couple, résultat partagé entre les liens du couple. Signal calculé par lien. Bucket : clé existante (symbole + source + param) => pas de collision entre exchanges.
- Source d'un lien : exchange du wallet -> `MarketDataSource` (mapping explicite ; wallet sans exchange de marché => comportement « actif seul »).
- Persistance : le pass/run du lien enregistre la source utilisée et si c'était une substitution (affichée sur la page).
- Pas de credentials : la requête candles reste publique.

## Décision complémentaire : fenêtre D1 ramenée à 720 j (2026-10-12)
- `D1_LOOKBACK_CANDLES` passe de 4000 à **720**. 720 j suffisent à capter l'ATH de BTC, ETH et PAXG (dernier ATH de chacun dans la fenêtre) ; cela divise fortement les requêtes aux providers.
- **ATH = plus haut de la fenêtre de 720 j**, par (actif, source) puisque le signal est par source. Plus de semis sur l'historique complet.
- **Limite à documenter (architecture + known-gaps)** : si un jour un actif exige plus de 720 j d'historique (ATH plus ancien que la fenêtre), il faudra agir (fenêtre plus large, historique profond ou ATH stocké). Un actif sans ATH depuis 720 j est considéré **mort** : pas de support prévu.
- 720 j = limite native Kraken `OHLC` en D1 (interval 1440).

## Point bloquant : Kraken est H1-only dans le Bucket
`KrakenMarketDataApiClient` ne gère que `TimeFrame.H1` (`NATIVE_INTERVALS`) ; le `Bucket` stocke du H1 et agrège. Kraken ne renvoie que 720 bougies -> **30 j de H1**, pas 720 j de D1. Même à 720 j, le signal Kraken est donc impossible via le Bucket H1 actuel. Piste retenue à confirmer : chemin **D1 natif** pour le Rainbow (Kraken `interval=1440`, 720 bougies = 720 j en un appel, persisté dans le cache DB des candles, hors Bucket H1), aligné sur la politique cache DB. Les autres sources (Binance, OKX) peuvent suivre le même chemin D1 pour un comportement uniforme.

## Hors périmètre
Route de signal PAXG/BTC, jonglage entre actifs, fallback automatique de route d'exécution.
