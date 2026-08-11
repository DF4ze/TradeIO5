# Prompt d'implémentation — Décision, Palier 1 (3 correctifs critiques)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre le **Palier 1** défini avec Clem le 2026-08-10 :
les 3 TODO critiques déjà identifiés dans `docs/suivi/point-avancement-2026-08-10.md` §5 (items #1,
#2, #3), repris et détaillés dans `docs/etudes/etude-mecanique-decision-dca-intelligent.md` (§2.2 et
§12). Ces trois correctifs sont **indépendants entre eux** — traiter dans l'ordre de ce prompt ou en
parallèle.

**Ce que ce lot n'est PAS** : ce n'est pas le sizing réel (§4 de l'étude), pas le branchement Spring
de `DecisionEngine`/`DefaultScenarioEngine` dans l'application qui tourne (§12 point 1 — ces classes
restent, après ce lot, toujours de simples POJO non instanciés hors tests, **volontairement**), pas
la persistance de l'état (§12 point 2), pas la correction du bug de cache cross-utilisateur
(§12 point 3, `BalanceCacheManager` — hors scope, réservé au Palier 2 socle multi-user). Ce lot rend
la logique **interne** de Scenario/Decision cohérente et testable en isolation, rien de plus.

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-mecanique-decision-dca-intelligent.md` — le "Principe directeur" en tête de
   doc, §2.1-2.2 (analyse de l'existant) et §12 points 1 (contexte : ces correctifs ne rendent PAS
   la chaîne exécutable en prod, juste cohérente en isolation).
2. `service/tree/decision/DecisionEngine.java` — en particulier `createDecision(...)`,
   `mapToCandidate(...)`, `mapAction(...)`.
3. `service/tree/scenario/DefaultScenarioEngine.java` — en particulier `collectActionIntents(...)`
   et `cleanup(...)`.
4. `service/tree/scenario/DefaultMarketScenario.java` — en particulier `proposeIntent(...)`.
5. `model/enumerate/tree/decision/DecisionType.java`, `ExecutionAction.java`,
   `model/enumerate/tree/MarketIntentAction.java`, `model/enumerate/tree/scenario/ScenarioStatus.java`
   — les enums manipulés par ce lot.
6. Tests existants à prendre comme patron : `service/tree/decision/DecisionTest.java` (construction
   d'un `Decision`/`EventBus` réel, pas de mock lourd), `service/tree/scenario/
   DefaultScenarioEngineUnitTest.java` (accès package-private au champ `scenarios`, `FixedDomainClock`,
   `EventBus` + `InMemoryEventStore` réels pour capturer les événements publiés), `service/tree/
   scenario/DefaultMarketScenarioTest.java` (patron `opResult(SignalType, confidence)` /
   `context(clock, owner)` pour amener un scénario à `VALIDATED`).
7. `CachingMarketDataApiClient.java` méthode `expectedCandleCount(...)` (voir
   `docs/prompts/prompt-implementation-fallback-etapes-4-5.md` étape 4) — exemple déjà en place dans
   ce projet du pattern "extraire une méthode statique/package-private testable" à réutiliser ici
   pour `mapDecisionType(...)` (étape 1 ci-dessous).

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher à `ProviderApiService`,
`BalanceCacheManager`, ni ajouter d'annotation Spring sur `DecisionEngine`/`DefaultScenarioEngine`
— hors scope de ce lot.

---

## Étape 1 — `DecisionType` reflète l'action réelle (au lieu d'être toujours `EXIT`)

**Contexte** : le code hardcode `DecisionType.EXIT` à **deux endroits distincts**, pas un seul —
piège facile à ne corriger qu'à moitié :
- `mapToCandidate(...)` construit un `DecisionCandidate` avec `DecisionType.EXIT` littéral (`// TODO`
  déjà présent dans le code).
- `createDecision(...)` construit le `DecisionSnapshot` avec **à nouveau** `DecisionType.EXIT`
  littéral — en ignorant complètement `candidate.type()`, qui existe pourtant déjà comme champ du
  `DecisionCandidate` reçu en paramètre. Corriger uniquement `mapToCandidate(...)` ne suffit donc
  pas : `createDecision(...)` écraserait quand même la valeur avec `EXIT`.

**À faire** :

1. Extraire une méthode dédiée, testable indépendamment (même patron que `expectedCandleCount(...)`
   cité en lecture préalable) :
   ```java
   // package-private, testable directement depuis DecisionEngineTest sans passer par tout le flux événementiel
   static DecisionType mapDecisionType(ExecutionAction action) {
       return switch (action) {
           case BUY -> DecisionType.ENTER;
           case SELL, EXIT -> DecisionType.EXIT;
           case NO_OP -> DecisionType.EXIT; // ne devrait pas être atteint en pratique (proposeIntent n'émet jamais d'intent pour un signal NEUTRAL/HOLD) ; valeur de repli neutre, pas une vraie sémantique
       };
   }
   ```
   `REBALANCE`/`STOP` sont **volontairement hors scope** de ce mapping minimal : les distinguer d'un
   simple ENTER/EXIT demanderait de connaître l'état du portefeuille (position déjà ouverte ou non),
   qui n'existe pas encore dans ce contexte (cf. étude §12 point 7, futur composant Sizing). Ne pas
   essayer de les déduire ici.
