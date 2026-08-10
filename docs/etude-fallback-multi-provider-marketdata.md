# Étude — Fallback multi-provider pour les données de marché

Point de départ : une question sur l'exchange utilisé pour PAXG a révélé plusieurs trous d'architecture autour de la récupération de market data (klines/candles). Ce document consolide le diagnostic et les décisions prises, puis séquence l'implémentation. Ne pas lancer une étape avant que la précédente ne soit terminée (compilée + testée) : chaque étape suivante s'appuie sur les classes de l'étape d'avant.

## 1. Diagnostic (état actuel du code)

- `MarketDataSource.BINANCE` est câblé en dur à quasi tous les call sites (`TreeAnalysisFacade` x3, `DcaCalculatorService`). Kraken/OKX existent comme clients mais ne sont jamais sélectionnés automatiquement.
- `BinanceMarketDataApiClient` / `KrakenMarketDataApiClient` / `OkxMarketDataApiClient` avalent toutes les erreurs provider dans un `catch` générique → `List.of()` + `logger.warn(...)`. Impossible de distinguer "vide légitime" (période avant listing) de "vraie erreur" (symbole invalide, panne, rate limit).
- Kraken et OKX font déjà un parsing d'erreur interne correct (`IllegalStateException` sur le champ `error`/`code` de la réponse JSON) — mais cette info est immédiatement ré-avalée par le `catch` englobant. Le signal existe, il est juste jeté au mauvais endroit.
- L'entité `Asset` (id, symbol, name, decimals) existe en base mais est orpheline : `AssetRepository` n'est utilisé que par `AssetInitializer`, aucun autre service ne s'en sert. Le routage réel des prix passe directement par le `symbol` string dans `MarketDatasetRequest`, indépendamment de cette table.
- `AssetInitializer` ne seed qu'une fois (`if (assetRepository.count() == 0)`) — pas de mécanisme d'ajout incrémental. PAXG a forcément été ajouté hors de ce chemin.
- Chaque exchange a sa propre convention de nommage de paire (Binance `BTCUSDT` concaténé, Kraken `XXBTZUSD`, OKX `BTC-USDT`). Aujourd'hui un seul symbole générique (`symbol + "USDT"`) est utilisé partout — un fallback naïf enverrait donc un symbole faux à Kraken/OKX et échouerait pour de mauvaises raisons.
- `CachingMarketDataApiClient` calcule déjà une grille de bougies attendue (`findGaps()` / `gapSize()`) mais ne l'utilise que pour piloter *quoi* demander au réseau, jamais pour valider *ce qui est revenu*.
- `DcaCalculatorService.NON_BINANCE_MAX_HORIZON_DAYS` indique que Kraken/OKX ont des limites d'historique différentes de Binance — à respecter si un fallback change de provider en cours de route.
- Ce point horizon a été creusé (cf. `etude-tick-retrieval.md` §3) : **ce n'est pas symétrique entre les 3 providers**. Binance (`GET /api/v3/klines`) n'a qu'un seul endpoint, pagination `startTime`/`endTime` sans limite de profondeur — déjà exploité par `DcaCalculatorService` (blocs de 1000). Kraken (`GET /0/public/OHLC`) ignore `since` au-delà de ~720 points (~30j), **impasse dure et définitive via REST** (seule alternative : CSV historiques Kraken hors API, hors scope). OKX (`GET /api/v5/market/candles`) est limité à une fenêtre récente **par choix d'implémentation actuel**, pas par contrainte d'exchange : OKX expose un second endpoint dédié, `GET /api/v5/market/history-candles` (curseurs `after`/`before`), jamais branché dans le projet. OKX est donc le seul des 3 où le problème est réellement réparable.

## 2. Décisions d'architecture validées

