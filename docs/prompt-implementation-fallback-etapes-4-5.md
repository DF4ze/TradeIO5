# Prompt d'implémentation — Fallback multi-provider, étapes 4 et 5

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le contexte de la conversation de conception. Il couvre les **étapes 4 et 5** de la roadmap définie dans `docs/etude-fallback-multi-provider-marketdata.md` §3. Ces deux étapes sont indépendantes l'une de l'autre (aucune dépendance croisée) — elles peuvent être traitées dans l'ordre de ce prompt ou en parallèle.

Prérequis : les étapes 0 à 3 (hiérarchie d'exceptions `MarketDataProviderException`/`SymbolNotFoundException`/`ProviderUnavailableException`, mapping erreur typée sur Binance/Kraken/OKX, routing `history-candles` OKX, tests de contrat) doivent déjà être mergées — cf. `docs/prompt-implementation-fallback-etapes-0-3.md`. Vérifier qu'elles sont bien en place avant de commencer (les exceptions typées ci-dessus doivent exister dans `service/connector/apiclient/marketdata/exception/`) ; si ce n'est pas le cas, s'arrêter et le signaler plutôt que d'improviser.

Avant de commencer, lire dans l'ordre :
1. `docs/etude-fallback-multi-provider-marketdata.md` — §1 (diagnostic) et §2 (décisions), pour le contexte.
2. `service/connector/apiclient/marketdata/CachingMarketDataApiClient.java` — en particulier `getCandles(...)` (boucle sur les `gaps`, lignes ~93-100), `gapSize(...)`, `isClosed(...)`.
3. `service/connector/apiclient/marketdata/CachingMarketDataApiClientTest.java` — patron de test existant (mock du `delegate`, `CandleRepository`, `FixedDomainClock`).
4. `model/entity/currency/Asset.java` et `repository/AssetRepository.java` — l'entité à référencer en FK.
5. `model/entity/currency/Wallet.java` — patron de convention pour une entité avec FK (`@ManyToOne` + `@JoinColumn`, `@Table` + `@UniqueConstraint`, Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`).
6. `model/enumerate/market/MarketDataSource.java` — l'enum à référencer.
7. `src/main/resources/application-profile.properties.template` (ligne 14 : `spring.jpa.hibernate.ddl-auto=update`) — **le projet n'utilise ni Flyway ni Liquibase**, le schéma est généré/mis à jour automatiquement depuis les entités JPA au démarrage. Ne pas écrire de script SQL de migration.

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher à `AssetInitializer` (étape 6, hors scope), ni implémenter la boucle de fallback / `MarketDatasetEngine.getDatasetForAsset(...)` (étape 7, hors scope) — l'objectif de ce lot est uniquement : (a) détecter et signaler un mismatch de bougies, (b) poser la table `asset_provider` vide de toute logique métier.

---

## Étape 4 — Warn mismatch bougies attendues/reçues (`CachingMarketDataApiClient`)

**Contexte** : dans `getCandles(...)`, pour chaque trou (`gap`) à combler, le code calcule déjà `gapLimit = gapSize(gap, timeFrame)` (nombre de bougies attendues sur ce trou) avant d'appeler `delegate.getCandles(symbol, timeFrame, gap.since(), gap.until(), gapLimit)`. Aujourd'hui, rien ne compare `gapLimit` (attendu) au nombre de bougies réellement reçues. Un écart peut signaler un problème provider (symbole retiré, historique tronqué côté exchange, etc.) sans qu'aucune exception ne soit levée (le provider répond correctement, juste avec moins de données).

**Cas légitime à ne pas confondre avec une anomalie** : si le trou touche la borne haute demandée (`gap.until() == untilGrid`) et que cette dernière bougie n'est pas encore close au moment de l'appel (`timeFrame.addTo(untilGrid).isAfter(clock.now())`), il est normal de recevoir une bougie de moins que `gapLimit` — c'est la bougie en cours, jamais persistée par construction (cf. `isClosed(...)`, déjà présent dans la classe). Ne pas déclencher de warn dans ce cas précis. **En revanche**, un trou plus ancien qu'un jeune listing (ex: PAXG interrogé avant sa date de listing sur l'exchange) doit bien déclencher le warn — cette étape ne cherche pas à distinguer "jeune listing" de "vraie anomalie" (impossible à faire de façon fiable ici), elle se contente de signaler un écart quantitatif ; l'appréciation du "pourquoi" reste à l'humain qui lit les logs.

**À faire** :

1. Extraire le calcul de la borne attendue ajustée dans une méthode statique testable, par exemple :
   ```java
   static int expectedCandleCount(Range gap, TimeFrame timeFrame, Instant untilGrid, Instant now) {
       int expected = gapSize(gap, timeFrame);
       if (gap.until().equals(untilGrid) && timeFrame.addTo(untilGrid).isAfter(now)) {
           expected -= 1;
       }
       return expected;
   }
   ```
   (squelette indicatif — adapter si une meilleure décomposition apparaît en écrivant le code, mais garder le principe : méthode statique, sans effet de bord, testable sans mock.)
2. Dans la boucle `for (Range gap : gaps)` de `getCandles(...)`, après l'appel `List<MarketData> fetched = delegate.getCandles(...)`, comparer `fetched.size()` à `expectedCandleCount(gap, timeFrame, untilGrid, clock.now())`. Si `fetched.size()` est strictement inférieur, logguer un `log.warn(...)` explicite : symbole, timeFrame, source, plage du trou, nombre attendu, nombre reçu. Ne rien faire (pas d'exception, pas de comportement modifié) au-delà du log — cette étape est un signal d'observabilité, pas un déclencheur de fallback (ce sera le rôle de `max_horizon_days` à l'étape 7, sur un mécanisme différent).
3. Ne pas comparer si `fetched.size()` est supérieur ou égal à l'attendu (rien à signaler).

**Tests attendus** (`CachingMarketDataApiClientTest`, ou nouvelle classe dédiée si plus lisible) :
- `expectedCandleCount(...)` retourne la valeur brute de `gapSize(...)` quand le trou ne touche pas la borne haute "en cours".
- `expectedCandleCount(...)` retourne `gapSize(...) - 1` quand le trou touche `untilGrid` et que cette bougie n'est pas close (utiliser `FixedDomainClock` comme le reste de la classe de test pour contrôler "maintenant").
- Un scénario d'intégration (comme les tests `getCandles_gapInMiddle_onlyFetchesTheGap` existants) où le `delegate` mocké renvoie moins de bougies que `gapLimit` sur un trou qui ne touche pas la borne haute : vérifier que le comportement fonctionnel ne change pas (les bougies reçues sont quand même fusionnées/retournées) — le but est de vérifier qu'aucune régression n'est introduite, la présence du warn en tant que telle n'a pas besoin d'être assertée via capture de logs (pas de pattern de test de ce type existant dans le projet ; ne pas en introduire un pour ce seul besoin sauf si ça reste trivial).

---

## Étape 5 — Table `asset_provider` (entité + repository)

**Contexte** : `Asset` (`model/entity/currency/Asset.java`) existe déjà mais est orpheline (aucun autre service ne la consomme aujourd'hui). Cette étape crée la table de jointure qui portera, pour chaque couple (asset, exchange), l'appellation propre à cet exchange, l'ordre de préférence et la limite d'historique connue — sans encore rien brancher dessus (le branchement dans `MarketDatasetEngine` est l'étape 7).

**À faire** :

1. Nouvelle entité `model/entity/currency/AssetProvider.java`, sur le patron de `Wallet.java` (Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`, `@Table` + `@UniqueConstraint`) :
   - `id` (`Long`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`).
   - `asset` (`@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "asset_id", nullable = false)`, type `Asset`).
   - `source` (`@Enumerated(EnumType.STRING) @Column(nullable = false, length = 50)`, type `MarketDataSource`).
   - `providerSymbol` (`@Column(name = "provider_symbol", nullable = false, length = 50)`, type `String`) — l'appellation propre à l'exchange (ex: `BTCUSDT` chez Binance, `XXBTZUSD` chez Kraken, `BTC-USDT` chez OKX).
   - `priority` (`@Column(nullable = false)`, type `int`) — 0 = favori/premier essayé.
   - `enabled` (`@Column(nullable = false)`, type `boolean`, `@Builder.Default` à `true`) — kill switch manuel.
   - `maxHorizonDays` (`@Column(name = "max_horizon_days")`, type `Integer`, nullable) — `null` = illimité (Binance, OKX une fois `history-candles` branché) ; valeur non nulle pour Kraken (~30, valeur exacte à reprendre de `DcaCalculatorService.NON_BINANCE_MAX_HORIZON_DAYS` si toujours présente à ce stade).
   - `@Table(name = "asset_provider", uniqueConstraints = { @UniqueConstraint(name = "uk_asset_provider_asset_source", columnNames = {"asset_id", "source"}) })` — un seul provider par couple (asset, source).
2. Nouveau repository `repository/AssetProviderRepository.java` (même package que `AssetRepository`, `extends JpaRepository<AssetProvider, Long>`), avec au minimum la méthode dérivée `List<AssetProvider> findByAsset_SymbolOrderByPriorityAsc(String symbol)` (navigation Spring Data JPA via `_` vers `Asset.symbol`) — c'est celle dont l'étape 7 aura besoin pour construire la liste ordonnée de candidats. Ne pas ajouter d'autres méthodes non utilisées pour l'instant (`enabled`/filtrage à ajouter au moment où l'étape 7 en aura réellement besoin, pas avant).
3. Ne rien seeder ici — pas de ligne insérée, pas de modification d'`AssetInitializer` (étape 6, session ultérieure). Le but de cette étape est uniquement que la table existe et soit interrogeable, schéma généré automatiquement par Hibernate (`ddl-auto=update`) au prochain démarrage.

**Tests attendus** (nouveau fichier `AssetProviderRepositoryTest.java`, `@DataJpaTest` — **premier test de ce type dans le projet**, vérifier que la configuration Spring Boot Test standard suffit avec H2 déjà en dépendance de test, aucune configuration supplémentaire ne devrait être nécessaire) :
- Persister un `Asset` puis plusieurs `AssetProvider` liés (priorités différentes), vérifier que `findByAsset_SymbolOrderByPriorityAsc(symbol)` renvoie bien la liste triée par `priority` croissante.
- Vérifier que la contrainte unique `(asset_id, source)` est bien appliquée : tenter de persister deux `AssetProvider` pour le même `(asset, source)` doit lever une exception de contrainte (`DataIntegrityViolationException` ou équivalent selon le comportement H2/Hibernate observé).

---

## À la fin : lancer les tests via la Gateway

Une fois l'implémentation et les tests écrits, compiler et exécuter la suite de tests complète du projet via l'opération CI/CD `test:tradeio-5` du gateway SSH (`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`, ou lister les opérations disponibles avec `listOperations` si besoin de confirmer le nom exact). Ne pas lancer `mvn` directement dans un sandbox local : le projet ne compile/teste que via cette gateway (pas de Maven/réseau disponible en sandbox).

Rapporter : le résultat global (succès/échec), le nombre de tests exécutés, et le détail de tout test en échec (classe + message). Porter une attention particulière au premier démarrage après l'ajout de l'entité `AssetProvider` : si `ddl-auto=update` échoue à créer la table (conflit de nom de colonne, contrainte déjà existante, etc.), le signaler explicitement dans le rapport — ce n'est pas un échec de test au sens strict mais bloquerait toute la suite si le contexte Spring ne démarre pas.
