# Prompt d'analyse — Branchement applicatif & persistance de l'état (§12 points 1-2)

Ce prompt est autonome : il peut être donné tel quel à une session qui n'a pas le contexte de la
conversation de conception. **Ce n'est PAS un prompt d'implémentation.** Il couvre les deux points
les plus sévères de l'analyse critique du 2026-08-10
(`docs/etudes/etude-mecanique-decision-dca-intelligent.md` §12, "bloquant dur") :

1. `DecisionEngine`/`DefaultScenarioEngine` ne sont branchés nulle part dans l'application qui
   tourne (pas d'annotation Spring, jamais instanciés hors tests).
2. Aucune persistance/rehydratation de l'état vivant (`scenarios`/`activeDecisions` en
   `ConcurrentHashMap` pur, perdus à chaque redémarrage).

Ces deux points sont **couplés** (pas deux sujets indépendants à traiter en silo) : la façon dont on
décide de brancher ces moteurs dans Spring détermine directement ce qu'il faut persister et comment.
D'où un seul prompt pour les deux.

## Ce qui est demandé — et ce qui ne l'est pas

**Demandé** : produire une analyse écrite (nouveau chapitre de l'étude, ou nouveau document lié si
plus lisible) qui (a) reconfirme les constats en code — ne pas prendre les constats ci-dessous pour
acquis sans les revérifier, cf. principe directeur en tête de l'étude —, (b) pose au moins 2-3 options
réalistes pour chaque point avec leurs tradeoffs, (c) formule des questions concrètes et fermées pour
Clem, seulement une fois le contexte nécessaire posé pour qu'il puisse trancher en connaissance de
cause. Une fois ces points arbitrés par Clem, un prompt d'implémentation dédié ("Palier 3") sera
rédigé séparément, sur la base de ce qui aura été décidé ici.

