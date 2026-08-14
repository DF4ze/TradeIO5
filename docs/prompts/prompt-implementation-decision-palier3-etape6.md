# Prompt d'implémentation — Décision, Palier 3, Étape 6 (archivage sur inactivité prolongée)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 6** de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md`. Référence :
`docs/etudes/etude-branchement-persistance-decision-engine.md` §E point 5. **Prérequis** : Étapes 4 et
5 mergées (photo/rejeu delta + `User.lastLogin`, confirmées en code avant rédaction de ce prompt — pas
supposées).

**Décisions prises avec Clem le 2026-08-13, avant rédaction de ce prompt** :
1. **Délai d'archivage : 2 mois**, valeur retenue telle quelle pour ce lot — **tagger la constante en
   `// TODO parametrize`** (demande explicite de Clem) pour signaler clairement que c'est un paramètre
   à externaliser plus tard (property Spring), pas une valeur figée définitivement.
2. **Déclencheur de l'archivage** : même patron que la photo quotidienne (étape 4, déjà en prod dans le
   code) — job `@Scheduled` désactivé par défaut (`cron = "${tradeio.decision.archival-cron:-}"`, la
   valeur spéciale `"-"` désactivant l'enregistrement de la tâche, déjà vérifiée empiriquement à
   l'étape 4) + endpoint REST admin pour déclenchement manuel (`ROLE_ADMIN`, patron
   `DecisionScenarioSnapshotAdminController`).
3. **Marqueur d'archivage** : nouveau champ explicite `User.archivedAt` (nullable `Instant`), mis à
   jour par le job d'archivage et remis à `null` à la restauration. Préféré à une déduction implicite
   ("pas de données actives dans le moteur") : lecture triviale, sans ambiguïté pour un utilisateur qui
   n'a simplement jamais rien eu à archiver.
4. **Hook de restauration à la reconnexion : uniquement `AuthController.authenticateUserForm`
   (`signinForm`), pas `AuthTokenFilter`.** Raison vérifiée en code (pas supposée) : le JWT expire après
   24h (`bezkoder.app.jwtExpirationMs=86400000`, cookie `maxAge` identique) — un utilisateur inactif
   depuis 2 mois n'a, par construction, plus aucun cookie JWT valide. `AuthTokenFilter` ne authentifie
   jamais quelqu'un dont le token a expiré (`jwtUtils.validateJwtToken(jwt)` échoue), donc il ne peut
   structurellement jamais être le point où un utilisateur archivé se reconnecte — seul `signinForm`
   peut l'être. Inutile de dupliquer le hook comme à l'étape 5 (où le raisonnement était différent :
   capter une activité continue, pas une reconnexion après coupure de session garantie).

