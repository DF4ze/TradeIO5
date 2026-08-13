# Prompt d'implémentation — Décision, Palier 3, Étape 3 (extensions de modèle pour la persistance)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 3** de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` — extensions de modèle pures,
préparant les DTO/entités pour la persistance (étape 4), **sans** implémenter la mécanique de
sauvegarde/rejeu elle-même. Référence : `docs/etudes/etude-branchement-persistance-decision-engine.md`
§C ("Ce qui a été vérifié en creusant") + §E point 4. **Prérequis** : Étape 1 mergée (recommandé, pas
strictement bloquant selon la roadmap — vérifié : aucune des trois extensions ci-dessous ne dépend du
refactor B3). Étape 2 n'est pas un prérequis dur non plus.

**Décisions prises avec Clem le 2026-08-13, avant rédaction de ce prompt** (points que l'étude laissait
ouverts — à ne pas retrancher) :
1. **`DecisionCreatedCause`** : étendre le record existant plutôt que créer une cause dédiée — *à
   condition que l'ajout reste le même sens métier* (le principe posé par Clem). Ci-dessous, étape 1,
   la justification de pourquoi ce cas rentre dans ce principe.
2. **Reconstruction de `DefaultMarketScenario`** : un constructeur surchargé (pas une fabrique statique
   nommée).
3. **Bug `JpaEventStore.toDomain()`** (switch sans cas `DECISION`) : **explicitement reporté à
   l'étape 4** — ce lot ne touche pas `JpaEventStore`, ni le chemin de désérialisation en général,
   uniquement les DTO/entités en écriture.
4. **Champ `scope` sur `ScenarioEvent`** : nullable, sans migration des lignes déjà persistées dans
   `scenario_events` — ce lot ne traite pas la compatibilité avec l'historique existant, seulement le
   modèle à partir de maintenant.

