# Prompt d'implémentation — Fallback multi-provider, étapes 6, 7 et 8 (finalisation)

Ce prompt est autonome mais couvre la partie la plus invasive de la roadmap `docs/etudes/etude-fallback-multi-provider-marketdata.md` §3 — **les étapes 6, 7 et 8, dans cet ordre** (6 avant 7 n'est pas une dépendance dure, mais peupler `asset_provider` avant d'écrire la boucle de fallback permet de la tester avec de vraies données plutôt qu'à vide ; 8 dépend strictement de 7).

**Prérequis obligatoires**, à vérifier avant de commencer (s'arrêter et le signaler si l'un manque, ne pas improviser) :
- Étapes 0-3 mergées : `MarketDataProviderException`/`SymbolNotFoundException`/`ProviderUnavailableException` existent dans `service/connector/apiclient/marketdata/exception/`, les 3 clients (Binance/Kraken/OKX) les lèvent au lieu d'avaler en `List.of()`.
- Étape 5 mergée : entité `AssetProvider` (`model/entity/currency/AssetProvider.java`) et `AssetProviderRepository` existent, avec au moins `findByAsset_SymbolOrderByPriorityAsc(String symbol)`.

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-fallback-multi-provider-marketdata.md` — en entier, c'est le document de référence de toute cette refacto.
2. `model/entity/currency/AssetProvider.java`, `repository/AssetProviderRepository.java`, `model/entity/currency/Asset.java`, `repository/AssetRepository.java`.
3. `configuration/initializer/AssetInitializer.java` (à faire évoluer, étape 6).
4. `service/market/dataset/MarketDatasetEngine.java` en entier (à faire évoluer, étape 7).
5. `model/dto/market/MarketDatasetRequest.java` (record à 6 champs : `symbol, timeFrame, lookBack, endTime, source, providerParam`).
6. `service/market/provider/MarketDataProviderRegistry.java` — noter que `getProvider(MarketDataSource, Object param)` attend, pour `BINANCE`/`KRAKEN`/`OKX`, un `param` castable en `MarketDataApiClient` (cf. les factories `param -> new BinanceMarketDataProvider((MarketDataApiClient) param)` etc.) — **`providerParam` n'est donc pas juste une info technique optionnelle, c'est l'instance de client à utiliser**, à résoudre correctement pour chaque candidat de fallback.
7. `service/dca/DcaCalculatorService.java` en entier — patron déjà existant d'injection des 3 clients par source (`clientsBySource`, constructeur avec 3 `@Qualifier`), à reproduire dans `MarketDatasetEngine`.
8. `service/tree/api/mcp/TreeAnalysisFacade.java` en entier — en particulier `getIndicator(6 args)` (ligne ~135), `evaluateStrategy(6 args)` (ligne ~207), `getOpinion(5 args)` (ligne ~241), `buildMarketContext(...)` (ligne ~314), `fetchDataset(...)` (ligne ~339). Les 3 points d'entrée "réels" (utilisés par les tools MCP, 4/4/3 paramètres) délèguent aujourd'hui à ces méthodes avec `MarketDataSource.BINANCE, binanceMarketDataApiClient` codés en dur (lignes 140, 203, 237) — **les surcharges à source explicite existent pour les tests (`MarketDataSource.MEMORY`) et ne doivent pas être touchées.**
9. `configuration/MarketDataCachingConfig.java` — pour connaître les noms exacts des beans qualifiés (`cachingBinanceMarketDataApiClient`, `cachingKrakenMarketDataApiClient`, `cachingOkxMarketDataApiClient`).

---

## Étape 6 — Évolution d'`AssetInitializer`

**Contexte** : aujourd'hui, seed one-shot (`if (assetRepository.count() == 0)`) de 7 `Asset` sans aucune donnée `asset_provider`. PAXG (déjà utilisé en production, cf. origine de toute cette étude) n'y figure même pas — il a dû être créé hors de ce chemin.

**À faire** :

1. Ajouter `Optional<Asset> findBySymbol(String symbol)` à `AssetRepository` (méthode dérivée Spring Data, n'existe pas aujourd'hui).
2. Ajouter à `AssetProviderRepository` une méthode d'existence ciblée, ex: `Optional<AssetProvider> findByAsset_SymbolAndSource(String symbol, MarketDataSource source)`.
3. Remplacer la logique one-shot d'`AssetInitializer` par un **upsert idempotent**, rejouable à chaque démarrage sans dupliquer ni écraser un réglage opérationnel :
   - Pour chaque `Asset` de la liste de seed : `findBySymbol(...)`, créer si absent, ne rien modifier si déjà présent (le `symbol`/`name`/`decimals` d'un actif ne change pas dynamiquement).
   - Pour chaque couple `(asset, provider)` de la liste de seed : `findByAsset_SymbolAndSource(...)`. Si absent, créer (avec `enabled = true` par défaut). **Si déjà présent, rafraîchir `providerSymbol`/`priority`/`maxHorizonDays` depuis la définition de seed, mais ne jamais toucher `enabled`** — ce champ est un kill switch opérationnel qui a pu être basculé manuellement (ex: après un incident), un redéploiement ne doit pas l'annuler silencieusement.
4. Liste de seed à couvrir : les 7 assets déjà présents (`BTC, ETH, SOL, BNB, XRP, USDT, USDC`) **plus `PAXG`** (jamais seedé jusqu'ici, mais déjà utilisé en prod). Pour chacun, seeder les lignes `asset_provider` pour `BINANCE` (toujours, `maxHorizonDays = null`), `KRAKEN` (`maxHorizonDays` = la valeur reprise de `DcaCalculatorService.NON_BINANCE_MAX_HORIZON_DAYS` si toujours présente à ce stade, sinon ~30), et `OKX` (`maxHorizonDays = null` si l'étape 2 de `docs/prompts/prompt-implementation-fallback-etapes-0-3.md` — routing `history-candles` — a bien été mergée, sinon valeur conservatrice temporaire).
5. **Ne pas deviner les `provider_symbol` par exchange.** Seuls `BTC` (Kraken `XXBTZUSD`, OKX `BTC-USDT`) sont déjà confirmés par les fixtures de test existantes (`KrakenMarketDataApiClientTest`, `OkxMarketDataApiClientTest`). Pour tous les autres assets (`ETH, SOL, BNB, XRP, USDT, USDC, PAXG`), vérifier empiriquement le code de paire réel de chaque exchange avant d'écrire la valeur de seed (appel direct aux endpoints publics `GET /0/public/AssetPairs` pour Kraken, `GET /api/v5/public/instruments?instType=SPOT` pour OKX, ou tout simplement un essai direct sur l'endpoint OHLC/candles avec le symbole pressenti). Documenter dans un commentaire au-dessus de la liste de seed la source de vérification utilisée. Pour Binance, la convention `symbol + "USDT"` déjà en place dans le reste du code est fiable (confirmée pour `PAXGUSDT`, cf. logs de prod).
6. Décimales : vérifier plutôt que supposer (`decimals` pour `PAXG` notamment) — PAX Gold est un token ERC-20, la valeur usuelle est 18 mais à confirmer avant de l'écrire en dur.

**Tests attendus** : test unitaire/`@DataJpaTest` vérifiant que (a) relancer le runner deux fois de suite ne duplique rien (nombre de lignes stable), (b) modifier manuellement `enabled = false` sur une ligne puis relancer le runner ne le remet pas à `true`, (c) modifier `priority` en base puis relancer le runner la resynchronise sur la valeur de seed.

---

## Étape 7 — `MarketDatasetEngine.getDatasetForAsset(...)` + refactor `getDataset()`

### 7a. `getDataset(request)` — contrat inchangé, source obligatoire

Première ligne de la méthode : `Objects.requireNonNull(request.source(), "source obligatoire ici ; utiliser getDatasetForAsset(symbol, ...) pour la résolution automatique");`. Vérifié : aucun appelant actuel (`TreeAnalysisFacade.fetchDataset(...)`, seul appelant en dehors de la classe elle-même) ne passe de `source` nulle — grep `new MarketDatasetRequest(` dans `src/test/` également avant de merger, pour confirmer qu'aucun test ne dépend d'un `source` nul (peu probable mais à vérifier plutôt que supposer).

### 7b. Nouvelle exception `NoProviderAvailableException`

Dans `service/market/dataset/` (co-localisée avec `MarketDatasetEngine`, ce n'est pas une exception "par provider" comme celles de l'étape 0 mais une exception d'orchestration) : `extends RuntimeException`, porte `String symbol` et la dernière cause rencontrée (nullable si aucun candidat n'était même éligible). Deux cas d'usage à distinguer dans le message : "aucun provider configuré pour cet asset" (liste `asset_provider` vide) vs "tous les providers éligibles ont échoué" (liste non vide mais chaque tentative a levé une exception, ou tous filtrés par horizon).

### 7c. Résolution du `providerParam` par source

`MarketDatasetEngine` a aujourd'hui `@RequiredArgsConstructor` (Lombok) avec 4 dépendances (`cache, manager, providerRegistry, timeFrameConverter`). Remplacer par un constructeur explicite qui garde ces 4 champs **et** injecte, sur le patron exact de `DcaCalculatorService` (constructeur + 3 `@Qualifier`), les 3 clients web :

```java
private final Map<MarketDataSource, MarketDataApiClient> webClientsBySource;

public MarketDatasetEngine(
        MarketDatasetCache cache,
        MarketDatasetManager manager,
        MarketDataProviderRegistry providerRegistry,
        TimeFrameConverter timeFrameConverter,
        @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceClient,
        @Qualifier("cachingKrakenMarketDataApiClient") MarketDataApiClient krakenClient,
        @Qualifier("cachingOkxMarketDataApiClient") MarketDataApiClient okxClient
) {
    this.cache = cache;
    this.manager = manager;
    this.providerRegistry = providerRegistry;
    this.timeFrameConverter = timeFrameConverter;
    this.webClientsBySource = new EnumMap<>(MarketDataSource.class);
    this.webClientsBySource.put(MarketDataSource.BINANCE, binanceClient);
    this.webClientsBySource.put(MarketDataSource.KRAKEN, krakenClient);
    this.webClientsBySource.put(MarketDataSource.OKX, okxClient);
}
```

(squelette indicatif — reprendre le nommage réel des champs déjà présents dans la classe.)

### 7d. La méthode elle-même

```java
private final AssetProviderRepository assetProviderRepository; // nouvelle dépendance, à ajouter au constructeur ci-dessus

public MarketDataset getDatasetForAsset(String symbol, TimeFrame timeFrame, int lookBack, Instant endTime) {
    List<AssetProvider> candidates = assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc(symbol)
            .stream()
            .filter(AssetProvider::isEnabled)
            .toList();

    if (candidates.isEmpty()) {
        throw new NoProviderAvailableException(symbol, null); // aucun provider configuré
    }

    int requiredCount = lookBack == 0 ? DEFAULT_LIMIT : lookBack;
    long requestedSpanDays = timeFrame.getUnit().getDuration()
            .multipliedBy(timeFrame.getAmount())
            .multipliedBy(requiredCount)
            .toDays();

    List<AssetProvider> eligible = candidates.stream()
            .filter(c -> c.getMaxHorizonDays() == null || requestedSpanDays <= c.getMaxHorizonDays())
            .toList();

    if (eligible.isEmpty()) {
        throw new NoProviderAvailableException(symbol, null); // aucun candidat ne couvre l'horizon demandé
    }

    MarketDataProviderException lastError = null;
    for (AssetProvider candidate : eligible) {
        MarketDataApiClient client = webClientsBySource.get(candidate.getSource());
        if (client == null) {
            log.warn("Aucun MarketDataApiClient injecté pour la source {} (asset_provider id={}), candidat ignoré.",
                    candidate.getSource(), candidate.getId());
            continue;
        }

        MarketDatasetRequest request = new MarketDatasetRequest(
                candidate.getProviderSymbol(), timeFrame, lookBack, endTime,
                candidate.getSource(), client
        );
        try {
            return getDataset(request);
        } catch (SymbolNotFoundException | ProviderUnavailableException e) {
            log.warn("Provider {} indisponible pour {} ({}) : {} — bascule sur le candidat suivant.",
                    candidate.getSource(), symbol, candidate.getProviderSymbol(), e.getMessage());
            lastError = e;
        }
    }
    throw new NoProviderAvailableException(symbol, lastError);
}
```

(squelette indicatif — adapter aux noms réels des champs/méthodes écrits aux étapes précédentes, notamment le nom exact du getter `isEnabled()`/`getEnabled()` selon la convention Lombok choisie à l'étape 5.)

**Tests attendus** (`MarketDatasetEngineTest`, étendre le fichier existant s'il y en a un, sinon nouveau) :
- Candidat favori répond correctement → aucun appel au candidat suivant.
- Candidat favori lève `ProviderUnavailableException` → bascule sur le candidat priorité 1, avec un warn logué (vérifier au minimum que `getDataset` est bien appelé une seconde fois avec le bon candidat, pas la présence du log en tant que telle).
- Candidat favori lève `SymbolNotFoundException` → même bascule.
- Tous les candidats échouent → `NoProviderAvailableException` levée, portant la dernière erreur rencontrée.
- Aucune ligne `asset_provider` pour le symbole → `NoProviderAvailableException` immédiate, aucun appel réseau.
- `lookBack`/`timeFrame` demandant un horizon > `maxHorizonDays` du favori (ex: Kraken priorité 0 avec 30 jours, requête de 90 jours) → le favori est écarté sans même être appelé, bascule directe sur le candidat suivant éligible.
- `getDataset(request)` avec `source == null` → `NullPointerException` (ou l'exception choisie), pour verrouiller le contrat de 7a.

---

## Étape 8 — Migration des call sites

### 8a. `TreeAnalysisFacade`

Objectif : les 3 points d'entrée "réels" (`getIndicator(5 args, avec stringParams)`, `evaluateStrategy(4 args)`, `getOpinion(3 args)`) ne doivent plus construire `MarketDataSource.BINANCE, binanceMarketDataApiClient` — ils doivent passer par `marketDatasetEngine.getDatasetForAsset(...)`. **Les surcharges à source explicite (celles avec `MarketDataSource source, Object providerParam` en paramètres, utilisées par les tests avec `MEMORY`) ne changent pas.**

1. Ajouter `fetchDatasetForAsset(String symbol, TimeFrame timeFrame, int lookBack, Instant now)`, miroir de `fetchDataset(...)` (ligne 339) mais appelant `marketDatasetEngine.getDatasetForAsset(symbol, timeFrame, lookBack, now)`. Catcher `NoProviderAvailableException` en plus de `IllegalStateException`/`IllegalArgumentException` déjà catchées, même traitement (wrap en `TreeAnalysisException` avec message explicite).
2. Ajouter `buildMarketContextForAsset(String symbol, Map<TimeFrame, Integer> requiredCandles, Instant now)`, miroir de `buildMarketContext(...)` (ligne 314) mais appelant `fetchDatasetForAsset(...)` au lieu de `fetchDataset(..., source, providerParam)` dans la boucle sur `timeFrames.keySet()`.
3. `getIndicator(symbol, timeFrame, type, numericParams, stringParams)` (ligne 135, corps ligne 152+) : remplacer l'appel `fetchDataset(symbol, timeFrame, MarketDatasetEngine.DEFAULT_LIMIT, now, source, providerParam)` (ligne 188) par `fetchDatasetForAsset(symbol, timeFrame, MarketDatasetEngine.DEFAULT_LIMIT, now)` **uniquement dans la variante appelée par le point d'entrée réel** — la méthode à 7 paramètres (avec `source`/`providerParam` explicites, ligne 152) reste inchangée pour les tests. Concrètement, dupliquer le corps entre une version "résolue" et une version "source explicite" revient à dupliquer ~40 lignes ; factoriser en extrayant le corps commun dans une méthode privée qui reçoit directement le `MarketDataset` déjà résolu, avec deux fines méthodes publiques qui ne diffèrent que par la façon dont elles obtiennent ce dataset. Éviter la duplication de logique métier.
4. Même traitement pour `evaluateStrategy(4 args, ligne 200)` → `buildMarketContextForAsset(...)` au lieu de `buildMarketContext(symbol, requiredCandles, MarketDataSource.BINANCE, binanceMarketDataApiClient, now)`, et `getOpinion(3 args, ligne 236)` de même.
5. Une fois ces 3 points d'entrée migrés, vérifier si le champ `binanceMarketDataApiClient` (et le paramètre `@Qualifier("cachingBinanceMarketDataApiClient")` du constructeur) est encore utilisé ailleurs dans la classe. S'il ne l'est plus (attendu), le supprimer proprement (champ, paramètre constructeur, import `Qualifier` si plus utilisé) plutôt que de laisser un câblage mort.
6. Mettre à jour le Javadoc de la classe (lignes 64-70) qui documente explicitement aujourd'hui "utilise `MarketDataSource#BINANCE` comme source réelle" — ce n'est plus vrai après cette migration, à réécrire pour décrire la résolution via `asset_provider`.

**Tests attendus** : les tests existants de `TreeAnalysisFacade` utilisant la surcharge à source explicite (`MEMORY`) ne doivent pas changer de comportement — les faire tourner pour confirmer la non-régression. Ajouter un test (avec `AssetProviderRepository` mocké ou une base de test peuplée) vérifiant que le point d'entrée réel (`getIndicator` 5 args) résout bien via `getDatasetForAsset` — au minimum un test qui vérifie que `MarketDatasetEngine.getDatasetForAsset(...)` est appelé avec les bons arguments (Mockito `verify`), pas nécessairement un test de bout en bout avec un vrai appel réseau.

### 8b. `DcaCalculatorService`

**Ne pas reproduire la boucle de fallback complète ici** — ce service bypasse déjà volontairement `MarketDatasetEngine` (cf. javadoc de la classe) avec sa propre pagination par blocs ; reconstruire un mécanisme de fallback parallèle serait hors scope et risquerait de changer silencieusement la source de prix en cours de calcul DCA (une moyenne DCA doit rester cohérente sur une seule source du début à la fin, pas basculer d'exchange à mi-chemin). Se limiter à retirer le `BINANCE` codé en dur :

1. Injecter `AssetProviderRepository` dans le constructeur.
2. Ligne 76, `MarketDataSource effectiveSource = source != null ? source : MarketDataSource.BINANCE;` → quand `source == null`, résoudre via `assetProviderRepository.findByAsset_SymbolOrderByPriorityAsc(symbol)`, prendre le premier candidat `enabled`, et son `getSource()`. **Si aucune ligne `asset_provider` n'existe pour ce symbole (asset pas encore migré), conserver `MarketDataSource.BINANCE` comme repli** — ne pas faire échouer un DCA qui fonctionnait hier simplement parce que l'étape 6 n'a pas encore couvert cet asset.
3. Lignes 96-101 (`NON_BINANCE_MAX_HORIZON_DAYS`) → chercher la ligne `asset_provider` correspondant à `(symbol, effectiveSource)`. Si trouvée et `maxHorizonDays` non nul, l'utiliser à la place de la constante. **Si aucune ligne ne correspond et `effectiveSource != BINANCE`, conserver l'ancien comportement (limite conservatrice `NON_BINANCE_MAX_HORIZON_DAYS` telle quelle)** plutôt que de désactiver silencieusement le garde-fou tant que `asset_provider` n'est pas exhaustif. Une fois cette bascule faite, la constante `NON_BINANCE_MAX_HORIZON_DAYS` reste utilisée comme valeur de repli documentée, elle n'est pas supprimée dans ce lot.

**Tests attendus** (`DcaCalculatorServiceTest`, étendre l'existant) : `source == null` + asset avec favori Kraken en base → `effectiveSource` résolu à `KRAKEN` sans erreur. `source == null` + asset absent d'`asset_provider` → repli sur `BINANCE`, comportement identique à avant cette étape. Horizon dépassé avec une valeur `maxHorizonDays` venant de la DB différente de la constante → l'exception `DcaException` utilise bien la valeur DB dans son message, pas la constante.

---

## À la fin : lancer les tests via la Gateway

Une fois l'implémentation et les tests écrits, compiler et exécuter la suite de tests complète du projet via l'opération CI/CD `test:tradeio-5` du gateway SSH (`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`, ou lister les opérations disponibles avec `listOperations` si besoin de confirmer le nom exact). Ne pas lancer `mvn` directement dans un sandbox local : le projet ne compile/teste que via cette gateway (pas de Maven/réseau disponible en sandbox).

Rapporter : le résultat global (succès/échec), le nombre de tests exécutés, le détail de tout test en échec (classe + message). Cette session ferme la roadmap complète (`docs/etudes/etude-fallback-multi-provider-marketdata.md` §3, étapes 0 à 8) — si tout passe, mettre à jour le statut de chaque étape (⬜ → ✅) dans le tableau §3 du document avant de conclure, c'est ce tableau qui fait foi sur l'avancement global.