**Ce que ce lot n'est PAS** : pas l'orchestrateur (étape 7) ; pas de changement du contenu de la photo
elle-même (étape 4, déjà correcte) ; pas de purge définitive des données (l'archivage retire de la
mémoire active, il ne supprime rien en base — la photo + les événements restent, c'est ce qui permet la
restauration).

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-branchement-persistance-decision-engine.md` §E point 5.
2. `security/model/User.java` — `lastLogin` (étape 5, dernier champ). **Point d'attention identique à
   l'étape 5** : `@AllArgsConstructor` génère un constructeur positionnel, un seul site d'appel connu
   (`AuthController.registerUserForm`, déjà mis à jour pour `lastLogin`) — à mettre à jour à nouveau
   pour `archivedAt`.
3. `security/repository/UserRepository.java` — `extends JpaRepository<User, Long>`, patron de requête
   dérivée déjà utilisé ailleurs dans ce projet (ex. `findByType`/`findByTimestampAfter` sur
   `EventRepository`) à reproduire pour la requête de l'étape 1 ci-dessous.
4. `controller/AuthController.java` — `authenticateUserForm(...)` : le hook `lastLogin` de l'étape 5
   (lignes ~129-134), point d'insertion pour le hook de restauration de ce lot.
5. `service/tree/scenario/ScenarioEngine.java` / `DefaultScenarioEngine.java` — `restoreScenarios(...)`
   (étape 4, "écrase silencieusement toute entrée existante à la même clé"), `getActiveScenarios(owner,
   ...)` (déjà scopé par owner, réutilisable directement pour l'éviction).
6. `service/tree/decision/DecisionEngine.java` — `restoreDecisions(...)`, `activeDecisions` (map
   interne, clé = `decision.getSnapshot().decisionId()`, **pas** `decision.getId()` — piège déjà
   documenté dans le code, à ne pas confondre une deuxième fois).
7. `service/tree/decision/DecisionScenarioRestoreRunner.java` — **lire en entier, pas en diagonale** :
   c'est la logique la plus dense de tout le palier (départage des créations concurrentes via
   `ScenarioStatus.ordinal()`, UUID déterministe pour les `Decision` jamais snapshottées, fallback
   `DEFAULT_SCOPE_FOR_LEGACY_EVENTS`). Ce lot doit **réutiliser cette logique, pas la réécrire** — cf.
   étape 3 ci-dessous.
8. `service/tree/decision/DecisionScenarioSnapshotService.java` + `service/scheduler/
   DecisionScenarioSnapshotJob.java` + `controller/DecisionScenarioSnapshotAdminController.java` —
   patron exact à reproduire pour le service/job/controller d'archivage (étape 4 ci-dessous).
9. `security/jwt/JwtUtils.java` (`jwtExpirationMs`) et `src/main/resources/
   application-profile.properties.template` (`bezkoder.app.jwtExpirationMs=86400000`) — la donnée qui
   justifie la décision 4 ci-dessus, à vérifier toujours présente/inchangée avant de considérer cette
   décision acquise.

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher `AuthTokenFilter.java` (hook
volontairement absent de ce lot, décision 4 ci-dessus) ni au contenu de `DecisionScenarioSnapshotService`
(réutilisé tel quel, pas étendu).

---

## Étape 1 — `User.archivedAt` + requête des candidats à l'archivage

**À faire** :
```java
/**
 * Date d'archivage (Palier 3, étape 6) si cet utilisateur a été retiré de la mémoire active pour
 * inactivité prolongée (2 mois, cf. ArchivalService). Null tant que jamais archivé, ou après
 * restauration à la reconnexion.
 */