- **Pas de nouveau cache de disponibilité.** Le signal de bascule est une exception explicite au moment de l'appel, pas un healthcheck périodique séparé — `CachingMarketDataApiClient` fait déjà ce travail de cache côté data.
- **Hiérarchie d'exceptions scellée** : `MarketDataProviderException` (base) → `SymbolNotFoundException` (permanent — désactive le provider pour cet asset) / `ProviderUnavailableException` (transitoire — réseau, rate limit, 5xx). Chaque client mappe son erreur native vers l'un des deux.
- **Contrat imposé par un test, pas par la signature d'interface.** Java ne permet pas de forcer la levée d'une exception unchecked via une interface. À la place : Javadoc du contrat sur `MarketDataApiClient` + un test de contrat abstrait (`AbstractMarketDataApiClientContractTest`) que chaque `XyzMarketDataApiClientTest` doit étendre — la CI casse si un nouveau provider ne mappe pas correctement ses erreurs.
- **Mismatch bougies attendues/reçues = warn, pas un trigger de fallback.** Réutilisation de `gapSize()` vs `fetched.size()` dans `CachingMarketDataApiClient`. Un jeune listing a légitimement moins d'historique que demandé — seule une exception explicite du provider doit déclencher une bascule.
- **Table de jointure `asset_provider`** (FK vers `asset.id`), pas un simple `@ElementCollection` d'enum : `source`, `provider_symbol` (appellation propre à l'exchange — donnée déclarative, pas dérivable par règle), `priority` (0 = favori/ordre d'essai), `enabled` (kill switch manuel), `max_horizon_days` (nullable = illimité).
- **`max_horizon_days` filtré en amont, pas déclenché par exception.** Kraken tronque silencieusement (liste vide/partielle, pas d'erreur) au lieu de lever une erreur explicite — le loop de fallback doit donc écarter un candidat dont l'horizon est dépassé *avant* de l'essayer, plutôt que compter sur `SymbolNotFoundException`/`ProviderUnavailableException`. En pratique, avec le fix OKX (§3, étape OKX history-candles), seul Kraken garde une valeur non nulle (~30 jours) ; Binance et OKX (une fois `history-candles` branché) restent `null`.
- **`Asset` reste une entité pure donnée.** Aucune logique de fallback dedans — elle vit dans le service layer.
- **`AssetInitializer` évolue plutôt que d'être supprimé.** Le pattern `CommandLineRunner` + `@Order` est la convention établie du projet pour le seed (`RoleInitializer=10`, `UserInitializer=20`, etc., pas de Flyway/Liquibase). `AssetInitializer` reste donc le bon endroit, mais doit : (1) seeder aussi les lignes `asset_provider` correspondantes (mapping par exchange), et (2) passer d'un seed one-shot (`count() == 0`) à un upsert idempotent par `symbol`, pour permettre d'ajouter un asset sans dépendre d'une base vierge.
- **Deux méthodes distinctes plutôt qu'un paramètre nullable** sur `MarketDatasetEngine` :
  - `getDataset(MarketDatasetRequest request)` — contrat existant inchangé, `source` obligatoire (`Objects.requireNonNull` avec message explicite).
  - `getDatasetForAsset(String symbol, TimeFrame timeFrame, int lookBack, Instant endTime)` — nouvelle méthode, résout via `asset_provider` par priorité, boucle en catchant `SymbolNotFoundException`/`ProviderUnavailableException`, délègue à `getDataset(request)` pour chaque candidat (aucune duplication de la logique cache/gap).
- **`MarketDataProviderRegistry` reste inchangée** — simple factory `source → provider`, ne porte pas la logique de bascule.

## 3. Roadmap

| # | Étape | Statut | Dépend de | Contenu |
|---|---|---|---|---|
| 0 | Hiérarchie d'exceptions (`MarketDataProviderException`, `SymbolNotFoundException`, `ProviderUnavailableException`) | ✅ Fait | — | Package dédié (ex: `service.connector.apiclient.marketdata.exception`), Javadoc du contrat sur `MarketDataApiClient` |
| 1 | Mapping erreur → exception typée sur Binance et Kraken | ✅ Fait | #0 | Remplacer le `catch` générique par une levée typée ; garder `List.of()` uniquement pour le cas "réponse réseau OK mais 0 élément" |
| 2 | OKX — mapping erreur typée **+** routing `history-candles` (implémentation unitaire) | ✅ Fait | #0 | Vérifier d'abord précisément la profondeur réelle accessible via `/market/candles` seul (non chiffrée dans `etude-tick-retrieval.md`) ; `OkxMarketDataApiClient.getCandles(...)` route en interne vers `/market/candles` (récent) ou `/market/history-candles` (curseurs `after`/`before`) selon l'ancienneté de `since`/`until` — aucun changement du contrat `MarketDataApiClient`, tout reste interne à cette classe. Fait dans le même passage que le mapping d'erreur pour ne retoucher cette classe qu'une fois |
| 3 | Test de contrat abstrait `AbstractMarketDataApiClientContractTest` + spécialisation par provider | ✅ Fait | #1, #2 | Un test par provider prouvant le mapping "symbole invalide → `SymbolNotFoundException`" / "erreur transitoire → `ProviderUnavailableException`" ; côté OKX, couvre aussi le routing récent/historique |
| 4 | Warn mismatch bougies attendues/reçues dans `CachingMarketDataApiClient` | ✅ Fait | #1, #2 (même classes déjà retouchées) | Comparer `gapSize(gap, timeFrame)` à `fetched.size()` après chaque appel `delegate.getCandles(...)`, log warn si écart |
| 5 | Table `asset_provider` (migration + entité + repository) | ✅ Fait | — (indépendant, peut être fait en parallèle de #0-#4) | Colonnes `asset_id, source, provider_symbol, priority, enabled, max_horizon_days` ; seed initial pour les assets déjà en prod (BTC/ETH/SOL/BNB/XRP + PAXG) avec leurs vraies appellations Binance/Kraken/OKX ; `max_horizon_days` = ~30 pour Kraken, `null` pour Binance et OKX (une fois #2 fait) |
| 6 | Évolution `AssetInitializer` : seed `asset` + `asset_provider`, upsert idempotent par `symbol` | ✅ Fait | #5 | Remplace `count() == 0` ; permet d'ajouter un asset sans base vierge |
| 7 | `MarketDatasetEngine.getDatasetForAsset(...)` + refactor `getDataset()` (requireNonNull source) + filtrage `max_horizon_days` | ✅ Fait | #0, #5 | Boucle de fallback ordonnée par `priority`, écarte d'abord les candidats dont `max_horizon_days` est dépassé, puis catch des deux exceptions typées, délégation à `getDataset(request)` par candidat |
| 8 | Migration des call sites (`TreeAnalysisFacade`, `DcaCalculatorService`) vers `getDatasetForAsset` | ✅ Fait | #7 | Retrait des `MarketDataSource.BINANCE` en dur ; surface la plus large de la refacto, à faire en dernier une fois le mécanisme validé isolément |

**Pourquoi cet ordre** :
- #0 avant tout : #1, #2 et #7 catchent/lèvent ces types, rien ne peut être écrit avant.
- #1 (Binance/Kraken, mapping simple) et #2 (OKX, mapping + nouvel endpoint) sont séparées : OKX est un vrai morceau de travail à part (nouvelle route, curseurs) alors que Binance/Kraken ne font que remplacer un `catch`. Autant l'isoler comme bloc unitaire plutôt que le noyer dans une passe générique sur les 3 clients.
- #3 après #1 et #2 : le test de contrat n'a de sens qu'une fois les deux mappings (et le routing OKX) réellement faits.
- #4 après #1 et #2 : mêmes classes déjà en cours de modification, autant grouper plutôt que d'y retoucher une troisième fois.
- #5 est indépendante du reste (pure DB + entité) — peut être faite en parallèle de #0-#4.
- #6 a besoin de la table (#5) pour exister avant de pouvoir la peupler.
- #7 a besoin des exceptions typées (#0) et de la table pour connaître les candidats ordonnés + leur horizon (#5). Elle bénéficie de #2 (sinon OKX doit temporairement porter un `max_horizon_days` conservateur au lieu de `null`).
- #8 en dernier, volontairement isolée : seule étape qui touche du code métier existant (facades/DCA) plutôt que la couche technique. Autant valider tout le mécanisme via #7 avant de changer les points d'entrée réels.

## 4. Risques / points ouverts

- ~~**Profondeur exacte de `/market/candles` OKX non chiffrée.**~~ Résolu (étape 2, 2026-08-10) : `limit=300` renvoie 300 bougies, `limit=301` renvoie toujours 300 (plafond confirmé empiriquement, `instId=BTC-USDT`, `bar=1H`). Le routing ne s'appuie donc pas sur ce chiffre en dur mais sur une détection adaptative (`OkxMarketDataApiClient.mergeWithHistory` : pagine tant que la bougie la plus ancienne obtenue reste postérieure à `since`), robuste si OKX fait évoluer ce plafond.
- ~~**`NON_BINANCE_MAX_HORIZON_DAYS`** (`DcaCalculatorService`)~~ Rebranché (étape 8b, 2026-08-10) : `resolveMaxHorizonDays(symbol, effectiveSource)` lit désormais `asset_provider.max_horizon_days` en priorité, la constante ne servant plus que de repli documenté pour un asset pas encore migré (ou une ligne DB sans `max_horizon_days` renseigné) — volontairement pas supprimée (cf. étape 8b du prompt d'implémentation).

## 5. Prompts d'implémentation

- Étapes 0-3 : `docs/prompt-implementation-fallback-etapes-0-3.md`
- Étapes 4-5 : `docs/prompt-implementation-fallback-etapes-4-5.md`
- Étapes 6-7-8 : `docs/prompt-implementation-fallback-etapes-6-7-8.md`

Chaque prompt est autonome et injectable tel quel dans une nouvelle session d'implémentation, en respectant les prérequis qu'il liste en tête (étapes précédentes déjà mergées).

## 6. Suivi

Mettre à jour le statut de chaque étape dans le tableau §3 au fur et à mesure (⬜ → ✅) — c'est ce tableau qui fait foi sur l'avancement, pas la mémoire d'une conversation précédente.
