# Prompt d'implémentation — Décision, Palier 3, Étape 4 (persistance — photo quotidienne + rejeu delta + restauration)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 4** de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md`. Référence :
`docs/etudes/etude-branchement-persistance-decision-engine.md` §C (C1/C2/C4) + §E point 3.
**Prérequis** : Étapes 1 et 3 mergées (branchement B3 + extensions de modèle : `DecisionCreatedCause`
porte les `ActionStep`, `DefaultMarketScenario` a un constructeur de reconstruction, `ScenarioEvent` a
un champ `scope`).

**Décisions prises avec Clem le 2026-08-13, avant rédaction de ce prompt** (l'étude/la roadmap
laissaient plusieurs points ouverts — à ne pas retrancher) :
1. **Granularité des tables de snapshot** : deux tables dédiées, `scenario_snapshots` et
   `decision_snapshots` (pas une table générique unique).
2. **Exposition "toutes les données actives, tous owners"** : nouvelles méthodes publiques dédiées sur
   chaque moteur (`ScenarioEngine.getAllActiveScenarios(...)`, `DecisionEngine.getAllActiveDecisions()`)
   — choix laissé à mon appréciation par Clem ; justifié étape 3 ci-dessous.
3. **Activation du cron** : le job de photo quotidienne est écrit mais **désactivé par défaut** en
   prod (même logique que le scheduler de décision resté postposé) ; un **endpoint REST admin** est
   ajouté pour le déclencher manuellement (patron `EtfFlowAdminController`).
4. **Périmètre du rejeu delta** : couvre à la fois les mutations d'entités déjà présentes dans la
   dernière photo **et** la création d'entités entièrement nouvelles apparues après cette photo —
   requête globale par date, pas ciblée par `targetId` connu.
5. **Fidélité d'id pour `Decision`** (découverte en préparant ce prompt : `Decision.id` — utilisé comme
   `targetId` des `DecisionEvent` persistés — et `DecisionSnapshot.decisionId()` sont deux identifiants
   distincts ; reconstruire une `Decision` sans préserver `Decision.id` l'orphelinerait de ses
   événements déjà persistés) : **ajouter un constructeur de reconstruction à `Decision`**, même
   patron que `DefaultMarketScenario` à l'étape 3.
6. **Bug préexistant découvert en préparant ce prompt, sans rapport avec la persistance mais dans le
   même fichier** : `Decision.generateDecisionId()` est appelé en tout premier dans le constructeur,
   **avant** que `type`/`createdAt` soient assignés — il produit donc toujours un id de la forme
   `"null-null-<8 chars>"` en prod aujourd'hui, jamais `"ENTER-2026-...-<8 chars>"` comme le nom
   suggère. **À corriger dans ce lot** (réordonner les assignations) puisque ce fichier est de toute
   façon modifié pour le point 5. Les id déjà persistés (`"null-null-xxxx"`) restent tels quels, ce
   lot ne les corrige pas rétroactivement — seuls les nouveaux id générés après ce correctif auront le
   bon format.