private Instant archivedAt;
```
Ajouter en dernière position (même raison qu'à l'étape 5 : limiter la portée de la rupture du
constructeur positionnel).

**À mettre à jour** : `AuthController.registerUserForm(...)` — ajouter `null` en dernier argument de
`new User(...)` (`archivedAt = null` à l'inscription).

**`UserRepository`** :
```java
List<User> findByLastLoginBeforeAndArchivedAtIsNull(Instant threshold);
```

**Tests attendus** : `UserRepositoryTest` (`@DataJpaTest`) — trois `User` avec des `lastLogin`
distincts (un ancien+non archivé, un ancien+déjà archivé, un récent) : vérifier que la requête ne
renvoie que le premier. Mettre à jour tout test construisant `User` par le constructeur positionnel
(rechercher avant de conclure qu'il n'y en a pas, même vérification qu'à l'étape 5).

---

## Étape 2 — Éviction d'un owner des moteurs partagés

**À faire** :

1. `ScenarioEngine.java` (interface) : ajouter
   ```java
   /**
    * Retire toutes les données d'un owner de la mémoire active (Palier 3, étape 6 — archivage sur
    * inactivité). Ne persiste rien : appelant responsable d'avoir pris une photo à jour avant
    * d'évincer (cf. ArchivalService).
    */
   void evictOwner(ScenarioOwner owner);
   ```
   `DefaultScenarioEngine` :
   ```java
   @Override
   public void evictOwner(ScenarioOwner owner) {
       scenarios.keySet().removeIf(key -> key.owner().equals(owner));
   }
   ```
2. `DecisionEngine.java` : ajouter
   ```java
   /**
    * Symétrique à ScenarioEngine.evictOwner (Palier 3, étape 6).
    */
   public void evictOwner(ScenarioOwner owner) {
       activeDecisions.values().removeIf(d -> d.getOwner().equals(owner));
   }
   ```
   Vérifier que `Map.values().removeIf(...)` retire bien les entrées correspondantes de la map
   sous-jacente (comportement standard de `Collection.removeIf` sur une vue de map — à confirmer en
   testant, pas à supposer, `activeDecisions` étant un `ConcurrentHashMap`).

**Tests attendus** :
- `DefaultScenarioEngineUnitTest`/`SharedScenarioEngineMultiOwnerTest` : scénarios pour deux owners,
  `evictOwner(ownerA)` : `getActiveScenarios(ownerA, ...)` vide ensuite, `getActiveScenarios(ownerB,
  ...)` inchangé.
- Test équivalent pour `DecisionEngine.evictOwner(...)`.

---

## Étape 3 — Extraire la logique de restauration en service réutilisable, owner-scopée

**Contexte** : `DecisionScenarioRestoreRunner` (étape 4) contient toute la logique de reconstruction
mais est un `CommandLineRunner` à usage unique (tout restaurer au démarrage, sans filtre). Ce lot a
besoin d'une variante **owner-scopée**, appelable à la demande (hook de reconnexion). Objectif :
**réutiliser** la logique déjà écrite et déjà durcie (départage des créations concurrentes, UUID
déterministe, fallback de scope), pas la dupliquer ni la réécrire.

**À faire** :

1. Créer `service/tree/decision/DecisionScenarioRestoreService.java`, `@Service`. Déplacer **tel quel**
   le corps de `restoreScenarios()`/`restoreDecisions()` et toutes leurs méthodes privées auxiliaires
   (`latestEvent`, `keyOf`, `scenarioFromSnapshot`, `scenarioFromEvent`, `resolveScope`,
   `decisionFromSnapshot`, `decisionFromEvents`, `deserializeScenarioEvent`,
   `deserializeDecisionEvent`, `deserialize`, et les constantes `DEFAULT_SCOPE_FOR_LEGACY_EVENTS`)
   depuis `DecisionScenarioRestoreRunner` vers ce nouveau service.
2. Paramétrer les deux méthodes par un filtre owner optionnel :
   ```java
   public RestoreSummary restoreAll() {
       return restore(Optional.empty());
   }

   public RestoreSummary restoreOwner(ScenarioOwner owner) {
       return restore(Optional.of(owner));
   }

   private RestoreSummary restore(Optional<ScenarioOwner> ownerFilter) {
       List<MarketScenario> scenarios = restoreScenarios(ownerFilter);
       List<Decision> decisions = restoreDecisions(ownerFilter);
       scenarioEngine.restoreScenarios(scenarios);
       decisionEngine.restoreDecisions(decisions);
       return new RestoreSummary(scenarios.size(), decisions.size());
   }
   ```
   Dans `restoreScenarios(Optional<ScenarioOwner> ownerFilter)`/`restoreDecisions(...)` (les anciennes
   méthodes déplacées) : appliquer le filtre **à deux endroits distincts**, ne pas en oublier un —
   - sur `snapshotEntities` chargées (`scenarioSnapshotRepository.findAll()` → filtrer par
     `ScenarioOwner.fromString(e.getOwner()).equals(ownerFilter.orElse(...))` si le filtre est présent,
     sinon tout garder — ou ajouter directement une méthode de repository `findByOwner(String owner)`
     si plus lisible, au choix) ;
   - sur les événements post-photo déserialisés, **avant** le regroupement par id (`.filter(e ->
     ownerFilter.isEmpty() || ownerFilter.get().equals(e.getOwner()))`) — sans ce filtre, une
     restauration owner-scopée récupérerait par erreur les créations nouvelles d'**autres** owners.
   `referenceInstant` (la plus ancienne `snapshotAt`) doit être recalculée sur l'ensemble **déjà
   filtré** par owner quand un filtre est actif, pas sur l'ensemble global — sinon une restauration
   owner-scopée scannerait inutilement tout l'historique d'événements de tous les owners.
3. `DecisionScenarioRestoreRunner` devient un simple délégateur :
   ```java
   @Component
   @RequiredArgsConstructor
   @Order(60)
   public class DecisionScenarioRestoreRunner implements CommandLineRunner {
       private final DecisionScenarioRestoreService restoreService;

       @Override
       public void run(String... args) {
           restoreService.restoreAll();
       }
   }
   ```
4. `RestoreSummary` : petit record `(int scenarioCount, int decisionCount)`, même esprit que
   `SnapshotResult` de l'étape 4.

**Tests attendus** : **ne pas perdre la couverture existante** — `DecisionScenarioRestoreIntegrationTest`
(étape 4, si ce nom exact existe — vérifier) doit continuer à passer sans modification de ses
assertions, seulement (si nécessaire) de la façon dont le service est instancié/appelé. Ajouter :
- Un test dédié au filtre owner : deux owners avec chacun un scénario/une décision snapshottés et un
  événement de mutation postérieur ; `restoreOwner(ownerA)` ne restaure que les données de A dans le
  moteur (vérifié via `getActiveScenarios`/`getAllActiveDecisions` filtré), rien de B.
- Un test où B a une création **entièrement nouvelle** (jamais snapshottée) après la dernière photo de
  A : `restoreOwner(ownerA)` ne doit pas la faire apparaître (couvre le filtre appliqué aux
  événements, pas seulement aux photos).

---

## Étape 4 — Service, job et endpoint d'archivage

**À faire**, patron exact `DecisionScenarioSnapshotService`/`Job`/`AdminController` :

1. `service/tree/decision/UserArchivalService.java` :
   ```java
   @Service
   @RequiredArgsConstructor
   public class UserArchivalService {

       private static final Logger log = LoggerFactory.getLogger(UserArchivalService.class);

       // TODO parametrize — valeur de départ actée par Clem (2026-08-13), à externaliser en
       // property Spring quand ce palier sera éprouvé en usage réel.
       private static final Duration ARCHIVAL_DELAY = Duration.ofDays(60);

       private final UserRepository userRepository;
       private final DecisionScenarioSnapshotService snapshotService;
       private final ScenarioEngine scenarioEngine;
       private final DecisionEngine decisionEngine;
       private final DomainClock clock;

       public ArchivalResult archiveInactiveUsers() {
           Instant now = clock.now();
           Instant threshold = now.minus(ARCHIVAL_DELAY);

           // Photo globale avant toute éviction : garantit que chaque owner évincé a un état à jour
           // en base, sans avoir à isoler une photo par owner (réutilise le service existant tel
           // quel — cf. étape 4 du palier, décision de ne pas dupliquer ce mécanisme).
           snapshotService.takeSnapshot();

           List<User> candidates = userRepository.findByLastLoginBeforeAndArchivedAtIsNull(threshold);
           for (User user : candidates) {
               ScenarioOwner owner = ScenarioOwner.of(user);
               scenarioEngine.evictOwner(owner);
               decisionEngine.evictOwner(owner);
               user.setArchivedAt(now);
           }
           userRepository.saveAll(candidates);

           log.info("UserArchivalService: {} utilisateur(s) archivé(s) (inactifs depuis {}).",
                   candidates.size(), ARCHIVAL_DELAY);

           return new ArchivalResult(candidates.size(), now);
       }
   }
   ```
   `ArchivalResult` : record `(int archivedCount, Instant archivedAt)`.
2. `service/scheduler/UserArchivalJob.java` :
   ```java
   @Component
   @RequiredArgsConstructor
   public class UserArchivalJob {
       private final UserArchivalService archivalService;

       @Scheduled(cron = "${tradeio.decision.archival-cron:-}")
       public void archiveInactiveUsers() {
           archivalService.archiveInactiveUsers();
       }
   }
   ```
3. `controller/UserArchivalAdminController.java` :
   ```java
   @RestController
   @RequestMapping("/api/admin/decision")
   @PreAuthorize("hasRole('ADMIN')")
   @RequiredArgsConstructor
   public class UserArchivalAdminController {
       private final UserArchivalService archivalService;

       @PostMapping("/archive")
       public ResponseEntity<ArchivalResult> triggerArchival() {
           return ResponseEntity.ok(archivalService.archiveInactiveUsers());
       }
   }
   ```
   (même `@RequestMapping` de base que `DecisionScenarioSnapshotAdminController` — vérifier qu'ajouter
   une seconde classe de controller sur le même préfixe `/api/admin/decision` avec un chemin différent
   `/archive` ne pose pas de conflit Spring — ça ne devrait pas, chemins distincts, mais à confirmer en
   démarrant le contexte plutôt qu'à supposer.)

**Tests attendus** (`UserArchivalServiceTest`) :
- Deux users, un inactif depuis 3 mois avec des scénarios/décisions actifs, un actif récemment :
  `archiveInactiveUsers()` évince bien les données du premier des deux moteurs, laisse le second
  intact, et positionne `archivedAt` uniquement sur le premier.
- Un user déjà `archivedAt != null` n'est pas re-traité (couvre le filtre `ArchivedAtIsNull` de la
  requête).
- Test de contrôleur pour `/api/admin/decision/archive` (patron du test existant pour `/snapshot` si
  un test dédié existe pour `DecisionScenarioSnapshotAdminController` — vérifier avant d'improviser un
  nouveau patron).

---

## Étape 5 — Hook de restauration à la reconnexion (`signinForm` uniquement)

**À faire**, dans `AuthController.authenticateUserForm(...)`, juste après la mise à jour de
`lastLogin` (étape 5) :
```java
userRepository.findById(userDetails.getId()).ifPresent(u -> {
    u.setLastLogin(Instant.now());

    if (u.getArchivedAt() != null) {
        restoreService.restoreOwner(ScenarioOwner.of(u));
        u.setArchivedAt(null);
        logger.info("User '" + u.getUsername() + "' restauré depuis l'archive à la reconnexion");
    }

    userRepository.save(u);
});
```
Injecter `DecisionScenarioRestoreService restoreService` dans `AuthController` (nouvelle dépendance,
même style d'injection `@Autowired` que les autres champs de ce controller).

**Tests attendus** : test de contrôleur `signinForm` (même patron qu'à l'étape 5) — un `User` avec
`archivedAt` renseigné : après login, `restoreService.restoreOwner(...)` (mocké) est appelé avec
l'owner correspondant, et `archivedAt` repasse à `null` en base. Un `User` avec `archivedAt == null` :
`restoreOwner(...)` n'est **jamais** appelé (couvre le coût quasi nul pour le cas normal — l'essentiel
de ce qui justifie de ne pas avoir dupliqué ce hook sur `AuthTokenFilter`, cf. décision 4 en tête de ce
prompt).

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline obtenue après l'étape 5),
détail de tout échec, et signaler explicitement :
- si l'extraction de `DecisionScenarioRestoreRunner` vers `DecisionScenarioRestoreService` (étape 3) a
  nécessité de dévier de la logique existante sur un point quelconque (départage des créations
  concurrentes, UUID déterministe, fallback de scope) — ces trois mécanismes doivent survivre à
  l'extraction sans changement de comportement, à vérifier explicitement, pas seulement en confiant ça
  à la compilation ;
- si `Map.values().removeIf(...)` (étape 2, `DecisionEngine.evictOwner`) s'est comporté comme attendu
  sur `ConcurrentHashMap` ou s'il a fallu une autre approche (ex. collecter les clés à retirer d'abord) ;
- tout autre écart pris par rapport à ce prompt.

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 6 → ✅). L'étape 7
(orchestrateur) reste bloquée par l'étape 2 (pas faite, cf. échange du 2026-08-13) — à traiter avant de
pouvoir rédiger le prompt de l'étape 7 dans de bonnes conditions.
