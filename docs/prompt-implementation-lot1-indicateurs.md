# Prompt d'implémentation — Lot 1 (indicateurs macro/externes)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le contexte des études précédentes. Il couvre le **Lot 1** défini dans `docs/etude-indicateurs-macro-externes.md` (§14) : 4 chantiers, tous gratuits, sans blocage de source, qui posent les fondations du reste de la roadmap (le Lot 2 — Strategy de qualification de mouvement notamment — dépend de ce qui est livré ici).

Avant de commencer, lire dans l'ordre :
1. `docs/etude-indicateurs-macro-externes.md` — §0 (patron d'intégration), §6/§7/§9/§12 (contenu détaillé des 4 items ci-dessous).
2. Le code du patron de référence pour un indicateur externe : `service/tree/indicator/external/FearAndGreedIndicator.java`, `service/tree/indicator/external/AbstractExternalIndicator.java`, `service/tree/indicator/external/feargreed/FearAndGreedProvider.java` et `CoinstatsFearAndGreedClient.java`.
3. `service/tree/indicator/IndicatorCredentialResolver.java`, `model/enumerate/WebProviderCode.java`, `model/enumerate/tree/indicator/IndicatorType.java`.
4. `service/tree/opinion/impl/GlobalMarketOpinion.java` (opinion `GLOBAL` existante, seul exemple actuel de consommation directe d'un indicateur externe hors `Strategy`).
5. `configuration/initializer/WebProviderInitializer.java` et `ApiCredentialInitializer.java` — patron d'enregistrement d'un provider et de sa credential pour l'utilisateur technique `"System"`.
6. `service/connector/apiclient/marketdata/BinanceMarketDataApiClient.java` — patron d'appel à l'API publique Binance sans clé.

Ne rien modifier en dehors de ce qui est listé ci-dessous. Chaque item doit rester compilable et testé indépendamment des trois autres (aucune dépendance croisée entre les 4 items de ce lot).

---

## Item A — Fear & Greed : règle d'évolution (aucune nouvelle source)

**Fichiers concernés** : `service/tree/opinion/impl/GlobalMarketOpinion.java`, `service/tree/helper/MarketOpinionHelper.java`.

**Contexte** : `FearAndGreedResponse` porte déjà `now`/`yesterday`/`lastWeek` (chacun un objet avec `getValue()`), mais `GlobalMarketOpinion.decide()` n'utilise aujourd'hui que `now` via `MarketOpinionHelper.computeRsiScore(now, buyThreshold, sellThreshold)`. Le document source (`Indicateurs.odt`) demande explicitement de regarder l'évolution : une hausse brutale du Fear & Greed est un signal de retournement probable, pas un signal contrarian fiable à suivre tel quel.

**À faire** :
1. Dans `GlobalMarketOpinion.decide()`, récupérer aussi `yesterday` (et `lastWeek` si disponible) depuis le même `snapshot` déjà interrogé pour `now` — pas de nouvel appel réseau, la donnée est déjà dans `FearAndGreedResponse`/`IndicatorResult.getValues()`.
2. Ajouter une méthode (dans `MarketOpinionHelper`, à côté de `computeRsiScore`, ou une nouvelle méthode privée dans `GlobalMarketOpinion` si la logique est jugée trop spécifique pour être générique) qui calcule un facteur d'atténuation à partir de `delta = now - yesterday` :
   - Si `now` est déjà dans une zone extrême (ex. `now <= buyThreshold` ou `now >= sellThreshold`, mêmes seuils que ceux déjà utilisés) **et** que `|delta|` dépasse un seuil paramétrable (proposer une constante par défaut, ex. `15.0` points sur 24h, exposée en `MarketOpinionParameters` comme `P_BUY_THRESHOLD`/`P_SELL_THRESHOLD` le sont déjà — voir le patron existant), alors réduire la confiance du signal contrarian (facteur multiplicatif entre 0 et 1, proportionnel à l'ampleur du mouvement au-delà du seuil) plutôt que de l'annuler brutalement à 0.
   - Sinon, facteur neutre (1.0) — comportement actuel inchangé.
3. Appliquer ce facteur à la `confidence` (pas au `score` directionnel — l'idée n'est pas d'inverser le signal, juste de le rendre moins affirmatif quand le sentiment vient de bouger trop vite).
4. Gérer le cas où `yesterday` est `null`/absent dans la réponse (ne pas lancer d'exception, retomber sur le comportement actuel sans atténuation).
5. Logger le calcul (`delta`, facteur appliqué) au même niveau `debug` que le reste de la méthode.

**Tests attendus** : un test qui vérifie qu'une hausse brutale (`now` élevé, `yesterday` nettement plus bas) réduit la confidence par rapport à un `now` identique mais stable ; un test qui vérifie le comportement inchangé quand `yesterday` est absent ; un test sur le cas où `now` n'est pas en zone extrême (le delta ne doit rien changer, même s'il est important — la règle ne s'applique qu'en zone extrême).

---

## Item B — Market Cap Stablecoins (DefiLlama)

**Source** : `GET https://stablecoins.llama.fi/stablecoins?includePrices=true` — gratuit, **sans clé API**, pas d'authentification. Format de réponse confirmé (vérifié en direct) :

```json
{
  "peggedAssets": [
    {
      "id": "1",
      "name": "Tether",
      "symbol": "USDT",
      "pegType": "peggedUSD",
      "circulating": { "peggedUSD": 184096473539.0687 },
      "circulatingPrevDay": { "peggedUSD": 184102908636.32526 },
      "circulatingPrevWeek": { "peggedUSD": 185005726592.55988 },
      "circulatingPrevMonth": { "peggedUSD": 187335042280.94324 }
    },
    { "...": "un objet par stablecoin, plusieurs centaines d'entrées" }
  ]
}
```

La capitalisation totale des stablecoins = somme de `circulating.peggedUSD` sur tous les éléments de `peggedAssets` où `pegType == "peggedUSD"` (filtrer sur ce champ : certains éléments sont peggés sur l'EUR ou d'autres devises et n'ont pas de `peggedUSD` significatif). Faire la même somme sur `circulatingPrevDay`/`circulatingPrevWeek`/`circulatingPrevMonth` pour obtenir, comme pour Fear & Greed, une lecture d'évolution en plus du niveau absolu (utile directement pour une future règle similaire à l'item A, mais pas dans le scope de ce lot — juste exposer les 4 valeurs dans `IndicatorResult.values`).

**À faire** :
1. `WebProviderCode.DEFILLAMA` — nouvelle valeur.
2. `IndicatorType.STABLECOIN_MARKET_CAP` — nouvelle valeur.
3. Package `service/tree/indicator/external/stablecoin/` (à créer), sur le patron `external/feargreed/` :
   - `StablecoinMarketCapProvider` (interface, une méthode `fetch(ApiCredentialDTO credential)` retournant un DTO de réponse).
   - `DefiLlamaStablecoinClient` (`extends AbstractExternalIndicator implements StablecoinMarketCapProvider`), appel `getWebClient(credential).get().uri("/stablecoins?includePrices=true")...`, mêmes garde-fous que `CoinstatsFearAndGreedClient` (`onStatus` 4xx/5xx → `ExternalApiException`, `timeout(Duration.ofSeconds(20))`, retour `.invalid()` propre sur toute erreur, jamais d'exception qui remonte).
   - DTO de réponse (`model/dto/tree/indicator/external/StablecoinMarketCapResponse.java`) avec un champ `valid` (booléen, comme `FearAndGreedResponse`) et les 4 totaux calculés (`total`, `totalPrevDay`, `totalPrevWeek`, `totalPrevMonth`).
4. `StablecoinMarketCapIndicator implements Indicator` dans `service/tree/indicator/external/` (au même niveau que `FearAndGreedIndicator`, pas dans le sous-package `stablecoin/`) : `getType() = STABLECOIN_MARKET_CAP`, `getRequiredData() = 0`, `getParametersNames() = List.of(AbstractExternalIndicator.P_CREDENTIAL)`, `compute(...)` qui appelle le provider et retourne `IndicatorResult` avec `values = Map.of("total", ..., "totalPrevDay", ..., "totalPrevWeek", ..., "totalPrevMonth", ...)`.
5. `IndicatorCredentialResolver.resolve(...)` : ajouter `case STABLECOIN_MARKET_CAP -> WebProviderCode.DEFILLAMA;`.
6. `WebProviderInitializer`/`ApiCredentialInitializer` : ajouter l'enregistrement du provider `DEFILLAMA` (base URL `https://stablecoins.llama.fi`) et une credential pour l'utilisateur `"System"` — même si l'API ne demande pas de clé, le contrat `ApiCredentialDTO`/`getWebClient` attend un `baseUrl` porté par la credential résolue ; `apiKey`/`secretKey` peuvent rester vides/`null`.

**Tests attendus** : mapping JSON → DTO (au moins un cas avec plusieurs `peggedAssets` de `pegType` différents, vérifier que seuls les `peggedUSD` sont sommés) ; comportement `invalid()` sur 4xx/5xx/timeout, identique au test existant de `FearAndGreedIndicator`/`CoinstatsFearAndGreedClient`.

---

## Item C — Open Interest + Funding Rate + Liquidations (Coinalyze, un seul provider pour trois besoins)

**Prérequis humain** : créer un compte gratuit sur [coinalyze.net](https://coinalyze.net) et générer une clé API sur `https://coinalyze.net/account/api-key/`. Rate limit : 40 appels/minute par clé.

**Auth** : header ou query param `api_key`.

**Étape 0 — résolution des symboles** (à faire une fois, avant le reste, et à documenter dans le code) : Coinalyze identifie chaque marché par un code propre, ex. `BTCUSDT_PERP.A` — le suffixe après le point encode l'exchange, et n'est **pas** à deviner. Appeler `GET https://api.coinalyze.net/v1/future-markets` (authentifié), filtrer la réponse sur l'exchange voulu (probablement Binance, à confirmer par le champ `exchange`/`code` retourné par `GET /v1/exchanges`) et le `symbol_on_exchange` correspondant au symbole TradeIO5 (ex. `BTCUSDT`), pour obtenir le `symbol` Coinalyze exact à utiliser dans tous les appels suivants. Ne pas coder le suffixe `.A` en dur sans avoir vérifié cette correspondance.

**Endpoints à utiliser** (spec vérifiée sur `https://api.coinalyze.net/v1/doc/`) :

| Besoin | Endpoint | Paramètres | Réponse |
|---|---|---|---|
| Open Interest courant | `GET /v1/open-interest` | `symbols` (CSV, max 20) | `[{ "symbol", "value", "update" }]` |
| Funding Rate courant | `GET /v1/funding-rate` | `symbols` | `[{ "symbol", "value", "update" }]` |
| Historique de liquidations | `GET /v1/liquidation-history` | `symbols`, `interval` (ex. `1hour`), `from`/`to` (UNIX seconds), `convert_to_usd` | `[{ "symbol", "history": [{ "t", "l", "s" }] }]` (`l`/`s` = volumes liquidés long/short sur la période) |

Il n'existe **pas** d'endpoint "liquidation courante" (contrairement à OI/funding) — seulement un historique. Pour un indicateur "maintenant", interroger `liquidation-history` sur une fenêtre glissante récente (ex. les dernières 24h avec `interval=1hour`) et sommer `l`/`s` sur la fenêtre.

**À faire** :
1. `WebProviderCode.COINALYZE` — nouvelle valeur.
2. `IndicatorType.OPEN_INTEREST`, `IndicatorType.FUNDING_RATE`, `IndicatorType.LIQUIDATIONS` — trois nouvelles valeurs.
3. Package `service/tree/indicator/external/coinalyze/` :
   - `CoinalyzeSymbolResolver` (ou méthode utilitaire) : mappe un symbole TradeIO5 (`String`, ex. `"BTCUSDT"`) vers le code Coinalyze (résolu selon l'étape 0 — soit codé en dur pour les symboles déjà tradés par le projet après vérification manuelle, soit résolu dynamiquement via `/v1/future-markets` avec cache, au choix de l'implémentation — documenter le choix fait).
   - `CoinalyzeClient extends AbstractExternalIndicator`, trois méthodes (`fetchOpenInterest`, `fetchFundingRate`, `fetchLiquidations`), mêmes garde-fous que `CoinstatsFearAndGreedClient` (4xx/5xx → exception dédiée, timeout, jamais d'exception non gérée qui remonte à l'`Indicator`).
   - DTOs de réponse correspondant aux 3 formes JSON ci-dessus, chacun avec un statut `valid`.
4. Trois classes `Indicator` (`OpenInterestIndicator`, `FundingRateIndicator`, `LiquidationsIndicator`) dans `service/tree/indicator/external/` :
   - Toutes trois ont besoin du **symbole** (contrairement à Fear & Greed/Stablecoin Market Cap) : utiliser `context.symbol()` de l'`IndicatorContext` pour résoudre le code Coinalyze avant l'appel.
   - `getRequiredData() = 0` pour les trois (valeurs externes, pas de warmup sur bougies).
   - `OpenInterestIndicator`/`FundingRateIndicator` : `IndicatorResult.value` = la valeur courante ; `LiquidationsIndicator` : `IndicatorResult.values = Map.of("long", ..., "short", ..., "total", ...)` sur la fenêtre choisie (documenter la fenêtre par défaut, ex. 24h, et l'exposer en paramètre si possible via `IndicatorParameters.getNumeric(...)`, sur le même principe que `AdxIndicator`/`period`).
5. `IndicatorCredentialResolver.resolve(...)` : ajouter `case OPEN_INTEREST, FUNDING_RATE, LIQUIDATIONS -> WebProviderCode.COINALYZE;`.
6. `WebProviderInitializer`/`ApiCredentialInitializer` : provider `COINALYZE` (base URL `https://api.coinalyze.net/v1`) + credential `"System"` avec la clé générée manuellement (pas de valeur par défaut committée en clair — suivre le même mécanisme que les autres credentials sensibles du projet, cf. mémoire projet sur `application-dev/prod.properties` gitignorées).

**Tests attendus** : mapping JSON → DTO pour les 3 endpoints ; comportement `invalid()` sur erreur réseau/HTTP pour chacun ; un test qui vérifie que `LiquidationsIndicator` somme correctement plusieurs points d'historique sur la fenêtre demandée.

---

## Item D — Carnet d'ordres Binance (marché, sans levier)

**Source** : `GET https://api.binance.com/api/v3/depth?symbol={symbol}&limit={limit}` — publique, gratuite, sans clé API. `limit` typique : 100 ou 500 (paliers acceptés par Binance : 5/10/20/50/100/500/1000/5000). Réponse : `{ "lastUpdateId": ..., "bids": [["prix","quantité"], ...], "asks": [["prix","quantité"], ...] }`, triée par proximité du prix courant.

**Rappel de cadrage (important, déjà tranché dans l'étude)** : ceci est le carnet d'ordres à cours limité (liquidité *placée*), pas les ordres "market" (qui ne stationnent jamais dans le carnet) et sans aucune notion de levier — c'est un indicateur différent de l'item C (Open Interest/Funding/Liquidations). **Scope de ce lot** : exposer une lecture directe et simple du carnet, pas un algorithme de détection de "zones" — le regroupement en zones de liquidité est un problème de définition à traiter plus tard (Lot 3, avec §5 zones de rejet), pas ici.

**À faire** :
1. Pas de nouveau `WebProviderCode` : `BINANCE` existe déjà (public, sans clé, comme pour les klines).
2. `IndicatorType.ORDER_BOOK` — nouvelle valeur.
3. Étendre `BinanceMarketDataApiClient` (ou créer une classe sœur dédiée dans le même package `service/connector/apiclient/marketdata/`, à trancher selon si l'ajout reste cohérent avec la responsabilité actuelle de la classe — `BinanceMarketDataApiClient` sert aujourd'hui les klines pour `Bucket`, pas des indicateurs ponctuels ; si le couplage semble artificiel, préférer un client séparé, ex. `BinanceOrderBookApiClient`, mais réutiliser le même `SpotClientImpl` déjà instancié dans `BinanceMarketDataApiClient` si possible plutôt que d'en recréer un) : méthode qui appelle l'endpoint `depth` — vérifier si `com.binance.connector.client.impl.SpotClientImpl` (déjà en dépendance) expose déjà cet endpoint via `createMarket().depth(params)`, ce qui éviterait d'écrire l'appel HTTP à la main (même mécanique que `.klines(params)` déjà utilisé).
4. `OrderBookIndicator implements Indicator` dans `service/tree/indicator/impl/` (pas `external/` — même si la donnée vient d'un appel réseau, le patron du projet range dans `external/` les valeurs qui passent par `AbstractExternalIndicator`/credential-based ; ici pas de credential nécessaire, donc plus proche dans l'esprit d'un accès marché public comme les klines — trancher à l'implémentation selon ce qui est le plus cohérent avec le reste du package, documenter le choix). `getRequiredData() = 0`. `compute(...)` a besoin de `context.symbol()`.
5. Calculer et exposer dans `IndicatorResult.values` au minimum :
   - `bidVolume` / `askVolume` : somme des quantités sur une bande de prix autour du prix courant (proposer un paramètre, ex. ±1% du meilleur bid/ask, exposé via `IndicatorParameters`).
   - `imbalance` : `(bidVolume - askVolume) / (bidVolume + askVolume)`, dans `[-1, 1]` — lecture directe et déjà exploitable (déséquilibre acheteur/vendeur visible dans le carnet).
6. Pas de modification à `IndicatorCredentialResolver` (aucune credential nécessaire — l'indicateur peut être appelé avec `parameters.getCredential() == null`, vérifier que `checkParameters()`/le reste du pipeline le tolère, comme c'est déjà le cas pour tout indicateur qui ne liste pas `P_CREDENTIAL` dans `getParametersNames()`).

**Tests attendus** : mapping de la réponse Binance brute vers `bidVolume`/`askVolume`/`imbalance` (au moins un cas déséquilibré et un cas équilibré) ; comportement sur carnet vide/erreur réseau (retour `invalid()`, pas d'exception).

---

## Definition of done (les 4 items)

- Compilation propre, aucun test existant cassé.
- Chaque nouvel `Indicator` interrogeable de bout en bout via le chemin déjà existant pour Fear & Greed (`IndicatorEngine.execute(...)`, donc aussi via l'outil MCP `get_indicator` s'il expose déjà `IndicatorType` dynamiquement — vérifier `TreeAnalysisFacade`).
- Chaque nouveau provider (`DEFILLAMA`, `COINALYZE`) enregistré via les initializers existants, avec une credential `"System"` fonctionnelle en environnement de dev (`application-dev.properties`, gitignoré — ne jamais committer la clé Coinalyze en clair).
- Chaque nouveau client réseau retombe sur un DTO `invalid()` propre en cas de panne/timeout/erreur HTTP — jamais d'exception non gérée qui remonte jusqu'à l'appelant, exactement comme `FearAndGreedIndicator`/`CoinstatsFearAndGreedClient` le font déjà.
- Pas de branchement dans une `Strategy` ou une nouvelle `MarketOpinion` pour les items B/C/D à ce stade (ça, c'est le Lot 2) — seul l'item A touche à une `MarketOpinion` existante (`GlobalMarketOpinion`), parce que c'est un ajustement de règle sur un indicateur déjà branché, pas un nouveau branchement.