**Ce que ce lot n'est PAS** : pas l'orchestrateur (étape 7, qui alimentera réellement les moteurs — sans
lui, `scenarios`/`activeDecisions` restent vides en usage réel, donc rien à photographier avant qu'il
existe ; ce lot construit le mécanisme, pas ce qui le rend utile en pratique) ; pas l'archivage sur
inactivité prolongée (étape 6, qui réutilisera ce mécanisme de photo mais avec une politique
différente) ; pas la détection de connexion utilisateur (étape 5).

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-branchement-persistance-decision-engine.md` — §C en entier et §E point 3.
2. `service/tree/event/engine/JpaEventStore.java` — `toDomain()` (switch à corriger, point 6 des
   lectures) et `append(...)` (patron de sérialisation Jackson déjà en place, à réutiliser).
3. `repository/decision/EventRepository.java` — `findByType`/`findByTargetId` existants, patron de
   requête dérivée Spring Data à suivre pour la nouvelle requête (étape 2 ci-dessous).
4. `model/entity/tree/EventEntity.java` — patron "id/targetId/type/timestamp/payload JSON" déjà utilisé
   pour le log d'événements ; **ne pas confondre avec les nouvelles tables de snapshot** (concept
   différent : ceci est le log append-only déjà actif, les snapshots sont une photo de l'état courant).
5. `service/tree/scenario/DefaultScenarioEngine.java` — `scenarios` (map interne, package-private),
   `cleanup(...)` (critère "actif" déjà utilisé, à réutiliser pour la photo), le constructeur de
   reconstruction de `DefaultMarketScenario` ajouté à l'étape 3.
6. `service/tree/decision/DecisionEngine.java` — `activeDecisions` (map interne, aucun accesseur
   public aujourd'hui), pas de méthode `cleanup()` équivalente à celle de `DefaultScenarioEngine` (les
   décisions ne sont aujourd'hui jamais évincées de cette map — hors scope de ce lot, à signaler dans
   le rapport final si non traité).
7. `service/tree/decision/Decision.java` — le seul constructeur existant, `generateDecisionId()` (bug
   à corriger, point 6 ci-dessus), `apply(DecisionEvent event)` (mécanisme déjà en place pour appliquer
   `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED` — **c'est le mécanisme de rejeu delta pour `Decision`**,
   contrairement à `MarketScenario` qui n'en a pas besoin puisque `ScenarioEvent.after` porte déjà
   l'état complet).
8. `model/dto/event/DecisionEvent.java` — noter que **contrairement à `ScenarioEvent`, `DecisionEvent`
   ne porte pas d'état `before`/`after`** (champs présents mais commentés) : le rejeu delta d'une
   `Decision` doit donc rejouer chaque événement via `apply(...)`, pas prendre un raccourci "dernier
   état connu" comme pour `MarketScenario`.
9. `model/dto/tree/decision/DecisionSnapshot.java`, `model/dto/tree/scenario/ScenarioDefinition.java`,
   `model/dto/tree/scenario/ScenarioState.java` — les DTO à sérialiser/désérialiser pour les deux
   nouvelles tables.
10. `controller/EtfFlowAdminController.java` — patron exact à reproduire pour le déclenchement manuel
    (`@RestController`, `@PreAuthorize("hasRole('ADMIN')")`, `@PostMapping`, pas de trigger automatique
    au démarrage).
11. `service/scheduler/EtfFlowHistorizationJob.java` — patron `@Scheduled(cron = "${prop:default}")`
    déjà utilisé dans ce projet ; pour ce lot, désactiver par défaut plutôt que fournir un cron actif
    (cf. décision 3 ci-dessus — vérifier en testant si la valeur spéciale `"-"` désactive bien le
    trigger dans la version de Spring utilisée par ce projet ; si ce n'est pas le cas, utiliser un
    flag booléen de propriété (`tradeio.decision.snapshot-enabled:false`) gate en tête de méthode
    plutôt que de forcer un cron qui ne se désactiverait pas comme attendu).
12. `configuration/initializer/AssetInitializer.java` — patron `CommandLineRunner` + `@Order` déjà
    utilisé pour du travail au démarrage, à reproduire pour la restauration (étape 8 ci-dessous).
13. Tests à prendre comme patron : `service/tree/scenario/SharedScenarioEngineMultiOwnerTest.java`
    (manipulation directe de `engine.scenarios`), `service/tree/decision/DecisionEngineTest.java`,
    tout `@DataJpaTest` existant pour les nouveaux repositories (ex. `AssetProviderRepositoryTest`
    comme patron de configuration H2).

Ne rien modifier en dehors de ce qui est listé ci-dessous.

---

## Étape 1 — Corriger les deux bugs prérequis

**1a. `JpaEventStore.toDomain()`** : ajouter le cas manquant.
```java
private PersistableEvent toDomain(EventEntity entity) {
    try {
        return switch (entity.getType()) {
            case SCENARIO -> objectMapper.readValue(entity.getPayload(), ScenarioEvent.class);
            case OPINION -> objectMapper.readValue(entity.getPayload(), OpinionEvent.class);
            case DECISION -> objectMapper.readValue(entity.getPayload(), DecisionEvent.class);
        };
    } catch (Exception e) {
        log.error("Failed to deserialize event {}: {}", entity.getId(), e.getMessage(), e);
        return null;
    }
}
```
(le `default` levant `IllegalArgumentException` peut être retiré maintenant que les 3 valeurs de
`EventType` sont couvertes — un `switch` exhaustif sur enum n'en a plus besoin ; si le compilateur
exige quand même un cas par défaut selon la configuration du projet, le garder mais ce serait
surprenant pour un `switch` sur enum à 3 valeurs déjà toutes couvertes).

**1b. `Decision` : ordre d'assignation dans le constructeur existant.**
```java
public Decision(DecisionSnapshot snapshot, List<ActionStep> steps, EventBus bus) {
    symbol = snapshot.symbol();
    owner = snapshot.owner();
    type = snapshot.type();
    this.snapshot = snapshot;
    this.steps = List.copyOf(steps);
    this.lastUpdatedAt = snapshot.createdAt();
    this.createdAt = snapshot.createdAt(); // était déjà lu par generateDecisionId() mais jamais assigné
    this.eventBus = bus;
    id = generateDecisionId(); // déplacé en dernier : dépend de type/createdAt désormais assignés
}
```

**Tests attendus** :
- `JpaEventStoreTest` (existant ou à créer si absent — vérifier avant) : persister un `DecisionEvent`
  réel via `append(...)`, le relire via `loadByTargetId(...)`/`loadById(...)`, vérifier qu'aucune
  exception n'est levée et que l'objet désérialisé est un `DecisionEvent` avec les bons champs (couvre
  spécifiquement la régression du bug déjà connu).
- `DecisionTest` (existant, à étendre) : construire une `Decision` avec un `type`/`snapshot.createdAt()`
  connus, vérifier que `getId()` ne contient plus la sous-chaîne littérale `"null"` et contient bien le
  `type`/la date attendus (couvre la régression du bug de l'étape 1b — écrire ce test *avant* de
  corriger et vérifier qu'il échoue sur le code actuel).

---

## Étape 2 — `EventRepository` : requête delta globale par date

**À faire** :
```java
List<EventEntity> findByTimestampAfter(Instant timestamp);
```
Requête dérivée Spring Data, même style que `findByType`/`findByTargetId` déjà présents — pas de
`@Query` manuelle nécessaire.

**Tests attendus** (`EventRepositoryTest`, `@DataJpaTest`) : persister trois `EventEntity` avec des
`timestamp` distincts, vérifier que `findByTimestampAfter(t)` renvoie exactement les entités
postérieures à `t`, ni celle égale à `t` (choisir et documenter explicitement le comportement exact
aux bornes — `isAfter`, donc strictement postérieur, à vérifier que c'est bien le comportement Spring
Data par défaut pour ce nom de méthode plutôt que de le supposer) ni les antérieures.

---

## Étape 3 — Exposer "toutes les données actives, tous owners" sur chaque moteur

**Justification du choix** (Clem a laissé la méthode à mon appréciation) : garder l'encapsulation des
maps internes (`scenarios`/`activeDecisions` restent package-private/private), ajouter une méthode
symétrique à `getActiveScenarios(owner, ...)` mais sans filtre owner — cohérent avec l'esprit B3 où le
filtrage par owner est déjà une opération de lecture, pas une propriété structurelle de la donnée.
Alternative rejetée : élargir la visibilité des champs eux-mêmes (casserait l'encapsulation pour un
besoin ponctuel, moins testable proprement).

**À faire** :

1. `ScenarioEngine.java` (interface) : ajouter
   ```java
   List<MarketScenario> getAllActiveScenarios(Duration maxAge, Instant now);
   ```
2. `DefaultScenarioEngine.java` : implémenter en réutilisant le même critère "actif" que
   `getActiveScenarios`/`cleanup`, sans filtre `isVisibleForOwner` :
   ```java
   @Override
   public List<MarketScenario> getAllActiveScenarios(Duration maxAge, Instant now) {
       return scenarios.values().stream()
               .filter(s -> s.isActive(now, maxAge))
               .toList();
   }
   ```
3. `DecisionEngine.java` : ajouter une méthode équivalente. **"Actif" pour une `Decision`** : ce projet
   n'a aujourd'hui aucune notion d'activité pour `Decision` (pas de `cleanup()` équivalent, cf. lecture
   préalable point 6). En pratique, seuls `CREATED`/`EXECUTED`/`ABORTED` sont aujourd'hui jamais
   assignés dans le code (`OBSERVING`/`CLOSED`/`EXPIRED` existent dans l'enum mais ne sont produits par
   aucun chemin actuel) — "actif" = `status == DecisionStatus.CREATED` (le seul statut non-terminal
   réellement atteignable aujourd'hui). Documenter ce choix en javadoc plutôt que le laisser implicite,
   et signaler dans le rapport final si `OBSERVING`/`CLOSED`/`EXPIRED` s'avèrent en fait déjà produits
   quelque part (vérifier par recherche avant de conclure que non) :
   ```java
   /**
    * Toutes les Decision actives, tous owners confondus (photo quotidienne, étape 4 Palier 3).
    * "Actif" = statut CREATED, seul statut non-terminal réellement atteint aujourd'hui (EXECUTED/
    * ABORTED sont terminaux ; OBSERVING/CLOSED/EXPIRED existent dans DecisionStatus mais ne sont
    * produits par aucun chemin de code actuel — à revisiter si ça change).
    */
   public List<Decision> getAllActiveDecisions() {
       return activeDecisions.values().stream()
               .filter(d -> d.getStatus() == DecisionStatus.CREATED)
               .toList();
   }
   ```

**Tests attendus** :
- `DefaultScenarioEngineUnitTest` (ou `SharedScenarioEngineMultiOwnerTest`) : insérer des scénarios pour
  deux owners différents, vérifier que `getAllActiveScenarios(...)` les renvoie tous les deux (contraste
  explicite avec `getActiveScenarios(owner, ...)` qui n'en renverrait qu'un).
- Test équivalent pour `DecisionEngine.getAllActiveDecisions()` : décisions pour deux owners, statuts
  variés (`CREATED` et `EXECUTED`), vérifier que seule celle en `CREATED` est renvoyée, tous owners
  confondus.

---

## Étape 4 — `Decision` : constructeur de reconstruction

**À faire**, même patron que `DefaultMarketScenario` (étape 3) :
```java
/**
 * Reconstruction depuis un état déjà persisté (Palier 3, étape 4). Contrairement au constructeur
 * principal, ne génère pas de nouvel id : réinjecte celui fourni, pour que l'objet reconstruit
 * conserve l'identité sous laquelle ses DecisionEvent ont été persistés (DecisionEvent.getDecisionId()
 * utilise Decision.id, PAS DecisionSnapshot.decisionId() — les deux sont des identifiants distincts,
 * cf. décision actée en tête de ce prompt). N'appelle PAS le constructeur principal.
 */