**Pas demandé** : écrire du code de production. Ne pas créer de registry, ne pas ajouter
d'annotation Spring, ne pas créer de mécanisme de rejeu — même à titre d'exemple/prototype. Ne pas
choisir à la place de Clem : si une option semble nettement meilleure, le dire et pourquoi, mais
poser quand même les alternatives sérieuses plutôt que les escamoter (cf. feedback déjà donné par
Clem le 2026-08-10 sur l'étude : poser les briques avant de forcer un choix).

## Avant de commencer, lire dans l'ordre

1. `docs/etudes/etude-mecanique-decision-dca-intelligent.md` — principe directeur en tête, §6
   (multi-utilisateur), §11 (par où commencer), §12 points 1 et 2 en entier (y compris la précision
   du 2026-08-11 sur `JpaEventStore`).
2. `docs/suivi/point-avancement-2026-08-10.md` §6.2 point 7 — le scheduler de génération de décision
   reste **explicitement postposé** par Clem. Ce prompt ne demande pas de le rebrancher ; il prépare
   le terrain (branchement + persistance) sans nécessairement déclencher un cycle automatique.
3. `service/tree/scenario/DefaultScenarioEngine.java` — **en particulier le constructeur**
   (`ScenarioOwner owner` fixé une fois, pas par événement) et `onOpinionEvent(...)` (utilise
   toujours `this.owner`, jamais un owner porté par l'event lui-même — `OpinionEvent` n'a d'ailleurs
   pas de notion d'owner). Comprendre précisément l'implication de ce constructeur avant de proposer
   quoi que ce soit (cf. Étape A ci-dessous).
4. `service/tree/decision/DecisionEngine.java` — même remarque sur le constructeur
   (`ScenarioOwner owner` fixé), et `onScenarioEvent(...)` qui filtre via `owner.isVisible(...)`.
5. `service/tree/event/engine/JpaEventStore.java`, `InMemoryEventStore.java`, `EventStore.java`
   (interface), `EventStoreRegistry.java` — **`EventStoreRegistry` est un précédent direct et
   pertinent** : `@Component` qui choisit `jpaStore` vs `inMemoryStore` selon `ExecutionMode`
   (`LIVE` vs `DEV`/`BACKTEST`, `model/enumerate/market/ExecutionMode.java`). Regarder si ce même
   patron (registry + `ExecutionMode`) peut/doit s'étendre à `ScenarioEngine`/`DecisionEngine`.
6. `model/dto/event/DecisionEvent.java`, `PersistableEvent.java`, `model/enumerate/tree/EventType.java`
   — vérifier par vous-même le bug confirmé le 2026-08-11 : `JpaEventStore.toDomain()` ne gère que
   `SCENARIO`/`OPINION` dans son `switch`, alors que `DecisionEvent` implémente bien
   `PersistableEvent` avec `EventType.DECISION` — une lecture (`loadByType`/`loadByTargetId`) d'un
   `DecisionEvent` persisté lèverait `IllegalArgumentException`. Ce bug est pertinent pour toute
   conception de rejeu (Étape C).
7. `repository/decision/EventRepository.java`, `model/entity/tree/EventEntity.java` — la forme
   actuelle de stockage (payload JSON générique via `ObjectMapper`), à prendre en compte pour évaluer
   la faisabilité d'un rejeu complet.
8. `service/market/DomainClock.java`, `FixedDomainClock.java`, `SystemDomainClock.java`,
   `service/market/dataset/execution/BacktestExecutionPolicy.java` — la contrainte transverse à ne
   jamais casser : un run de backtest doit toujours partir d'un état mémoire vierge, jamais interférer
   avec l'état persistant du mode live, ni l'inverse (cf. étude §8).
9. `service/tree/indicator/IndicatorRegistry.java`, `service/tree/strategy/StrategyRegistry.java`,
   `service/tree/opinion/MarketOpinionRegistry.java` — patrons de registry déjà utilisés ailleurs dans
   le projet, pour évaluer si le vocabulaire/la forme (`@Component`, résolution par clé) sont
   réutilisables pour un futur registry d'engines par owner.

Ne rien modifier en dehors de la rédaction du chapitre d'analyse demandé.

---

## Étape A — Reconfirmer le constat central avant de proposer quoi que ce soit

Vérifier en code (pas supposer) : `DefaultScenarioEngine` et `DecisionEngine` sont chacun construits
avec **un seul `ScenarioOwner` fixé à l'instanciation**, pas un singleton générique filtrant par
owner à chaque appel. Conséquence directe si c'est bien confirmé : on ne peut pas se contenter
d'ajouter `@Service` sur ces classes pour les "brancher" — leur design actuel implique **une instance
par owner actif** (potentiellement une par utilisateur, plus une pour `SystemOwner`), pas un bean
Spring unique. Documenter explicitement cette implication dans le chapitre d'analyse : c'est la pierre
angulaire qui détermine si les options de l'Étape B sont même envisageables.

Vérifier aussi : `OpinionEvent` ne porte aucune notion d'owner/utilisateur (les Opinions sont des
lectures de marché non personnalisées) — confirmer que c'est bien voulu ainsi (chaque instance
`DefaultScenarioEngine` réinterprète le même flux d'`OpinionEvent` selon son propre owner) et pas un
oubli, avant de bâtir une analyse dessus.

## Étape B — Cartographier les options de branchement (point 1)

Lister au moins 2-3 options réalistes, chacune avec : comment on décide quels owners ont une
instance active, coût mémoire/complexité, effort d'implémentation, compatibilité avec le mode
backtest (§8). Pistes à explorer sans se limiter à elles :

- Un registry Spring (`@Component`, sur le patron d'`EventStoreRegistry`) qui crée et mémorise à la
  demande (lazy) un couple `DefaultScenarioEngine`+`DecisionEngine` par owner, avec une politique
  d'éviction/cleanup à définir (owner inactif depuis longtemps → libérer l'instance ? sur quel
  signal ?).
- Création éager de toutes les instances au démarrage pour tous les utilisateurs "actifs" (à définir :
  tous les `User` en base ? seulement ceux avec au moins un `Wallet`/`ApiCredential` ? un futur flag
  "DCA activé" ?) — plus simple à raisonner, potentiellement coûteux si beaucoup d'utilisateurs
  inactifs.
- Toute autre option qui apparaît en creusant le code — ne pas se limiter à ces deux-là si une
  meilleure piste émerge de la lecture.

Pour chaque option, noter explicitement : est-ce compatible avec "le scheduler reste postposé"
(point d'avancement §6.2 pt 7) ? C'est-à-dire : le branchement peut-il exister sans forcément
déclencher un cycle automatique de génération de décision, ou les deux sujets sont-ils
indissociables en pratique ? Si indissociables, le dire clairement plutôt que de l'esquiver.

## Étape C — Cartographier les options de persistance (point 2)

Lister au moins 3 options, tradeoffs à l'appui :

- **Rejeu complet depuis `JpaEventStore`** (event sourcing) : au démarrage (ou à la création lazy
  d'une instance owner), recharger tous les événements (`loadByTargetId`/nouvelle méthode de lecture
  à définir) et reconstruire l'état en les rejouant. Nécessite de corriger le bug `toDomain()`
  (`EventType.DECISION` manquant, Étape A du prompt) et de doter `MarketScenario`/`Decision` d'un
  mécanisme de reconstruction depuis un flux d'événements (`Decision.apply(...)` existe déjà
  partiellement — évaluer s'il suffit ou s'il faut l'étendre ; `MarketScenario` n'a rien
  d'équivalent aujourd'hui). Avantage : réutilise l'infra déjà écrite et déjà active en prod (côté
  écriture). Inconvénient : coût de rejeu potentiellement croissant avec l'historique, complexité de
  reconstruction fidèle.