2. Dans `mapToCandidate(...)` : calculer `ExecutionAction action = mapAction(intent);` une seule
   fois, puis construire le `DecisionCandidate` avec `mapDecisionType(action)` comme `type` et
   `action` comme `action` (au lieu d'appeler `mapAction(intent)` une seconde fois séparément comme
   aujourd'hui).
3. Dans `createDecision(...)` : remplacer le `DecisionType.EXIT` littéral par `candidate.type()`.

**Tests attendus** (nouveau fichier `service/tree/decision/DecisionEngineTest.java` — **premier test
dédié à cette classe**, aucun n'existe aujourd'hui ; suivre le patron de `DecisionTest.java` pour la
construction d'un `EventBus` réel plutôt que mocké) :
- `mapDecisionType(ExecutionAction.BUY)` retourne `DecisionType.ENTER`.
- `mapDecisionType(ExecutionAction.SELL)` retourne `DecisionType.EXIT`.
- `createDecision(candidate)` avec un `DecisionCandidate` dont `type() == DecisionType.ENTER` produit
  un `Decision` dont `getSnapshot().type() == DecisionType.ENTER` (couvre spécifiquement le bug du
  `candidate.type()` ignoré — construire ce test *avant* de corriger le code et vérifier qu'il échoue
  bien sur le code actuel, pour être sûr de couvrir le bon bug).
- Un test bout-en-bout léger (optionnel mais recommandé) : publier un `ScenarioEvent(ACTION_PROPOSED,
  IntentCause(...))` avec une action `BUY` sur un `EventBus` réel, avec un stub/mock de
  `ScenarioEngine` dont `getActiveScenarios(...)` renvoie un unique scénario dont `proposeIntent(...)`
  renvoie la même action `BUY` (condition d'unanimité satisfaite) ; s'abonner à `DecisionEvent` sur ce
  même bus et vérifier que le `Decision` capturé a bien `DecisionType.ENTER`.

---

## Étape 2 — Dédup des `ActionIntent` déjà proposées

**Contexte** : `DefaultScenarioEngine.collectActionIntents(...)` propose un nouvel `ActionIntent` à
chaque appel pour tout scénario `VALIDATED`+stable, sans mémoire de ce qui a déjà été proposé. Tant
qu'un scénario reste validé (ce qui peut durer, par nature), il repropose le même intent à chaque
cycle — TODO déjà signalé dans le code (`// TODO : Warning...`).

**Règle retenue** : proposer un intent **une seule fois par épisode de validation continue**. Un
scénario qui quitte `VALIDATED` (ou perd sa stabilité) puis y revient plus tard constitue un nouvel
épisode, et peut donc reproposer un intent.

**À faire** :

1. Ajouter un champ dans `DefaultScenarioEngine` : `private final Set<String> proposedScenarioIds =
   ConcurrentHashMap.newKeySet();` (cohérence avec `scenarios` déjà en `ConcurrentHashMap`, l'engine
   étant déjà pensé thread-safe).
2. Dans `collectActionIntents(...)`, pour chaque scénario visible :
   - Si `marketScenario.getState().getStatus() != ScenarioStatus.VALIDATED` ou
     `!marketScenario.getState().isStable()` : retirer son id de `proposedScenarioIds` (reset — libère
     un futur nouvel épisode) puis `continue`.
   - Sinon, si `proposedScenarioIds.contains(marketScenario.getId())` : déjà proposé pour cet épisode,
     `continue` sans rappeler `proposeIntent(...)`.
   - Sinon, comportement actuel (appel `proposeIntent(...)`, publication de l'event, ajout à la
     liste retournée) **plus** `proposedScenarioIds.add(marketScenario.getId())` si un intent a
     effectivement été proposé.
3. Dans `cleanup(...)` : quand un scénario est retiré de `scenarios` (expiré/invalidé), retirer aussi
   son id de `proposedScenarioIds` — éviter une fuite mémoire silencieuse sur des scénarios qui
   n'existent plus.

**Tests attendus** (`DefaultScenarioEngineUnitTest`, même patron `@BeforeEach`/`FixedDomainClock` que
l'existant) :
- Amener un scénario à `VALIDATED`+stable (réutiliser le patron `opResult(...)`/`context(...)` de
  `DefaultMarketScenarioTest` ou l'équivalent déjà utilisé dans cette classe), appeler
  `engine.collectActionIntents(owner, now)` deux fois de suite sans nouvel événement d'Opinion entre
  les deux : la première liste contient l'intent, la seconde est vide.
- Faire évoluer le même scénario hors de `VALIDATED` (ex: opinion fortement opposée) puis le
  revalider (nouvelles opinions confirmantes) : vérifier qu'un nouvel intent peut de nouveau être
  proposé (deuxième épisode).
- `cleanup(...)` sur un scénario déjà présent dans `proposedScenarioIds` : vérifier (via un nouvel
  appel à `collectActionIntents` après réinsertion d'un scénario avec le même id — ou plus simplement
  en inspectant que le set ne grossit pas indéfiniment) que l'id a bien été purgé.

---

## Étape 3 — Quantité placeholder non nulle dans `ActionIntent`

**Contexte** : `DefaultMarketScenario.proposeIntent(...)` construit toujours `new BigDecimal(0.0)`
comme quantité (`// TODO : Manage quantity!`). Le vrai calcul (curseur de risque × `WalletSnapshot` ×
rôle du wallet) est un chantier à part entière, volontairement **hors scope ici** (cf. étude §4/§7,
non tranchés) — `DefaultMarketScenario` n'a d'ailleurs même pas accès au prix ou au wallet dans son
contexte actuel (`ScenarioContext` ne transporte que `owner`/`symbol`/`clock`/`globalScenarios`), donc
un vrai calcul n'y a pas sa place de toute façon.

**Objectif de ce correctif, volontairement minimal** : remplacer le zéro figé par une constante
placeholder non nulle et **explicitement documentée comme telle**, pour que la donnée cesse d'être
trivialement dégénérée en aval (`DecisionCandidate`, `ActionStep`) — utile pour vérifier que tout le
pipeline transporte une vraie valeur, pas pour produire un ordre exploitable (de toute façon, aucun
exécuteur ne consomme encore `ActionStep` en pratique, cf. étude §12 point 1 : rien n'exécute de
vrai ordre aujourd'hui).

**À faire** :

1. Ajouter une constante en tête de `DefaultMarketScenario` :
   ```java
   /**
    * Placeholder en attendant le composant de sizing réel (curseur de risque × WalletSnapshot ×
    * rôle du wallet — cf. docs/etudes/etude-mecanique-decision-dca-intelligent.md §4/§7/§12 pt 7).
    * DefaultMarketScenario n'a pas accès au prix ni au wallet dans son contexte actuel : cette
    * valeur ne doit JAMAIS être utilisée pour un ordre réel.
    */
   private static final BigDecimal PLACEHOLDER_QUANTITY = BigDecimal.ONE;
   ```
2. Remplacer `new BigDecimal(0.0)` par `PLACEHOLDER_QUANTITY` dans `proposeIntent(...)`.
   (Profiter du passage pour noter que `new BigDecimal(0.0)` était de toute façon le constructeur
   basé sur `double` — imprécis par nature — remplacé ici par une constante propre, pas juste une
   nouvelle valeur.)

**Tests attendus** (`DefaultMarketScenarioTest`, en étendant le test existant qui amène un scénario à
`VALIDATED` et vérifie `intent.get().action()`) :
- Ajouter une assertion sur le même test (ou un test dédié) : `intent.get().quantity()` n'est pas
  nul et vaut la constante attendue (`assertEquals(BigDecimal.ONE, intent.get().quantity())` ou
  équivalent en comparant les valeurs numériques plutôt que la référence, `BigDecimal.equals` étant
  sensible à l'échelle — préférer `compareTo(...) == 0` si un doute existe sur le scale).

---

## À la fin : lancer les tests via la Gateway

Une fois les 3 étapes implémentées et testées, compiler et exécuter la suite de tests complète du
projet via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`, ou lister les opérations disponibles avec
`listOperations` si besoin de confirmer le nom exact). Ne pas lancer `mvn` directement dans un
sandbox local : le projet ne compile/teste que via cette gateway (pas de Maven/réseau disponible en
sandbox).

Rapporter : le résultat global (succès/échec), le nombre de tests exécutés (baseline connue : 458
tests au 2026-08-10 avant ce lot), et le détail de tout test en échec (classe + message). Signaler
explicitement si le test bout-en-bout optionnel de l'étape 1 (mock `ScenarioEngine`) s'avère trop
lourd à mettre en place proprement — dans ce cas, les 3 tests unitaires directs suffisent, ne pas
forcer un montage artificiel juste pour la couverture bout-en-bout.

Une fois ce lot mergé, mettre à jour `docs/etudes/etude-mecanique-decision-dca-intelligent.md` (§2.2)
et le point d'avancement pour refléter que les TODO critiques #1-3 sont résolus, avant d'attaquer le
Palier 2 (socle multi-utilisateur, §11 de l'étude).