**Ce que ce lot n'est PAS** : pas la mécanique de snapshot/rejeu (étape 4) ; pas le fix du switch
`JpaEventStore.toDomain()` (décision ci-dessus, reporté) ; pas une reconstruction de `Decision` avec
un id fidèle à l'original — l'étude (§C4) a déjà tranché que la perte d'une `Decision` au redémarrage
est acceptée pour l'instant (cycle de vie court, contrairement à un `MarketScenario` qui peut vivre
plusieurs jours/semaines) ; ce lot n'y touche donc pas, au-delà de permettre à `DecisionCreatedCause` de
transporter les `ActionStep` d'origine (utile pour l'audit même sans rejeu complet de `Decision`).

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-branchement-persistance-decision-engine.md` — §C en entier (les trois trous
   identifiés : `DecisionEvent` incomplet, `MarketScenario` non reconstructible, `ScenarioEvent` sans
   `scope`) et §E point 4.
2. `model/dto/event/decision/DecisionCreatedCause.java` — le record actuel (`decisionId`, `reason`
   seulement) et `DecisionEventCause.java` — l'interface scellée listant les 5 causes existantes.
3. `service/tree/decision/DecisionEngine.java` — `onScenarioEvent(...)` (construit et publie le
   `DecisionEvent` avec `DecisionCreatedCause`) et `createDecision(...)` (construit le `Decision` à
   partir d'un `DecisionCandidate`, steps déjà connus à cet endroit).
4. `service/tree/decision/Decision.java` — noter que `steps` (`List<ActionStep>`) n'a **pas**
   d'accesseur Lombok `@Getter` aujourd'hui (contrairement à `id`/`type`/`symbol`/`owner`/`snapshot`/
   `status`) — à ajouter pour permettre le câblage de ce lot.
5. `service/tree/scenario/DefaultMarketScenario.java` — le constructeur actuel (ligne 66-76) :
   `id` généré par `generateScenarioId()` à l'intérieur, non paramétrable ; `state` toujours
   `ScenarioStatus.INITIAL` neuf.
6. `model/dto/tree/scenario/ScenarioState.java` — déjà muni d'un constructeur de copie
   `ScenarioState(ScenarioState state, Instant now)` (attention : celui-ci réinitialise `createdAt`/
   `lastUpdated` à `now`, ce n'est **pas** ce qu'il faut pour une reconstruction fidèle qui doit
   préserver les timestamps d'origine — à ne pas réutiliser tel quel pour ce lot, cf. étape 2
   ci-dessous).
7. `model/dto/event/ScenarioEvent.java` — champs actuels (`owner`, `symbol`, `scenarioType`, pas de
   `scope`) et le constructeur qui les dérive de `MarketScenario scenario`.
8. `service/tree/scenario/MarketScenario.java` — confirmer que `getScope()` existe déjà sur
   l'interface (utilisé par `DefaultScenarioEngine.keyOf(...)`) — c'est la source à utiliser pour
   peupler le nouveau champ, pas une nouvelle donnée à faire circuler.
9. `model/entity/tree/scenario/ScenarioEventEntity.java` — confirme que `scenario_events` est déjà une
   table active en prod (`causeJson`/`beforeJson`/`afterJson` en JSON libre) — context uniquement, ce
   lot ne touche pas cette entité ni son mapping (reporté étape 4 avec le fix `JpaEventStore`).
10. Tests à prendre comme patron : `service/tree/decision/DecisionEngineTest.java` (construction
    `DecisionCandidate`/`Decision` directe), `service/tree/scenario/DefaultScenarioEngineUnitTest.java`
    / `DefaultMarketScenarioTest.java` (construction `ScenarioDefinition`/`DefaultMarketScenario`
    directe).

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher à `JpaEventStore.java`,
`InMemoryEventStore.java`, `ScenarioEventEntity.java`, ni à aucun chemin de désérialisation/rejeu —
tout ça reste explicitement pour l'étape 4 (décision actée ci-dessus avec Clem).

---

## Étape 1 — `DecisionCreatedCause` transporte les `ActionStep` d'origine

**Justification du choix "étendre plutôt que créer"** (principe posé par Clem : même sens métier →
étendre, sens différent → nouvelle cause) : `DecisionCreatedCause` documente déjà "ce qui a été créé"
via `decisionId` — ajouter les `ActionStep` de cette même création ne change pas *pourquoi* l'événement
existe (toujours : "une Decision a été créée, en voici l'identité et la raison"), ça complète juste le
contenu de la même création avec ce qu'elle contenait réellement. Ce n'est pas un nouveau fait
métier distinct (contrairement à, par exemple, une cause qui documenterait un rejet ou une
modification ultérieure — ça, ce serait un sens différent). D'où : **extension en place**.

**À faire** :

1. Étendre le record :
   ```java
   public record DecisionCreatedCause(
           String decisionId,
           String reason,
           java.util.List<ActionStep> actionSteps
   ) implements DecisionEventCause {}
   ```
   (import `fr.ses10doigts.tradeIO5.model.dto.tree.decision.ActionStep`.)
2. `Decision.java` : ajouter `@Getter` sur le champ `steps` (Lombok générera `getSteps()`, cohérent
   avec le style déjà utilisé sur les autres champs de cette classe).
3. `DecisionEngine.onScenarioEvent(...)` : mettre à jour l'appel de construction pour passer les steps
   réels de la décision qui vient d'être créée :
   ```java
   eventBus.publish(new DecisionEvent(
           decision,
           DecisionEventType.DECISION_CREATED,
           new DecisionCreatedCause(
                   decision.getId(),
                   "On Scenario Event",
                   decision.getSteps()
           ),
           clock.now()
   ));
   ```
4. Mettre à jour tout autre point de construction de `DecisionCreatedCause` dans les tests existants
   (rechercher `new DecisionCreatedCause(` avant de conclure qu'il n'y en a qu'un — au moment de la
   rédaction de ce prompt, seul `DecisionEngine.onScenarioEvent` en construit un en code de
   production).

**Tests attendus** :
- Mettre à jour `DecisionEngineTest.onScenarioEvent_buyIntentProducesEnterDecision` (ou équivalent) pour
  vérifier que le `DecisionEvent` capturé porte bien une `DecisionCreatedCause` dont `actionSteps()`
  correspond exactement aux steps du `Decision` créé (même taille, mêmes `stepId`/`executionAction`/
  `quantity`/`walletId`) — pas juste "non vide", une vraie égalité de contenu pour couvrir le risque
  d'un mauvais mapping.
- Un test unitaire dédié, direct, sans passer par tout le flux événementiel : construire une
  `DecisionCreatedCause` avec une liste d'`ActionStep`, vérifier `actionSteps()` retourne bien la
  même liste (test trivial mais utile pour documenter l'intention du champ).

---

## Étape 2 — `DefaultMarketScenario` : constructeur de reconstruction

**Contexte** : le constructeur existant génère toujours un `id` neuf et un `ScenarioState` à
`ScenarioStatus.INITIAL`. Une reconstruction fidèle (à partir d'un `ScenarioEvent.after` déjà persisté,
cf. étude §C "bonne nouvelle partielle" — `after` porte déjà un `ScenarioState` complet) a besoin de
réinjecter l'`id` et le `ScenarioState` d'origine **tels quels**, sans passer par le constructeur de
copie `ScenarioState(ScenarioState, Instant now)` qui réécrit `createdAt`/`lastUpdated` à `now` — un
comportement voulu pour d'autres usages (`enrichFrom(...)`, à vérifier dans le code environnant avant
d'y toucher, mais **hors scope de ce lot**), pas pour une reconstruction qui doit au contraire préserver
les timestamps d'origine.

**À faire** :

1. Ajouter un second constructeur à `DefaultMarketScenario` :
   ```java
   /**
    * Reconstruction depuis un état déjà persisté (Palier 3, étape 3 — préparation de l'étape 4).
    * Contrairement au constructeur principal, ne génère ni nouvel {@code id} ni nouvel état initial :
    * réinjecte tels quels ceux fournis, pour que l'objet reconstruit conserve son identité d'origine
    * ({@code loadByTargetId}, déduplication {@code proposedScenarioIds}) et son historique de dates.
    * N'appelle PAS {@link #DefaultMarketScenario(ScenarioDefinition, EventBus)} : ce dernier générerait
    * un nouvel id et un nouvel état, exactement ce qu'on veut éviter ici.
    */
   public DefaultMarketScenario(String id, ScenarioState state, ScenarioDefinition definition, EventBus eventBus) {
       this.id = id;
       this.state = state;
       this.owner = definition.owner();
       this.symbol = definition.symbol();
       this.scope = definition.scope();
       this.eventBus = eventBus;
   }
   ```
   Vérifier que `id`/`state` ne sont pas `final` d'une manière qui empêcherait cette double
   assignation dans deux constructeurs différents (Java l'autorise nativement tant que chaque chemin
   assigne une fois — à confirmer en compilant, pas à supposer).
2. Ne pas construire, dans ce lot, le point d'appel qui utiliserait réellement ce constructeur
   (lecture depuis `JpaEventStore`, repeuplement de la map `scenarios`) — c'est le travail de l'étape 4,
   explicitement reporté (cf. décisions actées en tête de ce prompt).

**Tests attendus** (`DefaultMarketScenarioTest` ou nouveau test dédié) :
- Construire un `ScenarioState` avec des valeurs distinctives (`status = VALIDATED`, `confidence =
  0.87`, `createdAt`/`lastUpdated` fixés à des instants différents et différents de "maintenant"),
  construire un `DefaultMarketScenario` via le nouveau constructeur avec un `id` connu à l'avance ;
  vérifier que `getId()` retourne exactement cet id (pas un nouveau généré) et que `getState()` renvoie
  **la même instance ou une valeur strictement égale** (à choisir selon le style déjà utilisé dans le
  projet pour ce genre de vérification — comparer champ par champ si `ScenarioState` n'a pas
  d'`equals()` généré, ce qui est le cas ici puisque c'est une classe `@Data` mutable, pas un record —
  `@Data` génère bien `equals()`/`hashCode()` sur les champs, donc `assertEquals` direct doit
  fonctionner, à vérifier en lisant l'annotation avant de choisir la méthode d'assertion).
- Vérifier que `createdAt`/`lastUpdated` du `ScenarioState` reconstruit ne sont **pas** réécrits à
  l'instant de l'appel — couvre spécifiquement la différence avec le constructeur de copie
  `ScenarioState(ScenarioState, Instant now)` mentionné en lecture préalable, qui aurait ce défaut s'il
  avait été réutilisé par erreur ici.

---

## Étape 3 — `ScenarioEvent` gagne un champ `scope`

**Contexte** : `ScenarioKey` (l'identité utilisée par la map vivante de `DefaultScenarioEngine`) inclut
déjà `OpinionScope scope` depuis `docs/etudes/etude-extension-risk-macro-external.md` §5.2, mais
`ScenarioEvent` (ce qui est réellement publié/persisté) ne le porte pas — un rejeu ne pourrait pas
reconstruire une `ScenarioKey` fidèle sans cette donnée. Décision actée avec Clem : champ **nullable**,
sans traitement de compatibilité pour les lignes déjà persistées dans `scenario_events` (hors scope de
ce lot).

**À faire** :

1. Ajouter le champ à `ScenarioEvent.java` :
   ```java
   private final OpinionScope scope;
   ```
   (import `fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope`, déjà utilisé ailleurs
   dans ce fichier indirectement via `ScenarioEventCause`/`MarketScenario` — vérifier l'import exact
   nécessaire.) Le champ Java lui-même n'a pas besoin d'être `Optional<OpinionScope>` : `@Getter`
   (Lombok, déjà en tête de classe) suffit à exposer un accesseur qui peut renvoyer `null` pour les
   instances construites sans cette donnée — cohérent avec le choix "nullable, pas de migration".
2. Dans le constructeur `ScenarioEvent(MarketScenario scenario, ...)`, peupler le champ depuis la
   source déjà existante :
   ```java
   scope = scenario.getScope();
   ```
   (confirmer que `MarketScenario.getScope()` retourne bien un `OpinionScope` et non un `Optional<
   OpinionScope>` — lecture préalable point 8 — pour caler exactement le type du nouveau champ dessus,
   pas l'inverse.)

**Tests attendus** :
- Un test existant (`DefaultScenarioEngineUnitTest` ou équivalent) qui capture déjà un `ScenarioEvent`
  publié : étendre l'assertion pour vérifier que `event.getScope()` correspond au `scope` du scénario
  d'origine (ex. `OpinionScope.LOCAL` dans la plupart des tests existants).
- Un test dédié si aucun test existant ne capture facilement un `ScenarioEvent` réel : construire un
  `MarketScenario` avec un `scope` connu (`OpinionScope.EXTERNAL` par exemple, pour bien distinguer de
  la valeur par défaut `LOCAL` utilisée ailleurs et détecter un mapping resté figé), déclencher la
  publication d'un `ScenarioEvent` (n'importe quelle méthode qui en publie un — `cleanup(...)` est la
  plus simple à déclencher isolément), vérifier `getScope()` sur l'event capturé.

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline obtenue après l'étape 2 —
à relever au moment de lancer ce lot, pas à deviner ici puisque l'étape 2 n'est pas encore mergée au
moment de la rédaction de ce prompt), détail de tout échec, et signaler explicitement :
- si l'ajout du second constructeur à `DefaultMarketScenario` (étape 2) s'est heurté à un problème de
  double assignation `final` non anticipé ;
- si `MarketScenario.getScope()` s'est avéré retourner autre chose qu'un `OpinionScope` nu (étape 3,
  point 2) — ajuster le type du champ en conséquence plutôt que de forcer un cast.

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 3 → ✅) avant de rédiger le
prompt de l'étape 4 (persistance — photo quotidienne + rejeu delta + restauration, qui réutilisera les
trois extensions de ce lot, plus le fix `JpaEventStore.toDomain()` reporté ici) — la granularité exacte
de la table de snapshot reste un point à trancher avec Clem avant de rédiger ce prompt-là, comme déjà
noté dans la roadmap.