- **Snapshot direct périodique** : persister l'état courant (`ScenarioState`/`Decision`) dans une
  table dédiée à chaque mutation ou à intervalle régulier, charger le dernier snapshot au démarrage.
  Avantage : simple, rapide à charger. Inconvénient : duplique la représentation de l'état (table de
  snapshot en plus du log d'événements déjà existant), risque de divergence entre les deux.
- **Ne rien faire pour l'instant, assumer le risque et le documenter** : tant que le branchement
  (point 1) n'existe même pas, bâtir une persistance sophistiquée serait probablement prématuré —
  légitime de proposer explicitement cette option comme un choix "pas maintenant", pas comme un
  renoncement, avec les conditions qui justifieraient d'y revenir (volume d'utilisateurs, durée de
  vie réelle des scénarios une fois les seuils recalibrés en §7.3/§9).
- Toute option hybride qui apparaît pertinente en creusant.

Contrainte à respecter dans toutes les options : un run de backtest doit toujours démarrer d'un état
mémoire vierge (jamais lire ni écrire la persistance live) — expliciter comment chaque option
respecte ça, en s'appuyant sur le patron déjà en place dans `EventStoreRegistry`/`ExecutionMode`.

## Étape D — Rédiger le chapitre d'analyse

Nouveau chapitre dans `docs/etudes/etude-mecanique-decision-dca-intelligent.md` (numéroté à la suite
de l'existant) ou nouveau document `docs/etudes/etude-branchement-persistance-decision-engine.md` si
la taille du contenu le justifie mieux (juger en écrivant, pas de règle stricte) — contenant :
constat reconfirmé (Étape A), options de branchement avec tradeoffs (Étape B), options de persistance
avec tradeoffs (Étape C), et une section finale de **questions fermées pour Clem** (pas des choix
binaires posés à froid : chaque question doit pouvoir se lire en s'appuyant sur les briques posées
juste avant dans le même chapitre). Terminer explicitement par : "une fois ces points tranchés, un
prompt d'implémentation Palier 3 sera rédigé sur cette base — pas avant."

Mettre à jour la mémoire projet (`tradeio5_decision_dca_intelligent_etude`) avec un résumé des options
posées et, si Clem tranche dans la foulée, ses décisions — même patron que pour les chapitres
précédents de cette étude.

---

## Rappel — ce lot est une analyse, pas un chantier de code

Aucun diff de code applicatif n'est attendu en sortie de ce prompt. Le livrable est un texte
d'analyse (chapitre d'étude ou nouveau document), plus, le cas échéant, les réponses de Clem aux
questions posées. Le prompt d'implémentation viendra dans un lot séparé, une fois ces réponses
obtenues.
