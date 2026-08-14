# Étude — Branchement applicatif & persistance de l'état (Decision/Scenario Engine)

Document lié à `docs/etudes/etude-mecanique-decision-dca-intelligent.md` §12 points 1-2 ("bloquant
dur"), rédigé à partir de `docs/prompts/prompt-analyse-branchement-persistance-decision.md`. Analyse
pure — **aucun code de production n'a été modifié pour produire ce document**. Vérifié en code le
2026-08-11, pas supposé (même principe directeur que l'étude mère).

Les deux points sont traités ensemble parce qu'ils sont couplés : la façon dont on décide de
brancher `DefaultScenarioEngine`/`DecisionEngine` dans Spring détermine directement ce qu'il faut
persister et comment (cf. §A.4 et §A.6 ci-dessous, qui affinent ce couplage au-delà de ce que
l'étude mère avait déjà identifié).

---

## A. Constat reconfirmé

### A.1 — L'owner est fixé au constructeur, pas un paramètre d'appel

Vérifié dans les deux classes :

```java
// DefaultScenarioEngine.java:53
public DefaultScenarioEngine(ScenarioOwner owner, DomainClock clock, Set<String> symbols, EventBus eventBus) {
    this.owner = owner;   // final, jamais réassigné
    ...
    eventBus.subscribe(OpinionEvent.class, this::onOpinionEvent);
}
```

```java
// DecisionEngine.java:48
public DecisionEngine(ScenarioOwner owner, DomainClock clock, EventBus eventBus, ScenarioEngine scenarioEngine) {
    this.owner = owner;   // final, jamais réassigné
    ...
    eventBus.subscribe(ScenarioEvent.class, this::onScenarioEvent);
}
```

`onOpinionEvent` construit systématiquement son `ScenarioContext` avec `this.owner` (jamais un owner
porté par l'`OpinionEvent`, qui n'en a d'ailleurs pas — cf. A.3). `onScenarioEvent` filtre via
`owner.isVisible(event.getOwner())` — il lit bien l'owner de l'event, mais uniquement pour décider
si **son propre owner fixe** doit réagir à cet event, jamais pour changer de contexte.

**Conséquence directe, confirmée** : une instance de `DefaultScenarioEngine`/`DecisionEngine` ne
peut traiter que les événements d'un seul owner (plus ceux de `SystemOwner`, visibles par tous via
`isVisible`). Le design actuel implique bien **une instance par owner actif**, pas un bean Spring
unique — l'implication que le prompt demandait de documenter est confirmée telle quelle.

### A.2 — Pourquoi "juste ajouter `@Service`" est littéralement impossible, pas seulement une mauvaise idée

Les deux constructeurs exigent un `ScenarioOwner` en paramètre. Un bean Spring `@Service`/`@Component`
a besoin que le conteneur puisse l'instancier sans argument métier connu au démarrage (ou via
injection d'autres beans, mais aucun bean `ScenarioOwner` unique n'a de sens ici — il y en a
potentiellement un par utilisateur). Ce n'est donc pas qu'une annotation seule serait insuffisante
sur le plan architectural : le code ne compile même pas dans cette hypothèse sans changer la
signature du constructeur. Toute option de branchement (§B) doit donc, au minimum, soit fournir cet
owner autrement qu'au constructeur (§B3), soit créer explicitement une instance par owner (§B1/§B2).

### A.3 — `OpinionEvent` sans owner : confirmé volontaire, pas un oubli

`OpinionEvent` (package `model.dto.event`) n'a aucun champ `owner`/`userId` — vérifié, seuls
`symbol`, `scope`, `majoritySignal`, `weightedSignal`, `confidence`, `score`, `sources`, `reason`,
`timestamp` sont portés. C'est cohérent avec la lecture de marché non personnalisée : chaque
instance de `DefaultScenarioEngine` (une par owner, cf. A.1) reçoit le **même** flux
`OpinionEvent` (diffusé à tous les abonnés du `EventBus`, cf. A.5) et le réinterprète selon son
propre owner. Rien dans le code ne suggère un oubli — c'est la seule lecture cohérente avec
l'existence de `ScenarioOwner.SYSTEM` et le fait que les Opinions sont déjà documentées comme des
lectures de marché globales (étude mère §7.2).

### A.4 — Découverte en creusant : le mécanisme de visibilité `SystemOwner` est aujourd'hui inerte, et le resterait sous B1/B2 tels quels

Point non demandé explicitement par le prompt mais qui change la donne pour §B — à vérifier
soi-même avant de choisir une option, pas à prendre pour acquis.

`ScenarioKey` (record) porte un `owner`, et `DefaultScenarioEngine.isVisibleForOwner`/
`getGlobalScenarios` filtrent la map interne `scenarios` par owner, avec un cas spécial pour
`SystemOwner` (toujours visible). Cette donnée a clairement été conçue pour qu'un scénario
`SystemOwner` (une lecture de marché globale, ex. un régime `CRASH` général) soit visible par
**tous** les owners qui consultent la map.

Mais en tracant le chemin réel du code : `scenarios.merge(keyOf(scenario), ...)` n'insère jamais
que les scénarios produits par `ScenarioFactory.create(opinion, enrichedContext, eventBus)`, où
`enrichedContext.owner()` vaut **toujours** `this.owner` (le champ fixé au constructeur, cf. A.1).
Autrement dit : **la map `scenarios` d'une instance donnée ne contient jamais que des scénarios de
son propre owner.** Le filtre `isVisibleForOwner`/le cas `SystemOwner` de `ScenarioKey` n'ont
aujourd'hui aucune occasion de s'exercer sur un scénario d'un *autre* owner que celui de
l'instance — c'est un mécanisme de filtrage qui ne filtre jamais rien de différent, tant qu'une
seule instance par owner existe.

Implication concrète pour §B : si on choisit B1 ou B2 (une instance par owner) **sans rien de
plus**, un futur scénario `SystemOwner` (ex. une pause macro globale, cf. étude mère §12 point 5)
créé par l'instance `SystemOwner` ne sera **jamais vu** par les instances `UserOwner`, puisque
chaque instance ne lit que sa propre map. Le mécanisme de visibilité cross-owner qui existe dans les
types (`ScenarioKey.owner`, `isVisibleForOwner`) resterait un vestige inerte, pas une fonctionnalité
active — sauf à ajouter une passerelle explicite (ex. injecter les scénarios de l'instance
`SystemOwner` dans le `ScenarioContext` de chaque instance `UserOwner`, ce qui n'existe pas
aujourd'hui et n'est gratuit dans aucune des options). Ce point est distinct du point 5 de l'étude
mère (qui questionnait si un scénario global *devrait* pouvoir être outrepassé par le curseur de
risque) : ici, le constat est plus basique — même *sans* vouloir l'outrepasser, un scénario global ne
circule aujourd'hui vers personne d'autre que son propre owner dans l'architecture multi-instance.

### A.5 — `EventBus` est un singleton Spring partagé : chaque instance créée s'abonne au même bus

`EventBus` (`service/tree/event/engine/EventBus.java`) est `@Component`, donc un bean Spring
unique pour toute l'application. Les deux constructeurs de moteur s'abonnent eux-mêmes
(`eventBus.subscribe(...)`) dès leur instanciation. Conséquence chiffrable pour §B : si N owners
ont chacun une instance `ScenarioEngine` + une instance `DecisionEngine` actives, **chaque**
`OpinionEvent` publié est livré aux N instances `ScenarioEngine` (fan-out O(N) par event), même si
la plupart ne concernent qu'un seul symbole/owner utile. C'est un coût réel à mettre dans la balance
mémoire/CPU de B1 vs B2 (§B), et un point de vigilance supplémentaire pour une éventuelle éviction
(§B1) : `EventBus.subscribe` n'a pas de mécanisme de nettoyage automatique — voir `unsubscribe`
(méthode qui existe déjà, ajoutée pour "les abonnements temporaires") qui devra être appelée
explicitement, sans quoi une instance "évincée" du côté registry continuerait de recevoir et traiter
des events en silence (fuite mémoire **et** traitement fantôme, pas juste une fuite mémoire passive).

### A.6 — `EventStoreRegistry` : précédent valide comme *patron*, mais lui-même inactif en prod aujourd'hui

Le prompt cite `EventStoreRegistry` comme "précédent direct et pertinent". Le patron
(`@Component` qui choisit une implémentation selon `ExecutionMode`) est effectivement réutilisable
sur le principe. Mais en remontant ses appelants réels : **`EventStoreRegistry.forMode(...)` n'est
appelé nulle part dans le code de production**, sauf par `EventLogger` — qui n'est lui-même **jamais
instancié** (pas de `@Component`, pas de `new EventLogger(...)` dans `src/main`). `EventStoreRegistry`
est donc, aujourd'hui, du code mort en production, malgré son statut de `@Component` actif.

Plus important : `JpaEventStore` et `InMemoryEventStore` s'abonnent chacun **indépendamment et
inconditionnellement** au même `EventBus` partagé via leur propre `@PostConstruct` (`bus.subscribe
(PersistableEvent.class, this::append)`). Aucun des deux ne passe par `EventStoreRegistry` pour
décider s'il doit écrire ou non selon le mode. **Résultat vérifié : les deux stores persistent
aujourd'hui tout `PersistableEvent` publié sur le bus, sans aucun routage par `ExecutionMode`.**
La précision du 2026-08-11 déjà actée dans l'étude mère ("`JpaEventStore` fonctionne réellement en
prod dès qu'un event est publié") reste exacte, mais il faut l'affiner : ce n'est pas parce que
`ExecutionMode`/`EventStoreRegistry` route correctement vers `jpaStore` — c'est parce que
`JpaEventStore` écrit **toujours**, indépendamment du mode, faute d'un point de routage réellement
branché. Implication directe pour §C : la garantie "un backtest n'écrit jamais dans la persistance
live" **n'est pas assurée aujourd'hui par ce mécanisme** — elle ne tient que parce qu'aucun code de
production ne déclenche actuellement de run de backtest à travers le `EventBus` partagé (seuls des
tests instancient un `EventBus` local via `new EventBus()`, jamais le bean Spring). Toute option de
branchement/persistance retenue devra donc **construire** cette garantie plutôt que supposer qu'elle
existe déjà via `ExecutionMode`.

---

## B. Options de branchement (point 1)

Préambule commun aux trois options : aucune ne peut se contenter d'une annotation Spring seule
(A.2). Aucune ne résout par elle-même la visibilité `SystemOwner` (A.4) — ce point est orthogonal
au choix ci-dessous et devra être tranché séparément si un scénario global doit un jour être
réellement partagé (question posée en §D).

### B1 — Registry Spring, création paresseuse (lazy) d'un couple par owner

Piste suggérée par le prompt, sur le patron d'`EventStoreRegistry` mais indexé par owner plutôt que
par `ExecutionMode` :

```
ScenarioDecisionEngineRegistry (@Component)
  Map<ScenarioOwner, EngineCouple> instances   // EngineCouple = (DefaultScenarioEngine, DecisionEngine)
  EngineCouple forOwner(ScenarioOwner owner)   // crée à la demande si absent
```

- **Déclencheur de création** : au premier besoin réel — par exemple le premier appel
  `TreeAnalysisFacade`/MCP authentifié pour cet owner, ou la première écriture dans
  `UserTradingSettings` (déjà persistée depuis le Palier 2). À trancher (§D) : quel signal exact.
- **Coût mémoire/CPU** : proportionnel aux owners *réellement* actifs, pas à la base utilisateur
  totale — le plus économe des trois si peu d'owners sont actifs simultanément. Coût marginal par
  instance : 2 `ConcurrentHashMap` + 2 abonnements permanents au bus partagé (fan-out, cf. A.5).
- **Piège technique confirmé (A.5)** : une éviction qui se contente de retirer l'entrée de la `Map`
  du registry sans appeler `eventBus.unsubscribe(...)` sur les deux instances laisserait celles-ci
  continuer à consommer et traiter des events indéfiniment (la lambda `this::onOpinionEvent` reste
  référencée par `EventBus.subscribers`) — fuite mémoire **et** traitement fantôme, pas une simple
  fuite passive. L'éviction doit explicitement désabonner les deux moteurs.
- **Politique d'éviction** : à définir (owner inactif depuis X jours ? sur quel signal — dernier
  `OpinionEvent` traité, dernière connexion utilisateur ? évincer au dernier accès ou sur cron ?).
  Aucune réponse évidente dans le code existant, question ouverte pour Clem (§D).
- **Compatible "scheduler reste postposé"** : oui. La création lazy et l'écoute d'events sont
  purement réactives à des `OpinionEvent`/`ScenarioEvent` déjà publiés par le chemin MCP existant
  (`get_opinion` → `TreeAnalysisFacade`) — aucune boucle périodique n'est requise pour que le
  branchement "existe". Le scheduler ne déciderait que du *rythme* auquel `get_opinion` est appelé
  automatiquement, question indépendante.
- **Compatible backtest (§C)** : oui, à condition explicite que les instances de backtest ne passent
  jamais par ce registry (instances jetables construites directement par le futur harnais de
  backtest, avec leur propre `EventBus`/`FixedDomainClock` — cf. A.6, cette isolation n'est pas
  acquise par défaut, elle doit être construite).

### B2 — Création éager de toutes les instances au démarrage

Piste également suggérée par le prompt :

- **Périmètre "actif"** : à définir — tous les `User` en base (le plus simple, potentiellement le
  plus coûteux si beaucoup d'inscrits jamais actifs) ? seulement ceux avec au moins un
  `Wallet`+`ApiCredential` (vérifiable dès aujourd'hui sans nouveau champ) ? un futur flag "DCA
  activé" (le plus précis, mais n'existe pas encore — nouveau champ à ajouter) ? Ces trois lectures
  sont réalistes, aucune n'est évidemment meilleure sans savoir combien d'utilisateurs seront
  inscrits sans jamais activer de DCA.
- **Coût** : mémoire + fan-out CPU (A.5) dès le démarrage pour *tous* les owners du périmètre
  retenu, y compris ceux inactifs depuis longtemps — coût constant, pas incrémental, contrairement à
  B1.
- **Avantage direct** : pas de code d'éviction à écrire, pas de risque de fuite par oubli
  d'`unsubscribe` (A.5) — toute la classe de bugs de B1 liée au cycle de vie disparaît simplement
  parce qu'aucune instance n'est jamais détruite en cours de route.
- **Compatible "scheduler reste postposé"** : oui, même raisonnement que B1 — la création éager ne
  déclenche aucun cycle automatique par elle-même, elle rend seulement les instances déjà prêtes à
  réagir passivement.
- **Compatible backtest** : identique à B1 sur ce point (même garantie à construire, pas acquise).

### B3 — Owner en paramètre d'exécution plutôt que figé au constructeur (option non suggérée par le prompt, déduite de A.4)

En creusant A.4, une lecture s'impose : `ScenarioKey`/`isVisibleForOwner` ont visiblement été conçus
pour un moteur **partagé**, où l'owner est une donnée de filtrage à la lecture, pas une propriété
figée de l'instance. Cette option consiste à aller au bout de cette lecture plutôt que de la
contourner :

- Un seul bean Spring `@Service` pour `ScenarioEngine`, un seul pour `DecisionEngine`, pour toute
  l'application — pas un par owner.
- `onOpinionEvent`/`onMarketOpinion`/`onScenarioEvent` (et les méthodes publiques de l'interface
  `ScenarioEngine`) reçoivent l'owner en paramètre d'appel plutôt que de lire `this.owner`. Les maps
  `scenarios`/`activeDecisions` restent uniques et partagées, déjà partitionnées par owner via
  `ScenarioKey` (qui porte déjà ce champ).
- **Avantage principal** : résout nativement A.4 — un seul espace de données, la visibilité
  `SystemOwner` fonctionne exactement comme les types le suggèrent, sans passerelle à construire.
  Un seul flux à persister/rejouer côté §C (au lieu de N flux séparés à recharger un par un au
  démarrage). Pas de registry ni de politique d'éviction d'instance à écrire.
- **Inconvénient principal** : refactor plus profond que B1/B2 — touche la signature publique de
  `ScenarioEngine`/`DecisionEngine` et donc tous les appelants, y compris les tests existants qui
  construisent aujourd'hui les moteurs avec un owner fixe au constructeur (`ScenarioEngineIntegrationTest`,
  `DefaultScenarioEngineUnitTest`, `DecisionEngineTest`, `MultiUserIsolationIntegrationTest`) — à
  réécrire, pas seulement à étendre. Qui fournit l'owner à chaque appel devient une question
  transverse (probablement déjà résolue côté authentification pour `TreeAnalysisFacade`, mais pas
  vérifié dans le cadre de ce document — hors périmètre du prompt).
- **Éviction mémoire déplacée, pas supprimée** : le problème de fuite d'A.5 disparaît (plus
  d'instance à désabonner), mais le problème d'accumulation de données pour des owners inactifs
  reste entier — cette fois côté contenu de la map (`ScenarioKey`→`MarketScenario` d'un utilisateur
  parti depuis des mois) plutôt que côté instance. Un cleanup par owner resterait à écrire, même
  logique que B1 mais sur les données plutôt que sur l'objet engine.
- **Compatible backtest** : au moins aussi simple à isoler que B1/B2 — un backtest instancierait son
  propre couple engine+bus jetable, séparé du bean Spring partagé, exactement comme les deux autres
  options.

**Précisions apportées le 2026-08-12, après le passage en implémentation de l'étape 1 de la roadmap**
(cf. `docs/prompts/prompt-implementation-decision-palier3-roadmap.md`, étape 2 ajoutée pour les
intégrer plutôt que de les considérer acquises rétroactivement) :
- Une bonne partie de l'interface `ScenarioEngine` prend déjà l'owner en paramètre d'appel
  (`getActiveScenarios`, `collectActionIntents`, `cleanup`) — seuls le constructeur et
  `onOpinionEvent`/`onMarketOpinion` restaient figés sur un owner unique. Le refactor réel est donc
  plus ciblé que redouté au moment d'écrire ce document.
- L'abonnement `eventBus.subscribe(OpinionEvent.class, this::onOpinionEvent)` fait au constructeur
  n'a plus de sens sous B3 : `OpinionEvent` ne porte pas d'owner (toujours volontaire, cf. §A.3), donc
  un moteur partagé ne peut pas savoir "pour qui" réagir via une simple diffusion sur le bus. Ce
  déclenchement doit être remplacé par un appel direct de méthode, fait explicitement par
  l'orchestrateur (§E pt 6) une fois par owner concerné — pas par une souscription passive.
- Corollaire : le mécanisme de "liste de surveillance" (`symbols`, `addSymbolSurvey`/
  `removeSymbolSurvey`) perd sa raison d'être — il servait à filtrer une diffusion large, or c'est
  désormais l'orchestrateur qui décide en amont quels appels déclencher.
- `DecisionEngine.isUnanimousAcrossScopes` (et tout site similaire) doit interroger
  `scenarioEngine.getActiveScenarios(event.getOwner(), ...)` plutôt que `this.owner` — un moteur de
  décision partagé n'a plus d'owner fixe à lui, et doit raisonner sur l'owner porté par l'événement
  qu'il traite.
- Le filtre `owner.isVisible(event.getOwner())` d'`onScenarioEvent`, qui servait à ignorer les
  événements d'un autre owner, devient inutile sous B3 : un composant partagé doit réagir à tous les
  owners, il n'y a plus de notion de "pas le mien" à ce niveau (`ScenarioOwner.isVisible` peut rester
  pertinent ailleurs, ex. autorisation côté API — question distincte, pas traitée ici).
- Reste ouvert : faut-il conserver la publication d'un `OpinionEvent` sur le bus uniquement à des fins
  d'audit/persistance (sans rôle de déclenchement) ? À trancher au moment d'écrire le prompt détaillé
  de l'étape 2 ou de l'étape 7 (orchestrateur).

### Synthèse comparative

| | B1 (registry lazy) | B2 (éager au démarrage) | B3 (singleton, owner en paramètre) |
|---|---|---|---|
| Coût mémoire/CPU | proportionnel aux owners actifs | constant, dès le démarrage, sur tout le périmètre retenu | un seul jeu de structures, coût lié au volume de scénarios/décisions vivants, pas au nombre d'owners |
| Risque de fuite (A.5) | oui si éviction mal faite (`unsubscribe` oublié) | non (aucune instance détruite) | non (pas d'instance à détruire), mais cleanup de données à écrire |
| Résout A.4 (visibilité SystemOwner) nativement | non | non | oui |
| Effort d'implémentation | modéré (registry + politique d'éviction à inventer) | faible (boucle de création au démarrage + définir "actif") | plus élevé (refactor de signature + tests existants) |
| Compatible scheduler postposé | oui | oui | oui |
| Compatible backtest | oui, garantie à construire dans les 3 cas (A.6) | idem | idem |

---

## C. Options de persistance (point 2)

### Ce qui a été vérifié en creusant, au-delà du bug déjà confirmé le 2026-08-11

Le bug `JpaEventStore.toDomain()` (switch sans cas `DECISION`) est reconfirmé tel quel : `EventType`
a bien `OPINION, SCENARIO, DECISION`, et le switch ne gère que les deux premiers, `default` levant
`IllegalArgumentException`. Mais en poussant plus loin sur la faisabilité réelle d'un rejeu complet
(demandé explicitement par le prompt, Étape C), deux trous supplémentaires, non documentés
jusqu'ici, changent l'ampleur du travail nécessaire pour l'option C1 :

- **`DecisionEvent` ne transporte pas les `ActionStep`.** `DecisionCreatedCause` (la cause portée par
  l'événement `DECISION_CREATED`) ne contient que `decisionId` et `reason` — ni l'action
  (BUY/SELL), ni la quantité, ni le `walletId`. Le `Decision` vivant est construit en mémoire à
  partir d'un `DecisionCandidate` complet (`DecisionEngine.createDecision`), **avant** que
  l'événement ne soit publié — et l'événement publié ensuite n'embarque pas ces données. Corriger le
  `switch` de `toDomain()` ne suffirait donc pas à rejouer un `Decision` fidèle : même désérialisé
  sans erreur, l'événement `DECISION_CREATED` ne contient pas de quoi reconstruire les `ActionStep`
  d'origine.
- **`MarketScenario`/`DefaultMarketScenario` n'a aucun point d'entrée pour être reconstruit depuis un
  état persisté.** Le constructeur `DefaultMarketScenario(ScenarioDefinition, EventBus)` initialise
  toujours un `ScenarioState` neuf (`ScenarioStatus.INITIAL`, confiance 0) — il n'existe pas de
  variante acceptant un `ScenarioState` existant. Plus subtil : `id` est **généré** dans le
  constructeur (`type+createdAt+UUID aléatoire`) et n'est ni paramétrable ni modifiable après coup —
  un rejeu qui recréerait l'objet perdrait donc son identité d'origine (`scenarioId`), cassant à la
  fois `loadByTargetId` pour les événements futurs de ce même scénario et la déduplication
  `proposedScenarioIds` de `DefaultScenarioEngine` (qui suit les scénarios par `id`).
- **Découverte le 2026-08-12, lors d'un tour d'horizon documentaire plus large : `ScenarioEvent` ne
  porte pas non plus de champ `scope`.** Or `ScenarioKey` (l'identité utilisée par la map vivante)
  inclut bien `OpinionScope` depuis la correction documentée dans
  `docs/etudes/etude-extension-risk-macro-external.md` §5.2 — nécessaire pour qu'un scénario `LOCAL`
  et un scénario `EXTERNAL` sur le même symbole ne se recouvrent plus silencieusement (bug qui existait
  avant cette correction). Sans ce champ sur l'événement persisté, un rejeu ne pourrait pas
  reconstruire une `ScenarioKey` fidèle. Troisième extension de modèle à prévoir, en plus des deux
  ci-dessus.
- **Bonne nouvelle partielle, à noter** : `ScenarioEvent` transporte déjà un `ScenarioState` complet
  dans son champ `after` (pas juste un delta) — à chaque mutation, l'événement publié contient
  l'état résultant complet (`scenarioType`, `status`, `signal`, `confidence`, `stable`,
  `lastUpdated`, `createdAt`). En théorie, reconstruire un scénario ne demanderait donc pas de
  rejouer tout l'historique événement par événement (contrairement à un event-sourcing "pur") : le
  dernier `ScenarioEvent` par `scenarioId` suffirait à connaître l'état à recharger. Mais faute
  d'un point d'entrée pour réinjecter ce `ScenarioState` (et cet `id`) dans un nouvel objet
  `DefaultMarketScenario`, cette information reste aujourd'hui inexploitable telle quelle — un
  constructeur/fabrique dédié à la reconstruction serait un prérequis minimal, pas un détail.

Ces deux trous (`DecisionEvent` incomplet, `MarketScenario` non reconstructible) n'étaient pas
encore quantifiés avant ce document — l'étude mère notait "à évaluer" ; la réponse, vérifiée en
code, est qu'un rejeu fidèle demande plus que corriger le `switch` : il demande d'étendre le
contenu des événements `DecisionEvent`/`DECISION_CREATED` et d'ajouter un chemin de reconstruction
explicite à `DefaultMarketScenario` (et probablement `Decision`, dont le constructeur exige déjà un
`DecisionSnapshot`+`List<ActionStep>` complets, cohérent en soi mais qui doit être alimenté par des
données que l'événement actuel ne porte pas).

### C1 — Rejeu complet depuis `JpaEventStore` (event sourcing)

- **Ce qu'il faut faire, au minimum, pour que ce soit fonctionnel** (constat ci-dessus, pas une
  liste d'implémentation — rappelé pour que le choix soit informé) : (a) corriger le `switch` de
  `toDomain()` (`EventType.DECISION` → `DecisionEvent.class`) — **décision du 2026-08-13 (avec Clem,
  avant rédaction du prompt de l'étape 3 de la roadmap Palier 3) : ce point (a) est explicitement
  reporté à l'étape 4 de la roadmap** (`docs/prompts/prompt-implementation-decision-palier3-roadmap.md`),
  pas traité par l'étape 3 (extensions de modèle pures — DTO/entités en écriture uniquement, aucun
  chemin de désérialisation/rejeu touché) ; (b) enrichir `DecisionCreatedCause`
  (ou une nouvelle cause dédiée) pour transporter les `ActionStep` d'origine ; (c) ajouter un
  chemin de reconstruction à `DefaultMarketScenario` acceptant un `ScenarioState`+`id` existants ;
  (d) écrire le point d'appel qui, au démarrage (ou à la création lazy d'une instance owner, cf.
  §B1), appelle `loadByTargetId`/une nouvelle méthode de lecture par owner et repeuple
  `scenarios`/`activeDecisions`.
- **Avantage** : réutilise une infra déjà écrite et déjà active en écriture en prod (A.6) — pas de
  nouvelle table, pas de nouveau mécanisme de sérialisation à inventer, juste à compléter l'existant.
  Cohérent avec l'esprit event-driven déjà en place dans tout le pipeline Opinion→Scenario→Decision.
- **Inconvénient** : le travail réel est plus large que "corriger un bug" (cf. constat ci-dessus) —
  c'est une extension de modèle (nouvelles données sur les événements), pas juste un fix de
  désérialisation. Coût de lecture au démarrage croissant avec l'historique d'un owner (mitigé par
  le fait que `ScenarioEvent.after` porte déjà l'état complet — ne nécessite qu'une lecture du
  *dernier* événement par entité, pas un rejeu séquentiel complet, si la reconstruction est bien
  implémentée sur cette base plutôt qu'en rejouant chaque delta).

### C2 — Snapshot direct périodique

- Persister l'état courant (`ScenarioState` par scénario actif, état minimal d'un `Decision`) dans
  une table dédiée, à chaque mutation ou à intervalle régulier ; charger le dernier snapshot au
  démarrage (ou à la création lazy).
- **Avantage** : simple, rapide à charger (une lecture, pas de reconstruction depuis un historique).
  Contourne complètement les deux trous identifiés en C1 (pas besoin d'étendre `DecisionEvent`, pas
  besoin d'un chemin de rejeu event-sourcing) — il suffit de sérialiser l'état déjà en mémoire tel
  quel.
- **Inconvénient** : duplique la représentation de l'état (une table snapshot en plus du log
  d'événements déjà existant et déjà actif en écriture, cf. A.6) — risque de divergence entre les
  deux si l'un est mis à jour sans l'autre. Perd la valeur d'audit/rejouabilité fine que l'event log
  offre déjà (utile pour du debug/analyse a posteriori, pas juste pour la persistance de service).

### C3 — Ne rien faire pour l'instant, assumer et documenter le risque

- Légitime tant que le point 1 (branchement) n'est pas en place : construire une persistance
  sophistiquée pour un état qui n'existe encore dans aucune instance tournant réellement en prod
  serait probablement prématuré.
- Condition de sortie explicite à documenter si retenue : à partir de quel volume d'utilisateurs
  réellement actifs, ou de quelle durée de vie réelle des scénarios une fois les seuils recalibrés
  (étude mère §7.3/§9 — `EXPIRATION_IDLE` reste un placeholder de 2h, la vraie valeur envisagée est
  de l'ordre de plusieurs jours à une semaine), la perte d'état à chaque redéploiement devient un
  problème opérationnel plutôt qu'un risque théorique.
- Compatible avec n'importe laquelle des options de §B — n'interfère pas avec le choix de
  branchement, juste avec la robustesse de l'état une fois branché.

### C4 — Option hybride, apparue en croisant B3 et les trous de C1

Si B3 (§B) est retenu, la persistance devient plus simple à raisonner : un seul flux
`ScenarioEvent`/`DecisionEvent` à suivre au lieu de N (un par owner). Dans ce cas, un hybride
"C1 partiel" devient réaliste à court terme : ne persister/rejouer que les `ScenarioEvent` (dont le
trou de reconstruction est plus petit — `ScenarioEvent.after` porte déjà l'état complet, cf.
ci-dessus) et traiter les `Decision` selon C3 (assumé, documenté) le temps que le point (b) ci-dessus
(enrichir `DecisionEvent`) soit fait séparément — une `Decision` perdue au redémarrage est
arguablement moins grave qu'un `MarketScenario` en cours de maturation perdu, puisque `Decision` a un
cycle de vie court (`CREATED`→`EXECUTED`/`ABORTED`) alors qu'un scénario d'accumulation est censé
« vivre en permanence sur plusieurs jours/semaines » (étude mère §7.3). À ne considérer que si B3 est
le choix retenu en §B — sous B1/B2, cette option n'apporte pas d'avantage particulier par rapport à
traiter C1 comme un bloc.

### Contrainte transverse — isolation backtest, dans les 4 options

Rappel du constat A.6 : cette garantie n'est **pas** acquise aujourd'hui par
`EventStoreRegistry`/`ExecutionMode` (code mort en prod, les deux stores écrivent inconditionnellement
dès qu'un event passe sur le bus Spring partagé). Concrètement, pour chacune des options ci-dessus :
un run de backtest devra utiliser un `EventBus` **local**, jamais le bean Spring singleton, et des
instances d'engine jetables (jamais enregistrées dans le registry de §B1, jamais dans le périmètre
éager de §B2, jamais adressées avec un owner réel dans le service partagé de §B3). C'est une
construction à faire explicitement dans le futur harnais de backtest (étude mère §8, "rien de tout
cela n'est aujourd'hui assemblé en un harnais unique") — aucune des options C1-C4 ne l'obtient
gratuitement du seul fait de choisir `InMemoryEventStore` pour le mode `BACKTEST` : encore faut-il
que quelque chose route effectivement vers ce store et vers un bus séparé, ce qui n'existe pas
aujourd'hui.

---

## D. Questions fermées pour Clem

Chaque question s'appuie sur les briques posées ci-dessus — à lire dans l'ordre, pas isolément.

1. **Branchement (§B)** : entre B1 (registry lazy par owner), B2 (éager au démarrage) et B3
   (singleton partagé, owner en paramètre d'appel), quelle option retenir ? B3 résout nativement le
   trou de visibilité `SystemOwner` (§A.4) mais demande un refactor plus profond touchant les tests
   existants ; B1/B2 sont plus rapides à livrer mais laissent ce trou ouvert (documenté comme dette,
   à moins de construire une passerelle dédiée en plus).

2. **Si B1 ou B2 retenu, périmètre "owner actif"** : pour B2 (éager), faut-il couvrir tous les
   `User`, seulement ceux avec `Wallet`+`ApiCredential` (vérifiable dès aujourd'hui), ou attendre un
   futur flag "DCA activé" (à créer) ? Pour B1 (lazy), quel signal déclenche la création d'une
   instance (premier appel `get_opinion` authentifié pour cet owner ? première écriture dans
   `UserTradingSettings` ? autre) ?

3. **Visibilité `SystemOwner` (§A.4)** : indépendamment de l'option de branchement retenue, faut-il
   traiter dès ce palier la passerelle qui rendrait un scénario `SystemOwner` visible par toutes les
   instances `UserOwner` (nécessaire si B1/B2), ou l'assumer comme non résolu pour l'instant (le
   mécanisme resterait inerte comme aujourd'hui) en attendant une éventuelle bascule vers B3 plus
   tard ?

4. **Persistance (§C)** : entre C1 (rejeu event sourcing, le plus proche de l'infra déjà active en
   écriture mais qui demande d'étendre `DecisionEvent` et d'ajouter un chemin de reconstruction à
   `MarketScenario`), C2 (snapshot périodique, plus simple mais dupliquant la représentation de
   l'état), C3 (rien pour l'instant, assumé et documenté) et C4 (hybride, seulement pertinent si B3
   est retenu en §B) — laquelle privilégier pour ce palier ?

5. **Si C1 (ou C4 côté Scenario) retenu** : accord de principe pour étendre `DecisionCreatedCause`
   (ou une nouvelle cause dédiée) afin qu'elle transporte les `ActionStep` d'origine, et pour ajouter
   un constructeur/fabrique de reconstruction à `DefaultMarketScenario` acceptant un `ScenarioState`
   et un `id` existants ? Ce sont des extensions de modèle, pas de simples corrections — à valider
   avant de les considérer comme allant de soi dans le futur prompt d'implémentation.

6. **Éviction/cleanup (si B1, ou si B3 pour le nettoyage de données)** : quel signal détermine
   qu'un owner est "inactif" et peut être évincé (B1) ou nettoyé (B3) — dernière activité utilisateur
   authentifiée, dernier `OpinionEvent` traité pour cet owner, délai fixe, autre ? Aucune réponse
   évidente dans le code existant.

---

## E. Décisions retenues par Clem (2026-08-12)

Synthèse actée après plusieurs échanges de vulgarisation (schéma interactif inclus, partant du large
pour aller vers le détail technique — cf. [[dca-decision-study-collaborative-approach]]). À considérer
comme tranché pour la rédaction du prompt d'implémentation Palier 3.

1. **Branchement (§D question 1)** : **option B3 retenue** — moteur unique partagé, owner en
   paramètre d'appel plutôt que figé au constructeur. Assumé explicitement malgré le refactor plus
   profond ("tant pis pour la lourdeur du refacto") : cohérent avec la demande initiale du prompt
   d'analyse d'autoriser la remise en question de l'infra existante — application encore en version
   d'essai, marge de manœuvre architecturale volontairement gardée ouverte.
2. **Questions 2 et 3 du §D** (périmètre owner actif pour B1/B2, visibilité `SystemOwner`) : sans
   objet — réglées par le choix de B3 (résout nativement la visibilité, cf. §A.4/§B).
3. **Persistance (§D question 4)** : **photo (snapshot) indépendante par scénario/décision, cadence
   quotidienne (cron ~00:00-00:01 UTC), complétée par le rejeu des seuls événements postérieurs à la
   date de la photo** — variante affinée de C1/C2/C4 (checkpoint périodique + delta, patron classique
   de "snapshotting" en event sourcing, proposée par Clem). Ne sauvegarder que ce qui est encore actif
   (mêmes critères que le `cleanup()` existant — pas les scénarios déjà `INVALIDATED`/`EXPIRED`).
   Restauration au (re)démarrage dans l'ordre scénarios puis décisions, pour pouvoir vérifier la
   cohérence entre les deux (pas un rechargement immédiat après chaque écriture — clarifié après une
   ambiguïté de formulation).
4. **Question 5 du §D (extension du modèle)** : **accord de principe donné** — `DecisionEvent`/
   `DecisionCreatedCause` doivent être étendus pour transporter les `ActionStep` d'origine, et
   `DefaultMarketScenario` doit gagner un chemin de reconstruction acceptant un `ScenarioState`+`id`
   existants. Nécessaire pour que le rejeu du point 3 soit fidèle (cf. §C, trous confirmés en code).
   **Troisième trou découvert le 2026-08-12** lors d'un tour d'horizon documentaire plus large :
   `ScenarioEvent` doit aussi gagner un champ `scope`, absent aujourd'hui alors que `ScenarioKey`
   l'inclut désormais (cf. `docs/etudes/etude-extension-risk-macro-external.md`).
   **Précision du 2026-08-13** : ces trois extensions de modèle sont traitées par l'étape 3 de la
   roadmap Palier 3 ; le fix du `switch` de `JpaEventStore.toDomain()` (§C1 point (a) ci-dessus) et la
   compatibilité avec les lignes déjà persistées dans `scenario_events` (le nouveau champ `scope` y est
   nullable, sans migration) restent **explicitement pour l'étape 4** — l'étape 3 ne touche pas
   `JpaEventStore` ni aucun chemin de désérialisation/rejeu.
5. **Question 6 du §D (éviction/nettoyage)** : **archivage sur inactivité prolongée** (~2 mois, valeur
   indicative à ajuster) plutôt qu'une simple purge — dernière photo prise puis l'owner est retiré de
   la mémoire active ; à la reconnexion, l'absence de données actives déclenche une restauration
   depuis la photo archivée. Nécessite un signal de connexion utilisateur (champ "dernière connexion"
   mis à jour au login) — jugé simple à ajouter, pas creusé plus avant.
6. **Nouvelle brique identifiée pendant la discussion, absente du prompt d'analyse initial :
   l'orchestrateur qui fournit l'owner à chaque appel du moteur partagé (B3).** Deux étages à ne pas
   confondre :
   - **Calcul de l'Opinion** : une fois par actif suivi (BTC/ETH/PAXG), jamais par utilisateur —
     recalculer par owner serait un travail dupliqué et contredirait le caractère non personnalisé
     des Opinions (§A.3).
   - **Propagation vers chaque owner concerné** : un batch qui parcourt les couples User×Wallet×Asset
     et alimente le moteur partagé avec l'owner à chaque appel, déclenché par cron quotidien (même
     créneau que la photo, ~00:00-00:01 UTC), à la demande (bouton utilisateur), et à la connexion
     (patron Coinstats : sync automatique au login + relance manuelle possible).

   Ce batch est, de fait, une reformulation du **scheduler de génération de décision** resté
   "explicitement postposé" (`docs/suivi/point-avancement-2026-08-10.md` §6.2 pt 7) : sa conception
   doit exister dès que B3 est retenu (le moteur partagé ne peut pas fonctionner sans qu'un mécanisme
   lui fournisse l'owner), même si son activation réelle en prod (fréquence, mise en service) reste
   probablement une décision distincte à confirmer avant Palier 3.

   **Garde-fou retenu** : verrou/sémaphore par utilisateur — un seul refresh actif à la fois, nouveau
   refresh bloqué tant qu'un délai minimal (~1h, valeur indicative) ne s'est pas écoulé depuis le
   précédent, pour éviter qu'un cron + une relance manuelle + une connexion ne se marchent dessus et
   créent des décisions dupliquées.
7. **Idée retenue pour plus tard, pas pour ce palier** : vérification par "dry-run" de la
   restauration — rejouer une photo dans un environnement isolé et comparer le résultat à l'état
   vivant réel (pas à la photo elle-même, qui ne prouverait rien en cas de sauvegarde déjà corrompue)
   pour détecter une dérive de reconstruction avant qu'un vrai redémarrage ne la révèle. Complexité
   jugée trop élevée pour ce palier — à revisiter une fois le mécanisme de base (photo + rejeu delta)
   éprouvé en usage réel.

Reste à préciser au moment de rédiger le prompt Palier 3, pas avant : la valeur exacte du délai
d'archivage (2 mois de départ) et du verrou anti-doublon (1h de départ), et si l'activation réelle de
l'orchestrateur (vs sa simple conception) fait partie du périmètre de ce palier ou reste elle-même
postposée comme l'était le scheduler jusqu'ici.

---

**Les points 1 à 6 ci-dessus lèvent l'essentiel des questions fermées du §D — le prompt
d'implémentation Palier 3 peut être rédigé sur cette base.**

---

## F. Dette technique découverte à l'étape 4 (2026-08-13), non corrigée

Deux points, tracés ici pour mémoire mais **volontairement laissés tels quels** (décision explicite
de Clem le 2026-08-13 : "on laisse comme ça pour l'instant"). À reprendre si un futur besoin en
dépend directement — ne pas corriger "en passant" à l'occasion d'un autre lot sans en reparler.

### F.1 — La table `events` fonctionne en upsert, pas en append-only

`ScenarioEvent.id`/`DecisionEvent.id` sont calculés comme `"[Xxx]"+targetId` — une fonction du
**seul id de l'objet ciblé** (`scenarioId`/`decisionId`), identique pour tous les événements
successifs d'un même scénario ou d'une même décision. `EventEntity.id` (la clé primaire JPA de la
table `events`) reçoit directement cette valeur (`JpaEventStore.append()` :
`entity.setId(event.getId()); repository.save(entity)`), et `save()` sur une clé déjà existante
déclenche un `UPDATE`, pas un `INSERT`.

**Conséquence concrète** : pour un scénario/une décision qui change plusieurs fois d'état (ex.
`EMERGING → CONFIRMED → CLOSED`, 3 `ScenarioEvent` publiés sur le bus), seule la **dernière** ligne
survit dans `events` — les événements intermédiaires sont réellement écrasés en base, pas juste
"non lus". Le nom "event store" suggère un append-only classique (event sourcing) ; ce n'est pas le
cas aujourd'hui.

**Pourquoi ça n'a rien cassé jusqu'ici** : tout ce qui lit `events` aujourd'hui (photo/rejeu de
l'étape 4) ne s'intéresse qu'au **dernier état connu** par cible, qui reste correct malgré
l'écrasement. Le problème n'apparaîtrait que si un futur besoin exigeait l'historique complet des
transitions intermédiaires (audit fin, rejeu pas-à-pas, debug d'une séquence d'événements) —
actuellement, ce besoin n'existe pas.

**Si un jour ça doit être corrigé** : l'id de chaque `PersistableEvent` devrait devenir unique par
occurrence (ex. `targetId + "-" + timestamp` en cas de collision, ou `UUID.randomUUID()`), pas juste
par cible. Impact à évaluer avant de s'y engager : les lignes déjà persistées utilisent l'ancien
schéma d'id, une migration ou une période de cohabitation des deux formats serait probablement
nécessaire.

### F.2 — `DecisionSnapshot.decisionId()` est irrécupérable pour une décision jamais photographiée

Deux identifiants distincts existent pour une même `Decision` : `Decision.id` (porté par chaque
`DecisionEvent.decisionId`, donc toujours récupérable depuis le log d'événements) et
`DecisionSnapshot.decisionId()` (un second UUID, purement interne — utilisé comme clé de
`DecisionEngine.activeDecisions` — jamais transporté par aucun `DecisionEvent`). Si une décision
n'a jamais été photographiée avant un redémarrage, cette seconde valeur n'a été écrite nulle part
de récupérable : ni régénération déterministe ni heuristique ne peut retrouver la valeur d'origine,
elle n'a simplement jamais quitté la mémoire du processus précédent.

Confirmé sans impact aujourd'hui : cette valeur n'est lue nulle part ailleurs que comme clé
d'insertion dans `activeDecisions` — `DecisionEngine.getActiveDecision(String)` (le seul point qui
la consulterait) n'a aucun appelant actuel dans le code. `DecisionScenarioRestoreRunner` régénère
donc une valeur de remplacement — rendue déterministe (dérivée de `Decision.id` via
`UUID.nameUUIDFromBytes`, cf. javadoc de `decisionFromEvents`) pour rester stable entre
redémarrages successifs sans photo intermédiaire, plutôt qu'aléatoire à chaque fois — mais ce n'est
qu'un correctif de stabilité, pas une récupération de la vraie valeur d'origine.

**Si un jour ça doit être corrigé** : porter `DecisionSnapshot.decisionId()` sur `DecisionEvent`
(nouveau champ) rendrait cette valeur réellement récupérable pour toute décision, photographiée ou
non — seule façon de fermer ce point pour de bon plutôt que de le contourner.