public Decision(
        String id,
        DecisionSnapshot snapshot,
        List<ActionStep> steps,
        DecisionStatus status,
        Set<String> executedStepIds,
        Instant lastUpdatedAt,
        EventBus bus
) {
    this.id = id;
    this.symbol = snapshot.symbol();
    this.owner = snapshot.owner();
    this.type = snapshot.type();
    this.snapshot = snapshot;
    this.steps = List.copyOf(steps);
    this.status = status;
    this.executedStepIds.addAll(executedStepIds);
    this.lastUpdatedAt = lastUpdatedAt;
    this.createdAt = snapshot.createdAt();
    this.eventBus = bus;
}
```
Note : `executedStepIds` est aujourd'hui `private final Set<String> executedStepIds = new
HashSet<>();` (initialisé inline, pas assignable directement) — utiliser `.addAll(...)` comme ci-dessus
plutôt que de réassigner le champ, ou retirer l'initialiseur inline si une réassignation propre est
préférée (vérifier ce qui compile proprement et reste cohérent avec le constructeur principal existant,
qui compte sur l'initialiseur inline pour démarrer avec un set vide).

**Tests attendus** (`DecisionTest`) : construire une `Decision` via ce constructeur avec un `id` connu,
un `status = EXECUTED`, un `executedStepIds` non vide ; vérifier `getId()` renvoie exactement l'id
fourni (pas régénéré), `getStatus()` renvoie `EXECUTED`, et qu'un `apply(...)` ultérieur sur cette
instance reconstruite se comporte cohéremment avec l'état restauré (ex. un second `ACTION_STEP_EXECUTED`
sur le seul step restant fait bien passer `isAllActionStepsExecuted()`/le statut si applicable).

---

## Étape 5 — Tables de snapshot dédiées

**À faire** :

1. `model/entity/tree/scenario/ScenarioSnapshotEntity.java` (nouveau package si `scenario_snapshots`
   n'a pas sa place dans un package existant — suivre la convention déjà utilisée pour
   `ScenarioEventEntity`) :
   ```java
   @Data
   @Entity
   @Table(name = "scenario_snapshots")
   public class ScenarioSnapshotEntity {
       @Id
       private String scenarioId; // = MarketScenario.getId(), PK — une ligne par scénario, upsert à chaque photo

       private String scenarioType;
       private String owner;
       private String symbol; // nullable
       private String scope;

       @Column(columnDefinition = "TEXT")
       private String stateJson; // ScenarioState sérialisé (Jackson), même convention que EventEntity.payload

       private Instant snapshotAt;
   }
   ```
   **Choix "upsert, une ligne par scénario" plutôt qu'un historique de photos accumulées** : la photo
   sert à accélérer un redémarrage ("charger la dernière photo"), pas à naviguer un historique — ce
   besoin est déjà couvert par le log d'événements existant (`events`, actif depuis l'étude §A.6). Si
   ce choix s'avère faux (Clem veut in fine garder un historique de photos, pas juste la dernière), le
   signaler dans le rapport final plutôt que de le découvrir plus tard silencieusement.
2. `repository/scenario/ScenarioSnapshotRepository.java` : `extends JpaRepository<ScenarioSnapshotEntity,
   String>` — `save(...)` sert déjà d'upsert (PK = `scenarioId`).
3. `model/entity/tree/decision/DecisionSnapshotEntity.java` :
   ```java
   @Data
   @Entity
   @Table(name = "decision_snapshots")
   public class DecisionSnapshotEntity {
       @Id
       private String decisionId; // = Decision.getId() (PAS DecisionSnapshot.decisionId(), cf. étape 4)

       private String symbol;
       private String owner;
       private String type;
       private String status;

       @Column(columnDefinition = "TEXT")
       private String snapshotJson; // DTO de transport (snapshot + steps + executedStepIds + timestamps), sérialisé

       private Instant snapshotAt;
   }
   ```
   Pour `snapshotJson`, créer un petit DTO de transport interne (ex. record
   `DecisionSnapshotPayload(DecisionSnapshot snapshot, List<ActionStep> steps, Set<String>
   executedStepIds, Instant lastUpdatedAt)`) plutôt que d'éclater chaque champ en colonne séparée —
   cohérent avec la convention JSON déjà utilisée ailleurs dans ce projet pour des structures
   composites (`EventEntity.payload`, `ScenarioSnapshotEntity.stateJson` ci-dessus).
4. `repository/decision/DecisionSnapshotRepository.java` : `extends JpaRepository<DecisionSnapshotEntity,
   String>`.

**Tests attendus** : un `@DataJpaTest` par repository (patron `AssetProviderRepositoryTest`) — persister
une entité, la relire, vérifier les champs. Pas besoin de tester la sérialisation JSON ici (couvert par
les tests du service de photo, étape 6).

---

## Étape 6 — Service de photo quotidienne

**À faire**, nouveau `service/tree/decision/DecisionScenarioSnapshotService.java` :
```java
@Service
@RequiredArgsConstructor
public class DecisionScenarioSnapshotService {

