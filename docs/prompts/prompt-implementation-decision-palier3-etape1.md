# Prompt d'implémentation — Décision, Palier 3, Étape 1 (branchement — moteur unique partagé, option B3)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre l'**Étape 1** du **Palier 3**
(`docs/prompts/prompt-implementation-decision-palier3-roadmap.md`), la seule étape non bloquée par
une autre et point d'entrée obligatoire de tout le palier. Décision actée par Clem le 2026-08-12 :
`docs/etudes/etude-branchement-persistance-decision-engine.md` §E point 1 — **option B3 retenue**
("tant pis pour la lourdeur du refacto"). **Prérequis** : Palier 1
(`docs/prompts/prompt-implementation-decision-palier1.md`) et Palier 2
(`docs/prompts/prompt-implementation-decision-palier2.md`) mergés — baseline connue : 485 tests au
2026-08-11 avant ce lot.

**Ce que ce lot n'est PAS** (à ne pas faire ici, même si la tentation existe en touchant ce code) :
- Pas l'orchestrateur qui fournira réellement l'owner à chaque appel du moteur partagé en production
  (étape 6 du palier, bloquée par celle-ci). Après ce lot, `DefaultScenarioEngine`/`DecisionEngine`
  deviennent des beans Spring singletons valides et injectables, mais **aucun appelant de production
  ne les invoque encore** (confirmé en code : `TreeAnalysisFacade`/`TreeAnalysisMcpTools` ne
  référencent ni l'un ni l'autre aujourd'hui). Les beans existent, prêts pour l'étape 6 — ce lot ne
  construit pas ce qui les appellera.
- Pas la persistance/le rejeu d'état (étapes 2 et 3), pas la détection de connexion utilisateur
  (étape 4), pas l'archivage sur inactivité (étape 5), pas le calendrier macro (étape 7).
- Pas de construction d'une "passerelle de visibilité `SystemOwner`" : §E point 2 de l'étude est
  explicite — cette question devient **sans objet** avec B3, résolue nativement par le refactor
  ci-dessous (une seule map partagée, filtrée à la lecture par owner). Rien à ajouter exprès ; ce lot
  demande seulement de **vérifier** que c'est bien le cas (cf. test dédié étape 1 ci-dessous), pas de
  bâtir un mécanisme supplémentaire.
- Pas de correction du bug `JpaEventStore.toDomain()` (switch sans cas `DECISION`) ni d'extension de
  `DecisionEvent`/`ScenarioEvent` — ce sont des sujets de persistance (étape 2/3, §C de l'étude), pas
  de branchement.
- Pas de changement du contenu métier de `onMarketOpinion`/`collectActionIntents`/`cleanup`
  au-delà de ce qui est strictement nécessaire pour retirer la dépendance à un owner fixé au
  constructeur — ce lot est un refactor de branchement, pas une révision de l'algorithmie décisionnelle.

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-branchement-persistance-decision-engine.md` — §A en entier (constat, notamment
   A.1 "l'owner est fixé au constructeur", A.2 "pourquoi une annotation seule est impossible", A.3
   "`OpinionEvent` sans owner : confirmé volontaire", A.4 "le mécanisme de visibilité `SystemOwner`
   est aujourd'hui inerte", A.5 "`EventBus` singleton partagé"), §B3 (l'option retenue, avantages/
   inconvénients), §E points 1 et 2 (la décision actée).
2. `service/tree/scenario/ScenarioEngine.java` — l'interface : remarquer que `getActiveScenarios(...)`
   et `collectActionIntents(...)` prennent **déjà** un `ScenarioOwner owner` en paramètre d'appel
   (préexistant, pas à ajouter) ; seul `onMarketOpinion(...)` (owner porté par `ScenarioContext`,
   déjà présent aussi) et l'auto-abonnement `onOpinionEvent` restent à traiter.
3. `service/tree/scenario/DefaultScenarioEngine.java` — en particulier le constructeur (ligne 53),
   `onOpinionEvent(...)` (ligne 62, construit son contexte avec `this.owner`), et le bug ligne 115
   (`collectActionIntents(owner, clock.now())` utilise le champ fixé au lieu de l'owner du contexte
   reçu en paramètre — à corriger dans ce lot, cf. étape 1 ci-dessous).
4. `service/tree/decision/DecisionEngine.java` — en particulier le constructeur (ligne 48),
   `onScenarioEvent(...)` (ligne 59, filtre `!owner.isVisible(event.getOwner())`), et
   `isUnanimousAcrossScopes(...)` (ligne 100-114, appelle `scenarioEngine.getActiveScenarios(owner,
   ...)` avec le champ fixé). Noter que `ScenarioEvent` porte déjà l'owner du scénario d'origine
   (`event.getOwner()`) — contrairement à `OpinionEvent`, aucun changement de signature n'est requis
   ici pour que l'abonnement au bus continue à fonctionner sous singleton.
5. `model/dto/tree/scenario/ScenarioOwner.java` — `of(User)`/`asUserId()` (Palier 2), `isVisible(...)`.
6. `service/tree/event/engine/EventBus.java` — `@Component` Spring, `subscribe`/`unsubscribe`,
   singleton partagé par toute l'application (cf. étude §A.5).
7. `service/market/SystemDomainClock.java`/`DomainClock.java` — seule implémentation de production,
   déjà `@Component`, sans état par owner (source de temps globale) : reste injectable tel quel, pas
   concerné par ce refactor.
8. `service/tree/scenario/factory/ScenarioFactory.java` — déjà correct : construit systématiquement
   sa `ScenarioDefinition` avec `context.owner()`, jamais un champ d'instance. Rien à changer ici,
   cité pour confirmer qu'aucun autre point caché ne dépend d'un owner fixé.
9. Tests à mettre à jour (patron déjà en place, à adapter aux nouvelles signatures) :
   `service/tree/scenario/DefaultScenarioEngineUnitTest.java`,
   `service/tree/scenario/ScenarioEngineIntegrationTest.java`,
   `service/tree/scenario/ScenarioOwnerIsolationDataJpaTest.java`,
   `service/tree/decision/DecisionEngineTest.java`,
   `service/tree/decision/MultiUserIsolationIntegrationTest.java`.
10. `service/market/dataset/MarketDatasetEngineSpringTest.java` — patron pour le test de câblage
    Spring à ajouter (`@SpringBootTest` + `@Autowired`, cf. étape 3 ci-dessous).

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher à `ScenarioFactory`,
`DefaultMarketScenario`, `ScenarioContext`, `ScenarioKey`, `JpaEventStore`/`InMemoryEventStore` — hors
scope de ce lot.

---

## Étape 1 — `DefaultScenarioEngine` : retirer l'owner du constructeur

**Contexte** : `onMarketOpinion(...)` (le point d'entrée métier réel, déjà appelé avec un owner porté
par `ScenarioContext` dans tous les tests existants) fonctionne déjà correctement pour un moteur
partagé — **sauf** un bug précis, ligne 115 : `collectActionIntents(owner, clock.now())` lit le champ
d'instance fixé au lieu de `context.owner()` reçu en paramètre. Ce bug est resté invisible jusqu'ici
parce que dans tous les tests actuels, `context.owner()` et l'owner du constructeur sont toujours la
même valeur — un moteur partagé sous B3 l'exposerait immédiatement (deux appels avec des owners
différents sur la même instance produiraient des intents pour le mauvais owner).

Le vrai obstacle structurel est ailleurs : le constructeur fait aussi
`eventBus.subscribe(OpinionEvent.class, this::onOpinionEvent)`, et `onOpinionEvent(...)` construit son
`ScenarioContext` avec `this.owner` (le champ fixé). Or `OpinionEvent` ne porte **volontairement** pas
d'owner (étude §A.3 — les Opinions sont des lectures de marché non personnalisées, publiées une fois
par actif, jamais par utilisateur). Sous un moteur singleton, il n'existe donc plus de valeur
sensée pour `this.owner` à cet endroit — la question "à quel(s) owner(s) faire réagir cette Opinion ?"
n'est **pas** une question que `DefaultScenarioEngine` peut trancher seul : c'est exactement le rôle du
futur orchestrateur (étape 6, §E point 6 de l'étude — "propagation vers chaque owner concerné"), pas de
ce lot. La bonne réponse ici n'est pas de deviner un owner de repli, mais de retirer l'auto-abonnement
du constructeur et de transformer `onOpinionEvent` en méthode utilitaire prenant l'owner en paramètre,
comme `onMarketOpinion`/`getActiveScenarios`/`collectActionIntents` le font déjà — pour que l'étape 6
puisse l'appeler explicitement, par owner, sans dupliquer la logique de conversion `OpinionEvent` →
`OpinionSignal`.

**À faire** :

1. Retirer le champ `private final ScenarioOwner owner;` et le paramètre correspondant du
   constructeur :
   ```java
   public DefaultScenarioEngine(DomainClock clock, EventBus eventBus) {
       this.clock = clock;
       this.eventBus = eventBus;
       // plus d'auto-abonnement à OpinionEvent ici (cf. point 3 ci-dessous)
   }
   ```
2. Retirer le paramètre `Set<String> symbols` du constructeur également ; le champ `symbols` devient
   une collection interne mutable initialisée vide :
   ```java
   private final Set<String> symbols = ConcurrentHashMap.newKeySet();
   ```
   Raison : `addSymbolSurvey(...)`/`removeSymbolSurvey(...)` existent déjà comme unique mécanisme de
   mutation de cette liste — lui donner une valeur initiale imposée au constructeur (et donc à
   résoudre quelque part pour le bean Spring) anticiperait sur une question explicitement non tranchée
   (roadmap Palier 3, "points encore à trancher" étape 6.a : quels actifs suivre, comment). Laisser
   `symbols` vide par défaut ne casse rien aujourd'hui : **vérifié en lisant le corps des méthodes**,
   `onMarketOpinion(...)` ne référence jamais `this.symbols` (seul `onOpinionEvent(...)` le lisait,
   pour un simple `log.debug(...)` qui ne filtrait déjà rien — cf. point 3), et
   `removeSymbolSurvey(...)` n'a d'effet que sur des scénarios déjà présents pour ce symbole. Les
   tests existants qui passaient `Set.of("BTC")` au constructeur n'ont donc **pas besoin** d'appeler
   `addSymbolSurvey("BTC")` en remplacement : aucune assertion existante n'en dépend (à vérifier lors
   de la mise à jour des tests, étape 4 ci-dessous, mais ne pas ajouter cet appel par précaution si
   rien ne l'exige).
3. Retirer `eventBus.subscribe(OpinionEvent.class, this::onOpinionEvent);` du constructeur. Changer la
   signature de `onOpinionEvent` pour recevoir l'owner en paramètre, sur le même patron que
   `onMarketOpinion`/`getActiveScenarios` :
   ```java
   public void onOpinionEvent(OpinionEvent event, ScenarioOwner owner) {
       // corps inchangé pour la partie conversion — noter au passage (sans corriger, hors scope) que
       // le bloc if/else if ci-dessous ne fait que logguer, il ne filtre jamais réellement le
       // traitement selon `symbols` : constat à signaler dans le rapport final, pas un bug à
       // corriger dans ce lot.
       OpinionSignal result = eventToOpinionSignal(event);
       ScenarioContext context = new ScenarioContext(owner, event.getSymbol(), clock, getGlobalScenarios(owner));
       onMarketOpinion(result, context);
   }
   ```
   Cette méthode n'est plus auto-déclenchée par le bus : elle reste disponible comme utilitaire
   explicite, prête à être appelée par le futur orchestrateur (étape 6) une fois qu'il saura,
   owner par owner, relayer une `OpinionEvent` reçue. Si en écrivant ce point la conversion
   `OpinionEvent` → `OpinionSignal` (`eventToOpinionSignal`) s'avère plus simple à garder `private`
   qu'à exposer, ne pas forcer sa visibilité au-delà de ce que `onOpinionEvent` public exige déjà.
4. Corriger le bug ligne 115 : remplacer `collectActionIntents(owner, clock.now())` par
   `collectActionIntents(context.owner(), context.clock().now())` dans `onMarketOpinion(...)` — utiliser
   l'owner et l'horloge du contexte reçu en paramètre, jamais un champ d'instance.
5. Ajouter `@Service` (import `org.springframework.stereotype.Service`) sur la classe. Avec un seul
   constructeur restant (`DomainClock`, `EventBus`), Spring peut l'instancier sans qualifier
   explicitement — les deux dépendances sont déjà des beans (`SystemDomainClock`, `EventBus`).

**Tests attendus** (mise à jour de `DefaultScenarioEngineUnitTest`/`ScenarioEngineIntegrationTest`/
`ScenarioOwnerIsolationDataJpaTest` — retirer l'owner et le `Set.of("BTC")` des appels à `new
DefaultScenarioEngine(...)` dans chacun, garder le reste du patron `@BeforeEach` inchangé) :
- Un test nouveau, dédié à ce lot (ex. dans `DefaultScenarioEngineUnitTest` ou un nouveau fichier
  `SharedScenarioEngineMultiOwnerTest`) : **une seule instance** de `DefaultScenarioEngine`, appeler
  `onMarketOpinion(...)` avec un `ScenarioContext` pour `ownerA` puis avec un `ScenarioContext` pour
  `ownerB` sur des symboles différents (ou le même symbole) ; vérifier que
  `getActiveScenarios(ownerA, ...)` ne renvoie que le(s) scénario(s) d'A et
  `getActiveScenarios(ownerB, ...)` ne renvoie que le(s) scénario(s) de B — preuve directe que
  l'owner est bien lu au niveau de l'appel, pas de l'instance (ce qu'aucun test existant ne pouvait
  prouver tant qu'une seule instance par owner existait).
- Un test nouveau vérifiant que le bug corrigé à l'étape 4 reste corrigé : construire un
  `ScenarioContext` pour `ownerB`, appeler `onMarketOpinion(opinion, contextB)` sur une instance
  partagée déjà utilisée pour `ownerA` juste avant ; vérifier qu'aucun `ActionIntent`/événement
  `ACTION_PROPOSED` n'est émis pour `ownerA` par cet appel (couvre spécifiquement la régression que le
  bug ligne 115 aurait produite avant correction — écrire ce test *avant* de corriger et vérifier
  qu'il échoue sur le code non corrigé, même patron que Palier 1 étape 1).
- Test de la visibilité `SystemOwner` (§A.4/§E point 2 de l'étude — "sans objet, résolu nativement" :
  **à vérifier, pas à supposer**) : sur une instance partagée, créer un scénario avec
  `ScenarioOwner.SYSTEM` comme owner (via un `ScenarioContext`/`OpinionSignal` appropriés, ou en
  insérant directement dans `engine.scenarios` comme le font déjà les tests d'isolation existants),
  puis vérifier que `getActiveScenarios(ownerA, ...)` **et** `getActiveScenarios(ownerB, ...)` le
  voient tous les deux (`isVisibleForOwner` traite déjà `SystemOwner` comme toujours visible — ce test
  prouve que ça fonctionne réellement de bout en bout sous singleton, pas seulement au niveau du
  filtre unitaire).

---

## Étape 2 — `DecisionEngine` : retirer l'owner du constructeur

**Contexte**, différence importante avec l'étape 1 : `ScenarioEvent` porte déjà l'owner du scénario
d'origine (`event.getOwner()`, vérifié dans `DecisionEvent`/`ScenarioEvent`), contrairement à
`OpinionEvent`. L'auto-abonnement `eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent)` du
constructeur **reste valide** sous singleton — il n'y a pas ici l'obstacle structurel de l'étape 1.
Seuls les usages du champ `this.owner` à l'intérieur du corps doivent être remplacés par l'owner porté
par l'événement traité.

**À faire** :

1. Retirer le champ `private final ScenarioOwner owner;` et le paramètre correspondant du
   constructeur :
   ```java
   public DecisionEngine(DomainClock clock, EventBus eventBus, ScenarioEngine scenarioEngine) {
       this.clock = clock;
       this.eventBus = eventBus;
       this.scenarioEngine = scenarioEngine;
       activeDecisions = new ConcurrentHashMap<>();
       eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent);
   }
   ```
2. Dans `onScenarioEvent(...)` : retirer la condition `!owner.isVisible(event.getOwner())` du premier
   `if` — elle n'a plus de sens pour une instance qui doit désormais traiter les événements de tous
   les owners (c'est tout le point de B3). Garder les deux autres conditions inchangées (`!=
   ACTION_PROPOSED`, `event.getSymbol().isEmpty()`).
3. Dans `isUnanimousAcrossScopes(ScenarioEvent event)` : remplacer
   `scenarioEngine.getActiveScenarios(owner, MAX_SCENARIO_AGE, clock.now())` par
   `scenarioEngine.getActiveScenarios(event.getOwner(), MAX_SCENARIO_AGE, clock.now())` — scoper la
   requête à l'owner de l'événement traité, jamais à un champ d'instance.
4. Ajouter `@Service` sur la classe. Le troisième paramètre du constructeur (`ScenarioEngine
   scenarioEngine`) sera résolu par Spring via l'unique implémentation `@Service` de l'interface
   (`DefaultScenarioEngine`, étape 1) — aucun `@Qualifier` nécessaire, une seule implémentation
   existe.

**Tests attendus** (mise à jour de `DecisionEngineTest`/`MultiUserIsolationIntegrationTest` — retirer
l'owner des appels à `new DecisionEngine(...)` ; `onScenarioEvent_buyIntentProducesEnterDecision`
utilise déjà un mock `getActiveScenarios(any(), any(), any())`, donc insensible au changement du
point 3 sans modification) :
- Un test nouveau, sur le même patron que le test multi-owner de l'étape 1 : une seule instance de
  `DecisionEngine` (branchée sur une seule instance partagée de `DefaultScenarioEngine`), publier un
  `ScenarioEvent(ACTION_PROPOSED, ...)` pour `ownerA` puis un autre pour `ownerB` sur le même bus ;
  vérifier que les deux `DecisionEvent` capturés portent bien `getOwner() == ownerA` et `== ownerB`
  respectivement (pas de mélange/écrasement par une seule Decision "générique").
- Vérifier que `MultiUserIsolationIntegrationTest.scenarioValidatedForUserA_neverVisibleForUserB`
  passe toujours après retrait de l'owner des deux constructeurs — c'est précisément le test que ce
  lot doit continuer à satisfaire, maintenant sur un couple d'instances réellement partageables entre
  owners (même si ce test particulier n'en construit qu'un seul couple, cf. patron déjà en place).

---

## Étape 3 — Vérifier le câblage Spring des deux beans

**Contexte** : après les étapes 1 et 2, `DefaultScenarioEngine`/`DecisionEngine` sont des `@Service`
valides, mais rien ne prouve encore que le contexte Spring démarre correctement avec eux (ordre
d'injection, absence de dépendance circulaire `ScenarioEngine` ↔ `DecisionEngine` — `DecisionEngine`
dépend de `ScenarioEngine`, jamais l'inverse, donc pas de cycle attendu, mais à confirmer par un test
plutôt qu'à supposer).

**À faire** : aucun code de production supplémentaire. Ajouter uniquement le test ci-dessous.

**Tests attendus** (nouveau fichier, ex. `service/tree/decision/DecisionEngineSpringWiringTest.java`,
patron `MarketDatasetEngineSpringTest` — `@SpringBootTest` + `@Autowired`) :
- Le contexte Spring démarre sans exception avec les deux beans présents.
- `@Autowired private ScenarioEngine scenarioEngine;` et `@Autowired private DecisionEngine
  decisionEngine;` sont non-null.
- Deux injections successives du même type (ex. deux champs `@Autowired ScenarioEngine` dans deux
  composants de test, ou une simple relecture du bean via `ApplicationContext.getBean(...)` deux fois)
  renvoient la **même instance** — preuve que le bean est bien un singleton Spring (scope par défaut),
  pas une nouvelle instance à chaque injection.

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Porter une attention particulière au premier démarrage du contexte Spring après l'ajout des deux
`@Service` : si l'injection échoue (dépendance manquante, ambiguïté sur `ScenarioEngine`), le signaler
explicitement — ce n'est pas un échec de test au sens strict mais bloquerait toute la suite si le
contexte Spring ne démarre pas.

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline de 485 tests au
2026-08-11), détail de tout échec, et signaler explicitement tout écart pris par rapport à ce prompt —
en particulier si `eventToOpinionSignal`/`onOpinionEvent` ont dû être exposés différemment que prévu
(point 3 de l'étape 1), ou si le test de visibilité `SystemOwner` révèle que ce point n'est en fait
**pas** résolu nativement comme l'étude le prévoyait (ce serait une découverte importante à remonter
avant de poursuivre, pas juste un test à ajuster).

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 1 → ✅) avant de rédiger le
prompt de l'étape suivante — l'étape 2 (extensions de modèle) et l'étape 4 (détection de connexion,
indépendante) sont toutes deux débloquées à partir d'ici, à discuter avec Clem laquelle rédiger
ensuite.
