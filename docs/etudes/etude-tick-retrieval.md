# Étude — Brancher le tick/candle-retrieval sur Binance, Kraken et OKX

Objectif : permettre à la chaîne `Indicator → Strategy → Opinion` de fonctionner sur de vraies données, en remplaçant les `MarketDataProvider` stubs par de véritables appels aux API publiques de marché de Binance, Kraken et OKX.

## 1. Verdict rapide

C'est faisable exchange par exchange, sans toucher à `Indicator`/`Strategy`/`Opinion`/`Bucket` (leur contrat est déjà correct). Mais il y a **un bug bloquant en amont, indépendant du choix de l'exchange**, qui doit être corrigé en premier : le cache `MarketDatasetCache` ne peut jamais accumuler de données en usage réel. Sans ce correctif, brancher Binance/Kraken/OKX fera des appels API qui "marchent" mais qui repartiront de zéro à chaque fois — le Bucket ne recevra jamais de flux continu.

## 2. Le blocage prioritaire : `MarketDatasetCache` ne fait jamais de cache-hit en live

`MarketDatasetCache.getState()` utilise `MarketDatasetRequest` (un `record`) comme clé de map :

```java
private final Map<MarketDatasetRequest, MarketDatasetState> states = new ConcurrentHashMap<>();

MarketDatasetState getState(MarketDatasetRequest request) {
    return states.computeIfAbsent(request, r -> new MarketDatasetState(request.symbol(), Bucket.BASE_MAX_ITEMS));
}
```

Un `record` calcule `equals()`/`hashCode()` sur **tous** ses champs — y compris `endTime`. Or `MarketDatasetRequest.endTime()` change à chaque appel en usage live (`Instant.now()` à chaque tick du scheduler, ou par indicateur si les indicateurs ne passent pas exactement le même timestamp). Résultat concret :

- Chaque appel avec un `endTime` différent crée un **nouveau** `MarketDatasetState`, donc un **nouveau** `Bucket` vide.
- Le Bucket ne s'enrichit jamais dans la durée : on refetch et on repart de zéro à chaque tick.
- Si deux indicateurs demandent le même symbole avec des `lookBack` différents, ils obtiennent chacun leur propre Bucket au lieu de partager la même série H1 — exactement l'inverse de ce que `Bucket` est censé optimiser (une seule ingestion, plusieurs vues agrégées).

Le TODO déjà présent dans le fichier le confirme : `// TODO : Peut-etre uniquement la paire en clé...?`

**Correctif nécessaire avant tout branchement d'exchange** : clé de cache sur `(symbol, timeFrame de base, source, providerParam)` uniquement — pas sur `endTime` ni `lookBack`. Ça ne casse rien côté Indicator/Strategy puisque `MarketDatasetManager.snapshot()` filtre déjà la fenêtre demandée (`startTime`/`endTime`) à partir de la vue du Bucket ; le cache n'a besoin d'identifier que "quel flux natif", pas "quelle fenêtre".

## 3. Comparatif des 3 API candles

| | Binance | Kraken | OKX |
|---|---|---|---|
| Endpoint | `GET /api/v3/klines` | `GET /0/public/OHLC` | `GET /api/v5/market/candles` (+ `history-candles` pour plus ancien) |
| Auth requise | Non (public) | Non (public) | Non (public) |
| Intervalles | 1m,3m,5m,15m,30m,1h,2h,4h,6h,8h,12h,1d,3d,1w,1M | minutes : 1,5,15,30,60,240,1440,10080,21600 | 1m,3m,5m,15m,30m,1H,2H,4H,...,1D,1W,1M |
| Pagination historique | `startTime`/`endTime`, `limit` (défaut 500, max 1000) | **`since` limité : toujours les 720 derniers points max, impossible de remonter plus loin via cette route** | `limit` (~100-300/appel) + `history-candles` avec curseurs `after`/`before` pour remonter |
| Lib déjà dans le projet | `binance-connector-java:3.4.1` déjà en dépendance, `SpotClientImpl.createMarket().klines(params)` prêt à l'emploi | Aucun SDK ; `KrakenApiClient` fait déjà du `WebClient` brut (`publicGet`/`privatePost`) — réutilisable tel quel pour `OHLC` (route publique) | Aucun client existant, à créer (`WebClient` simple, comme Kraken, encore plus simple car pas de signature à faire pour les candles) |
| Rate limit | Poids par requête, plusieurs milliers/minute en pratique | Limites par tier de compte (public assez large) | ~20 req/2s sur les endpoints marché publics, code erreur 50026 si dépassement |