    private final ScenarioEngine scenarioEngine;
    private final DecisionEngine decisionEngine;
    private final ScenarioSnapshotRepository scenarioSnapshotRepository;
    private final DecisionSnapshotRepository decisionSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final DomainClock clock;

    private static final Duration MAX_SCENARIO_AGE = Duration.ofHours(2); // même valeur que DecisionEngine.MAX_SCENARIO_AGE — voir remarque ci-dessous

    public SnapshotResult takeSnapshot() {
        Instant now = clock.now();
        List<MarketScenario> scenarios = scenarioEngine.getAllActiveScenarios(MAX_SCENARIO_AGE, now);
        List<Decision> decisions = decisionEngine.getAllActiveDecisions();

        // sérialiser chaque scénario/décision vers son entité (mapping direct depuis les getters),
        // scenarioSnapshotRepository.saveAll(...)/decisionSnapshotRepository.saveAll(...)

        return new SnapshotResult(scenarios.size(), decisions.size(), now);
    }
}
```
Remarque à vérifier avant de dupliquer `MAX_SCENARIO_AGE` : cette constante existe déjà en `private
static final` dans `DecisionEngine` — si elle doit rester cohérente entre les deux classes (le
commentaire existant dans `DecisionEngine` dit explicitement "Doit rester cohérent avec
`DefaultMarketScenario.EXPIRATION_IDLE`"), envisager de la sortir dans un endroit partagé plutôt que de
la recopier une troisième fois ; si ça semble disproportionné pour ce lot, dupliquer en gardant le même
commentaire d'avertissement, et signaler le choix dans le rapport final.

`SnapshotResult` : petit record `(int scenarioCount, int decisionCount, Instant snapshotAt)` pour que
l'endpoint REST (étape 7) et le job planifié aient quelque chose à logguer/retourner.

**Tests attendus** (`DecisionScenarioSnapshotServiceTest`, moteurs réels construits directement — patron
`SharedScenarioEngineMultiOwnerTest` — repositories mockés ou H2 réel selon le style déjà utilisé pour
des tests de service similaires dans ce projet) :
- Scénarios/décisions actifs pour deux owners : `takeSnapshot()` persiste bien une ligne par entité
  active dans chaque table, aucune pour les entités inactives/terminales.
- Appeler `takeSnapshot()` deux fois de suite sans changement d'état : vérifier qu'il y a bien upsert
  (même nombre de lignes, pas de doublon) — couvre le choix PK=id de l'étape 5.

---

## Étape 7 — Déclenchement : job planifié désactivé + endpoint REST admin

**À faire** :

1. `service/scheduler/DecisionScenarioSnapshotJob.java`, patron `EtfFlowHistorizationJob` mais
   désactivé par défaut :
   ```java
   @Component
   @RequiredArgsConstructor
   public class DecisionScenarioSnapshotJob {
       private final DecisionScenarioSnapshotService snapshotService;

       @Scheduled(cron = "${tradeio.decision.snapshot-cron:-}")
       public void takeDailySnapshot() {
           snapshotService.takeSnapshot();
       }
   }
   ```
   **Vérifier avant de considérer ce point acquis** : que la valeur `"-"` désactive bien
   `@Scheduled(cron=...)` dans la version de Spring de ce projet (comportement documenté par Spring
   mais à confirmer empiriquement, pas supposer). Si ce n'est pas le cas, remplacer par un flag booléen
   explicite lu en tête de méthode (`@Value("${tradeio.decision.snapshot-enabled:false}") boolean
   enabled`, `if (!enabled) return;`) et le signaler dans le rapport final.
2. `controller/DecisionScenarioSnapshotAdminController.java`, patron exact `EtfFlowAdminController` :
   ```java
   @RestController
   @RequestMapping("/api/admin/decision")
   @PreAuthorize("hasRole('ADMIN')")
   @RequiredArgsConstructor
   public class DecisionScenarioSnapshotAdminController {
       private final DecisionScenarioSnapshotService snapshotService;

       @PostMapping("/snapshot")
       public ResponseEntity<SnapshotResult> triggerSnapshot() {
           return ResponseEntity.ok(snapshotService.takeSnapshot());
       }
   }
   ```

**Tests attendus** :
- Test de contrôleur (patron déjà utilisé ailleurs dans le projet pour les autres `*AdminController` —
  vérifier s'il existe un test pour `EtfFlowAdminController` à reprendre à l'identique) : `POST
  /api/admin/decision/snapshot` sans rôle `ADMIN` → rejeté ; avec rôle `ADMIN` → `200` et
  `SnapshotResult` cohérent (service mocké).
- Si le flag booléen est utilisé (point 1) : test unitaire du job vérifiant qu'il n'appelle pas
  `snapshotService.takeSnapshot()` quand le flag est à `false` (valeur par défaut).

---

## Étape 8 — Restauration au (re)démarrage

**Contexte** : ordre décidé par Clem (§E pt3) — scénarios puis décisions. Pour chaque type : charger la
dernière photo, puis rejouer les événements postérieurs à `snapshotAt` (mutations des entités déjà
snapshottées **et** créations entièrement nouvelles, décision actée en tête de ce prompt). Rejeu
`MarketScenario` : raccourci "dernier état complet" (`ScenarioEvent.after`) — pas de rejeu séquentiel
nécessaire (cf. étude §C, "bonne nouvelle partielle"). Rejeu `Decision` : séquentiel via
`Decision.apply(...)`, `DecisionEvent` ne portant pas d'état complet (cf. lecture préalable point 8).

**À faire** :

1. `ScenarioEngine.java` (interface) : ajouter `void restoreScenarios(List<MarketScenario>
   scenarios);`. `DefaultScenarioEngine` : `scenarios.forEach(s -> this.scenarios.put(keyOf(s), s));`.
2. `DecisionEngine.java` : ajouter `public void restoreDecisions(List<Decision> decisions)` :
   `decisions.forEach(d -> activeDecisions.put(d.getSnapshot().decisionId(), d));` (clé de la map =
   `snapshot.decisionId()`, pas `Decision.id` — vérifié dans `onScenarioEvent` existant, ne pas
   confondre les deux identifiants une deuxième fois dans ce lot).
3. Nouveau `service/tree/decision/DecisionScenarioRestoreRunner.java`, patron `CommandLineRunner` +
   `@Order` comme `AssetInitializer` (choisir un ordre cohérent — après tout seed de données de base
   type `AssetInitializer`, avant tout code qui supposerait le moteur déjà peuplé) :
   ```java
   @Component
   @RequiredArgsConstructor
   @Order(10) // après AssetInitializer (@Order(1)) — vérifier s'il existe d'autres runners avec un ordre à respecter avant de choisir cette valeur
   public class DecisionScenarioRestoreRunner implements CommandLineRunner {
       @Override
       public void run(String... args) {
           restoreScenarios();
           restoreDecisions();
       }

       private void restoreScenarios() {
           // 1. scenarioSnapshotRepository.findAll() → désérialiser stateJson en ScenarioState,
           //    reconstruire ScenarioDefinition(type, owner, symbol, scope, state.getCreatedAt()),
           //    construire via le constructeur de reconstruction DefaultMarketScenario(id, state,
           //    definition, eventBus) — étape 3 du prompt précédent.
           // 2. Pour chaque scénario restauré : eventRepository.findByTargetId(scenarioId) filtré aux
           //    events postérieurs à snapshotAt (ou findByTimestampAfter + filtre en mémoire par
           //    targetId — au choix, privilégier ce qui reste lisible), prendre le DERNIER
           //    ScenarioEvent (par timestamp) s'il y en a, reconstruire l'état final directement
           //    depuis son `after` plutôt que rejouer séquentiellement (raccourci déjà justifié
           //    ci-dessus).
           // 3. Créations nouvelles : eventRepository.findByTimestampAfter(dernière snapshotAt globale
           //    la plus ancienne parmi toutes les photos — ou, plus simple et plus sûr, la plus
           //    récente : à trancher en écrivant le code selon ce qui reste correct si les photos de
           //    différents scénarios n'ont pas toutes le même snapshotAt exact) filtrés
           //    EventType.SCENARIO, group by scenarioId, ne garder que les scenarioId ABSENTS de la
           //    photo, reconstruire depuis leur dernier ScenarioEvent comme au point 2.
           // 4. scenarioEngine.restoreScenarios(reconstructedScenarios).
       }

       private void restoreDecisions() {
           // Même schéma, mais rejeu séquentiel via Decision.apply(...) pour chaque DecisionEvent
           // postérieur (ACTION_STEP_EXECUTED/ACTION_STEP_FAILED), et reconstruction des décisions
           // entièrement nouvelles depuis leur DECISION_CREATED (DecisionEvent + sa
           // DecisionCreatedCause.actionSteps(), étape 3 du prompt précédent) plutôt que depuis une
           // photo qui ne les contient pas encore.
       }
   }
   ```
   Le pseudo-code en commentaire ci-dessus n'est pas une spécification figée au caractère près —
   l'implémenteur doit écrire le vrai code, ces commentaires servent à ne pas perdre le fil du
   raisonnement déjà posé (raccourci scénario vs rejeu séquentiel décision, périmètre mutations+
   créations). Si un point précis reste ambigu en écrivant le code (ex. quelle `snapshotAt` de
   référence utiliser quand plusieurs scénarios n'ont pas été photographiés exactement au même
   instant), **s'arrêter et demander plutôt que de trancher seul** — cohérent avec la consigne de
   Clem sur l'ensemble de ce palier.

**Tests attendus** (nouveau test d'intégration, ex.
`service/tree/decision/DecisionScenarioRestoreIntegrationTest.java`, `@DataJpaTest` ou équivalent avec
accès aux repositories réels) :
- Scénario : construire un `MarketScenario`, le faire vivre (quelques `observe(...)`), prendre une
  photo (`takeSnapshot()`), continuer à le faire évoluer après la photo (nouveaux `ScenarioEvent`
  publiés/persistés), puis simuler un redémarrage (nouvelle instance de moteur + exécuter la logique de
  restauration) : vérifier que l'état restauré correspond à l'état **après** les événements post-photo,
  pas à l'état de la photo elle-même (couvre le rejeu delta, pas juste le chargement brut).
- Décision : même schéma, avec une `Decision` dont un `ActionStepExecutedCause` est publié après la
  photo : vérifier que la `Decision` restaurée a bien `status == EXECUTED` (si tous ses steps sont
  exécutés), pas `CREATED` comme au moment de la photo.
- Création après photo : un scénario/une décision créé entièrement après la dernière photo (jamais
  snapshotté) : vérifier qu'il est bien présent après restauration (couvre le périmètre "créations",
  pas seulement "mutations").
- Cas vide : aucune photo, aucun événement — la restauration ne lève pas d'exception, les moteurs
  démarrent avec des maps vides (comportement actuel, non régressé).

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Porter une attention particulière au premier démarrage du contexte Spring après l'ajout de
`DecisionScenarioRestoreRunner` (`CommandLineRunner`) : s'il échoue (tables absentes, erreur de
désérialisation sur une base vide), le signaler explicitement — bloquerait tout démarrage de
l'application, pas seulement ce lot de tests.

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline obtenue après l'étape 3),
détail de tout échec, et signaler explicitement :
- si la valeur de cron `"-"` a effectivement désactivé le job (étape 7) ou s'il a fallu basculer sur le
  flag booléen ;
- si la mutualisation de `MAX_SCENARIO_AGE` (étape 6) a été faite ou dupliquée, et pourquoi ;
- toute ambiguïté rencontrée en écrivant `DecisionScenarioRestoreRunner` qui n'a pas pu être tranchée
  sans re-solliciter Clem (cf. consigne explicite dans l'étape 8) — même si la session d'implémentation
  n'a pas la possibilité d'attendre une réponse en cours de route, documenter précisément le choix fait
  par défaut et pourquoi, pour qu'il soit revu après coup plutôt que de rester silencieux.

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 4 → ✅) avant de rédiger le
prompt de l'étape 5 (détection de connexion utilisateur, indépendante — peut aussi être rédigée avant
si Clem préfère paralléliser la rédaction, pas l'implémentation).
