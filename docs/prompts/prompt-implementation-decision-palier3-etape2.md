# Prompt d'implémentation — Décision, Palier 3, Étape 2 (compléments au branchement — alignement fin sur le partage owner-en-paramètre)

Ce prompt est autonome : il peut être donné tel quel à une session d'implémentation qui n'a pas le
contexte de la conversation de conception. Il couvre la nouvelle **Étape 2** insérée le 2026-08-12 dans
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (toutes les étapes suivantes ont été
décalées d'un cran à cette occasion). **Prérequis** : Étape 1 (`docs/prompts/
prompt-implementation-decision-palier3-etape1.md`) mergée — confirmé en code, pas supposé : `@Service`
sur `DefaultScenarioEngine`/`DecisionEngine`, owner retiré des deux constructeurs, bug de
`onMarketOpinion` corrigé, filtre `isVisible` retiré d'`onScenarioEvent`, et les trois tests dédiés déjà
en place (`SharedScenarioEngineMultiOwnerTest`, `DecisionEngineSpringWiringTest`, mise à jour des tests
existants). Référence : `docs/etudes/etude-branchement-persistance-decision-engine.md` §B, addendum
"Précisions apportées le 2026-08-12" (lignes 239-264).

**Pourquoi cette étape existe séparément** : l'étape 1 a été implémentée avant que ces précisions ne
soient formalisées par écrit. Plutôt que de les considérer acquises rétroactivement sans vérification,
cette étape les reprend une à une pour confirmer ce qui est déjà fait (rien à refaire), traiter ce qui
ne l'est pas, et trancher la question laissée explicitement ouverte par l'addendum et le tableau "Points
encore à trancher" de la roadmap. **Ne pas ré-implémenter ce qui est déjà en place** — ce lot est un
lot de finition ciblé, pas une répétition de l'étape 1.

**Ce que ce lot n'est PAS** : pas l'orchestrateur (étape 7, qui utilisera ce que ce lot confirme/laisse
en place, mais ne le construit pas) ; pas la persistance (étapes 3/4) ; pas un nouveau refactor de
signature — aucune méthode publique de `ScenarioEngine`/`DecisionEngine` ne doit changer de signature
dans ce lot, seulement du nettoyage interne et de la documentation de décision.

Avant de commencer, lire dans l'ordre :
1. `docs/etudes/etude-branchement-persistance-decision-engine.md` — §B, le paragraphe "Précisions
   apportées le 2026-08-12" (lignes 239-264) en entier : c'est la checklist que ce lot referme point
   par point.
2. `docs/prompts/prompt-implementation-decision-palier3-roadmap.md` — section "Points encore à
   trancher", puce Étape 2 : la question explicitement laissée ouverte.
3. `service/tree/scenario/DefaultScenarioEngine.java` — état actuel post-étape 1 : en particulier
   `onOpinionEvent(OpinionEvent event, ScenarioOwner owner)` (le bloc if/else if qui ne filtre jamais
   réellement rien, cf. étape 3 ci-dessous) et le bloc `symbols`/`addSymbolSurvey`/`removeSymbolSurvey`
   (section "Symbols survey Set").
4. `service/tree/decision/DecisionEngine.java` — lignes 43 et 50 : deux lignes de code mort, restes
   commentés de l'ancien champ `symbols` jamais utilisé par cette classe (à ne pas confondre avec le
   `symbols` bien réel de `DefaultScenarioEngine`).
5. `service/tree/opinion/MarketOpinion.java` — l'interface : `decide(...)` retourne `void`, avec le
   commentaire "Must emit an event." C'est la pièce qui tranche la question ouverte de l'addendum (cf.
   étape 1 de ce prompt ci-dessous) : aucune Opinion ne renvoie jamais son résultat directement, la
   seule façon de le récupérer est de capter l'`OpinionEvent` publié sur le bus.
6. `service/tree/opinion/impl/DefaultMarketOpinion.java` — exemple concret : `interpretSignals(...)`
   construit un `OpinionEvent` et fait `eventBus.publish(event)`, sans jamais retourner l'`OpinionSignal`
   à l'appelant. Les 4 autres implémentations (`MacroMarketOpinion`, `GlobalMarketOpinion`,
   `ExternalMarketOpinion`, `MediaMarketOpinion`) suivent le même patron — inutile de toutes les lire en
   détail, une suffit pour confirmer le constat.
7. `service/tree/event/engine/EventBus.java` — `unsubscribe(...)`, déjà documenté comme "utile pour les
   abonnements temporaires (ex: capture synchrone d'un event le temps d'un seul appel)" : c'est le
   patron déjà prévu dans ce projet pour ce que le futur orchestrateur (étape 7) devra faire.
8. `docs/etudes/etude-branchement-persistance-decision-engine.md` §A.6 — rappel : `JpaEventStore`/
   `InMemoryEventStore` s'abonnent inconditionnellement à `PersistableEvent` et persistent aujourd'hui
   tout ce qui passe sur le bus, y compris `OpinionEvent` (implémente `PersistableEvent` — à vérifier en
   lisant `model/dto/event/OpinionEvent.java` si un doute existe).
9. Tests existants à prendre comme patron pour les nouveaux tests de ce lot :
   `service/tree/scenario/SharedScenarioEngineMultiOwnerTest.java` (patron multi-owner sur instance
   partagée, à réutiliser pour tester `onOpinionEvent` spécifiquement — ce test-ci n'exerce aujourd'hui
   que `onMarketOpinion`, jamais `onOpinionEvent` directement).

Ne rien modifier en dehors de ce qui est listé ci-dessous. Ne pas toucher aux 5 classes
`*MarketOpinion` (`DefaultMarketOpinion`, `MacroMarketOpinion`, `GlobalMarketOpinion`,
`ExternalMarketOpinion`, `MediaMarketOpinion`) — la question de la publication d'`OpinionEvent` se
tranche par la documentation/le test décrits ci-dessous, pas en modifiant leur code, qui reste
correct tel quel.

---

## Étape 1 — Trancher par écrit la question ouverte : publication d'`OpinionEvent`, garder ou abandonner

**Contexte** : la roadmap et l'addendum de l'étude laissent explicitement ouverte la question
"faut-il conserver la publication d'un `OpinionEvent` sur le bus uniquement à des fins d'audit/
persistance (sans rôle de déclenchement), ou l'abandonner entièrement puisque plus personne ne l'écoute
pour agir ?". En lisant le contrat de `MarketOpinion.decide(...)` (point 5 des lectures préalables), la
réponse est en fait déjà contrainte par l'existant, pas un choix de design libre : **`decide(...)`
retourne `void` par contrat** ("Must emit an event.") — aucune des 5 implémentations ne renvoie
l'`OpinionSignal` calculé autrement qu'en le publiant sur le bus. Un futur orchestrateur (étape 7) n'a
donc **aucun autre moyen** d'obtenir le résultat d'une Opinion que de capter l'`OpinionEvent` publié —
que ce soit pour de l'audit ou pour agir. Abandonner la publication casserait donc, de fait, le seul
canal existant pour transmettre le résultat, pas seulement une fonctionnalité d'audit accessoire.

**Réponse à documenter (pas de code de production à changer sur les 5 classes `*MarketOpinion`,
cf. avertissement ci-dessus)** : **garder la publication**, pour deux raisons distinctes à toujours
citer ensemble (l'une ne suffit pas à elle seule à justifier de la garder si l'autre disparaissait un
jour) :
1. Audit/persistance (étude §A.6) : `JpaEventStore`/`InMemoryEventStore` la persistent déjà
   inconditionnellement.
2. **Unique canal de transmission du résultat** : `MarketOpinion.decide(...)` n'a pas d'autre sortie —
   supprimer la publication reviendrait à supprimer la seule façon d'obtenir un `OpinionSignal`, y
   compris pour l'orchestrateur.

**À faire** :

1. Ajouter un commentaire javadoc sur `DefaultScenarioEngine.onOpinionEvent(...)` documentant
   explicitement ce raisonnement (les deux raisons ci-dessus) et le patron attendu pour un futur
   appelant : capture synchrone via `eventBus.subscribe(OpinionEvent.class, ...)` suivi de
   `eventBus.unsubscribe(...)` (cf. lecture préalable point 7), puis appel à `onOpinionEvent(event,
   owner)` par owner concerné — pas une méthode à inventer, celle-ci existe déjà depuis l'étape 1.
2. Mettre à jour `docs/etudes/etude-branchement-persistance-decision-engine.md` §B (juste après le
   paragraphe "Précisions apportées le 2026-08-12") : remplacer la dernière puce ("Reste ouvert : ...")
   par la décision actée ci-dessus, avec la même justification en deux points. Ne pas laisser la
   question "reste ouverte" dans le document une fois ce lot mergé.

**Tests attendus** (nouveau test, ex. dans un nouveau fichier
`service/tree/scenario/OpinionEventCaptureBridgeTest.java`, patron `SharedScenarioEngineMultiOwnerTest`
pour la partie multi-owner) :
- **Combler un trou de couverture existant** : `onOpinionEvent(OpinionEvent, ScenarioOwner)` n'est
  exercé par **aucun** test aujourd'hui (vérifié : seul `onMarketOpinion` est appelé directement dans
  `SharedScenarioEngineMultiOwnerTest`/`DefaultScenarioEngineUnitTest`) — un test doit l'appeler
  directement avec un `OpinionEvent` construit à la main, vérifier qu'un scénario est bien créé pour
  l'owner passé en paramètre (pas un autre), sur le patron des tests `onMarketOpinion` déjà existants.
- **Preuve du patron de capture synchrone** que l'orchestrateur (étape 7) utilisera : sur un
  `EventBus` réel, publier un `OpinionEvent` (construit à la main, pas besoin d'instancier une vraie
  classe `*MarketOpinion`) ; dans le test, s'abonner temporairement (`subscribe`/`unsubscribe`, même
  patron que documenté sur `EventBus.unsubscribe(...)`) pour capturer cet event, puis appeler
  `engine.onOpinionEvent(captured, ownerA)` et `engine.onOpinionEvent(captured, ownerB)` avec le
  **même** `OpinionEvent` capturé une seule fois ; vérifier que `getActiveScenarios(ownerA, ...)` et
  `getActiveScenarios(ownerB, ...)` renvoient chacun un scénario distinct, proprement isolé — preuve
  que le même signal de marché peut être propagé à plusieurs owners depuis une seule capture, exactement
  le schéma décrit en §E point 6 de l'étude ("calcul une fois, propagation par owner").

---

## Étape 2 — Nettoyage : code mort laissé par l'étape 1

**Contexte** : deux restes identifiés en relisant le code après l'étape 1, aucun des deux ne change de
comportement, purement du nettoyage.

**À faire** :

1. `DecisionEngine.java` lignes 43 et 50 : supprimer les deux lignes commentées `//private final
   Set<String> symbols;` et `// this.symbols = symbols;`, restes de l'ancien constructeur pré-étape 1.
   Si l'import `java.util.Set` devient inutilisé après ce retrait, le retirer aussi (vérifier : `Set<
   MarketIntentAction>` est utilisé plus loin dans `isUnanimousAcrossScopes`, donc l'import reste
   probablement nécessaire — à vérifier plutôt qu'à supposer).
2. Retirer le commentaire de tête associé si devenu obsolète après le nettoyage (relire les commentaires
   autour des lignes 42-56 pour cohérence, sans réécrire ce qui reste pertinent).

**Tests attendus** : aucun nouveau test — changement non fonctionnel. Vérifier simplement que la suite
existante compile et passe toujours après ce retrait (couvert par le lancement final de la suite,
cf. section finale de ce prompt).

---

## Étape 3 — `DefaultScenarioEngine` : trancher le sort du mécanisme `symbols`/`addSymbolSurvey`

**Contexte**, rappel de l'addendum (§B, "corollaire") : le mécanisme de liste de surveillance
(`symbols`, `addSymbolSurvey(...)`) servait, avant l'étape 1, à filtrer une diffusion large
(l'auto-abonnement `OpinionEvent` déclenchait un traitement pour *tout* event reçu, `symbols` étant
censé permettre de l'ignorer). Cet auto-abonnement a été retiré à l'étape 1 — c'est désormais
l'appelant (futur orchestrateur, étape 7) qui décide en amont quels appels déclencher, owner par owner,
symbole par symbole. **Vérifié en code, pas supposé** : `symbols` n'a aujourd'hui plus aucun effet
fonctionnel nulle part :
- `addSymbolSurvey(...)` l'alimente, mais rien ne lit `symbols` pour une décision réelle.
- `onOpinionEvent(...)` le consulte dans un bloc `if/else if` qui ne fait que `log.debug(...)` dans les
  deux branches — **aucun `return`/`continue`**, le traitement se poursuit toujours identiquement
  derrière, que le symbole soit "surveillé" ou non. Ce n'est pas un filtre, seulement un log
  conditionnel sans effet.
- `removeSymbolSurvey(String symbol)` retire bien `symbol` de `symbols`, mais la purge de scénarios
  qu'elle effectue ensuite (`scenarios.values().stream().filter(s -> s.getSymbol()...equals(symbol))`)
  ne consulte jamais `symbols` pour fonctionner — elle filtre directement sur le paramètre `symbol` reçu.
  **Ce comportement de purge, lui, reste utile et indépendant du mécanisme de liste de surveillance.**
- Aucun test existant n'appelle `addSymbolSurvey`/`removeSymbolSurvey`/ne dépend de `symbols` (vérifié
  par recherche dans `src/test/java` avant d'écrire ce prompt).

**Décision à appliquer** : retirer le champ `symbols` et `addSymbolSurvey(...)` (mécanisme de liste de
surveillance devenu inerte, cf. constat ci-dessus), **tout en conservant** la capacité de purger les
scénarios d'un symbole donné — un besoin réel et distinct, indépendant de toute notion de "liste
surveillée".

**À faire** :

1. Supprimer le champ `private final Set<String> symbols = ConcurrentHashMap.newKeySet();` et la
   méthode `addSymbolSurvey(String symbol)`.
2. Dans `onOpinionEvent(...)`, supprimer le bloc `if/else if` qui consultait `symbols` (lignes 71-76
   actuelles) — conversion et délégation directes à `eventToOpinionSignal(...)`/`onMarketOpinion(...)`,
   sans log conditionnel qui n'apportait rien. Si un log d'entrée reste souhaitable pour le débogage,
   un simple `log.debug("OpinionEvent reçu pour owner {}, symbole {}", owner, event.getSymbol());`
   inconditionnel est plus honnête que l'ancien filtre apparent — à la discrétion de l'implémenteur,
   pas obligatoire.
3. Renommer `removeSymbolSurvey(String symbol)` en `purgeScenariosForSymbol(String symbol)` (le nom
   actuel n'a plus de sens une fois la notion de "survey" retirée — cette méthode n'a jamais fait autre
   chose que purger, le nom était juste mal aligné sur son propre comportement réel) et retirer la ligne
   `symbols.remove(symbol);` devenue sans objet à l'intérieur. **Si ce renommage s'avère gênant** parce
   qu'un appelant externe existerait déjà (vérifier par recherche avant de renommer — aucun trouvé au
   moment de la rédaction de ce prompt, mais à reconfirmer), garder `removeSymbolSurvey` tel quel et le
   signaler dans le rapport final plutôt que de forcer le renommage.

**Tests attendus** :
- Mettre à jour tout test qui référencerait encore `addSymbolSurvey`/`removeSymbolSurvey` (aucun trouvé
  à ce jour, mais reconfirmer par recherche avant de conclure qu'il n'y a rien à changer).
- Un test dédié pour la méthode renommée (nouveau, ou adaptation d'un test existant si un test de
  purge existait déjà sous l'ancien nom — vérifier avant d'en écrire un redondant) : créer un scénario
  pour un symbole donné, appeler `purgeScenariosForSymbol("BTC")`, vérifier que le scénario est retiré
  de `engine.scenarios` et qu'un `ScenarioEvent(SCENARIO_EXPIRED)` est bien publié — reprend le
  comportement déjà couvert implicitement, à couvrir maintenant explicitement puisque la méthode change
  de nom et perd une ligne de code (`symbols.remove(...)`), pour prouver que le comportement réel
  (purge + event) n'a pas régressé au passage.

---

## À la fin : lancer les tests via la Gateway

Compiler et exécuter la suite de tests complète via l'opération CI/CD `test:tradeio-5` du gateway SSH
(`mcp__plugin_ssh-gateway_ssh-gateway__executeOperation`). Ne pas lancer `mvn` directement en sandbox
(pas de Maven/réseau disponible).

Rapporter : résultat global, nombre de tests exécutés (comparer à la baseline de 492 tests au
2026-08-12, après l'étape 1), détail de tout échec, et signaler explicitement :
- si le renommage `removeSymbolSurvey` → `purgeScenariosForSymbol` a dû être abandonné faute d'un
  appelant externe découvert en cours de route (étape 3, point 3) ;
- si l'import `java.util.Set` de `DecisionEngine` a effectivement pu être retiré ou non (étape 2) ;
- tout endroit où le patron de capture synchrone (étape 1, test `OpinionEventCaptureBridgeTest`) s'est
  avéré plus difficile à mettre en place proprement que prévu — information utile pour calibrer l'étape
  7 (l'orchestrateur), qui reproduira ce même patron à plus grande échelle.

Une fois ce lot mergé, mettre à jour le tableau de statut de
`docs/prompts/prompt-implementation-decision-palier3-roadmap.md` (étape 2 → ✅), puis rédiger le prompt
de l'étape 3 (extensions de modèle pour la persistance) — débloquée, comme l'étape 5 (détection de
connexion, toujours indépendante) — à discuter avec Clem laquelle rédiger en premier.