Point important sur Kraken : la route REST `OHLC` **ne permet pas de backfill historique profond** (toujours les 720 derniers points, quel que soit `since`). Pour amorcer un historique long sur Kraken il faudrait soit démarrer "à vide" et laisser le Bucket s'accumuler en live au fil du temps, soit s'appuyer une fois sur les fichiers CSV historiques téléchargeables que Kraken fournit hors API (hors scope de ce ticket). Binance et OKX n'ont pas cette limite (pagination normale par `startTime`/`endTime` ou curseurs).

Sources :
- [Binance — Market Data endpoints](https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints)
- [Kraken — Get OHLC Data](https://docs.kraken.com/api/docs/rest-api/get-ohlc-data)
- [OKX — Get Candlesticks](https://web3.okx.com/onchainos/dev-docs-v5/dex-api/dex-market-candlesticks)
- [OKX — Get Candlesticks History](https://web3.okx.com/build/dev-docs/wallet-api/market-candlesticks-history)
- [binance-connector-java (GitHub)](https://github.com/binance/binance-connector-java)

## 4. Mapping avec `TimeFrame` interne — et pourquoi on peut limiter le scope

`TimeFrame` définit : `Y1, Y3, M1, M2, M3, M6, W1, W2, D1, H1, H4, H12, MIN1, MIN5`. Aucun exchange n'a une correspondance 1:1 parfaite (ex: aucun n'a "2 semaines" ou "3 ans" en natif).

Mais ce n'est pas un problème : `Bucket` n'ingère **qu'un seul TimeFrame natif** (`BASE_TIME_FRAME = H1`, en dur aujourd'hui) et **agrège localement** vers tous les TF supérieurs (H4, H12, D1, W1, W2, M1...) via `Bucket.aggregate()`. Ça veut dire concrètement :

- Il suffit d'implémenter la récupération **d'un seul TF par exchange (H1)**, pas des 14 valeurs de l'enum.
- Binance : `H1` → `"1h"`. Kraken : `H1` → `60` (minutes). OKX : `H1` → `"1H"`. Un simple `Map<TimeFrame,String>` par client suffit, pas besoin d'une couche d'abstraction complexe.
- Limite structurelle à connaître : `Bucket.canAggregate()` n'autorise que `target >= base` (`isGreaterOrEqualThan`). Si un indicateur a un jour besoin de `MIN1`/`MIN5` (plus fin que H1), il faudra soit changer le TF de base du Bucket à `MIN5`, soit ajouter un second Bucket dédié à un TF plus fin — un choix à faire séparément, pas urgent aujourd'hui.

## 5. Où brancher concrètement dans le code

### 5.1 Un problème de conception à trancher avant de coder : `ProviderApiClient` est user-scoped, les candles ne le sont pas

`ProviderApiClient` (et donc `BinanceApiClient`/`KrakenApiClient`) est construit autour d'`ApiCredential`, qui est **obligatoirement lié à un `User`** (`@ManyToOne(optional = false)`). C'est cohérent pour `getBalance`/`getTradesSince` (données privées d'un compte), mais les candles/klines sont des données **publiques**, identiques pour tout le monde, sans clé API. Faire porter la récupération de candles par `ProviderApiClient` obligerait à traîner un `ApiCredential` d'un utilisateur juste pour lire un prix public — ce qui n'a pas de sens et couplerait à tort le "marché" au "compte utilisateur".

Recommandation : **une interface séparée**, par exemple `MarketDataApiClient`, non liée à `ApiCredential` :

```java
public interface MarketDataApiClient {
    MarketDataSource getSource();
    List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit);
}
```

Implémentée par `BinanceMarketDataApiClient`, `KrakenMarketDataApiClient`, `OkxMarketDataApiClient` — des `@Component` Spring simples, chacun avec son propre `WebClient`/SDK, sans dépendance à `ApiCredential`.

### 5.2 Chaîne de branchement, dans l'ordre

1. **Corriger `MarketDatasetCache`** (section 2) — clé sur `(symbol, timeFrame, source, providerParam)`.
2. **Créer `MarketDataApiClient`** + les 3 implémentations (5.1). Binance réutilise `SpotClientImpl.createMarket().klines(...)` déjà en dépendance ; Kraken réutilise le pattern `WebClient` déjà écrit dans `KrakenApiClient.publicGet(...)` (à extraire dans la nouvelle classe, sans `ApiCredential`) ; OKX est un nouveau `WebClient` vers `https://www.okx.com`, sans signature pour les candles.
3. **Implémenter les 3 méthodes de `MarketDataProvider`** dans `BinanceMarketDataProvider` (déjà présent mais stub), et créer `KrakenMarketDataProvider` / `OkxMarketDataProvider` (n'existent pas encore) : `fullLoad`/`loadSince`/`fetchMarketData` appellent le `MarketDataApiClient` correspondant et mappent le JSON brut vers `MarketData` (attention aux formats : Binance renvoie un tableau positionnel `[openTime, open, high, low, close, volume, closeTime, ...]`, Kraken un objet `result.<pair>` avec des tableaux `[time, open, high, low, close, vwap, volume, count]`, OKX un tableau `[ts, o, h, l, c, vol, volCcy, volCcyQuote, confirm]`).
4. **Ajouter les factories manquantes dans `MarketDataProviderRegistry`** : seules `MEMORY`, `FILE`, `DATABASE`, `BINANCE` existent aujourd'hui. `MarketDataSource.KRAKEN` et `MarketDataSource.OKX` sont bien déclarés dans l'enum mais **n'ont pas de factory** — `getProvider(KRAKEN, ...)` lève `IllegalStateException` aujourd'hui. À corriger en même temps.
5. **Mapper `TimeFrame.H1`** vers l'intervalle natif de chaque exchange (table simple, section 4).
6. **Créer le scheduler manquant** : `service/scheduler/` est un dossier vide, rien n'appelle `MarketDatasetEngine.getDataset()` périodiquement en prod. Sans ça, même une fois tout branché, le Bucket ne recevra jamais de nouvelle bougie tant que rien n'appelle `loadSince()` régulièrement pour les symboles suivis. Un `@Scheduled` simple (toutes les heures, alignée sur H1) suffit pour démarrer.
7. **Gestion d'erreurs minimale** : `BinanceApiClient`/`KrakenApiClient` actuels se contentent de logguer et retourner `0`/liste vide en cas d'échec (pas de retry). Pour une ingestion en continu, prévoir au minimum un retry avec backoff sur erreurs réseau/rate-limit (429/50026 OKX, erreurs Kraken en JSON `error[]`), pour ne pas casser silencieusement le flux du Bucket.

## 6. Risques et points d'attention à garder en tête

- **Kraken** : pas de backfill profond via REST (limite dure à 720 bougies) — accepter un démarrage "à froid" qui se remplit avec le temps, ou prévoir un import ponctuel des CSV historiques Kraken si un historique immédiat est nécessaire.
- **`ApiCredential` obligatoirement lié à un `User`** : en séparant `MarketDataApiClient` de `ProviderApiClient` (5.1), ce problème disparaît pour les candles — aucune raison de créer un `ApiCredential` fictif.
- **`MarketDatasetCache`** : correctif à faire *avant* de brancher quoi que ce soit, sinon les 3 exchanges auront le même symptôme (refetch permanent, Bucket jamais alimenté durablement).
- **OKX rate limit** (~20 req/2s sur le marché) : gérable facilement si l'ingestion reste au rythme "1 fetch H1 par heure et par symbole suivi" — le vrai risque seraient des backfills agressifs multi-symboles en rafale au démarrage.
- **Bucket mono-TF natif (H1, en dur)** : suffisant pour débloquer toute la chaîne actuelle (Strategy/Opinion n'utilisent que des TF ≥ H1 dans les tests vus). À ne changer que si un indicateur infra-horaire est requis plus tard.

## 7. Ce que ça débloquerait concrètement

Une fois les points 1 à 6 faits pour **un seul exchange** (Binance étant le plus simple : SDK déjà présent, pas de limite de backfill, mapping TimeFrame le plus complet), toute la chaîne `IndicatorEngine → Strategy → MarketOpinion` fonctionnerait sur données réelles sans aucune modification de ces couches — exactement le découplage que l'architecture `tree` avait prévu. Kraken et OKX peuvent être ajoutés ensuite en suivant le même patron, un `MarketDataApiClient` + un `MarketDataProvider` + une entrée dans `MarketDataProviderRegistry` à chaque fois.
