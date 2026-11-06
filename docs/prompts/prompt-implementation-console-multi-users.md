# Prompt — Implémentation : Console admin multi-utilisateurs (filtres + actions groupées)

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). Mission = **implémenter ce lot, rien d'autre**, puis s'arrêter pour un point avec Clem. **Aucun ordre réel pendant le développement** (tests = exchange simulé / mocks). Étude de référence : `docs/etudes/etude-console-admin-multi-users.md` (décisions §3, §8).

## 0. Démarrage
1. Lis `docs/README.md`, `docs/CODING_RULES.md`, `docs/etudes/etude-console-admin-multi-users.md`, `architecture/10-execution-plan.md`, `architecture/10-execution-reelle.md`, `architecture/11-front-app.md`.
2. Lis le code : `service/admin/{AdminExecutionService,AdminPlanTriggerService,AdminActionLogService,AdminPlanQueryService}`, `service/execution/plan/OrderPlanService` (`resetTarget`, `resetPlan`, `requireResettable`), `service/execution/run/OrderExecutor`, `service/dca/atr/binding/RainbowLiveBindingService#arm`, `service/dca/atr/bench/RainbowLiveExecutionService` (`runPass`, `recomputeDay`, `PassContext`, `seriesFor`, `Counters`), contrôleurs `ExecutionAdminController`, `ExecutionPlanAdminController`, `AdminExecutionQueryController`, `AdminUserController`, `AdminBindingController`, `model/dto/admin/AdminPlanDto`, `model/entity/admin/AdminAction`, `ExecutionDefaults`, front `adminPage.html`, `fragments/admin/console.html`, `static/assets/js/admin/{admin-core,console}.js`, tests `AdminExecutionServiceTest`, `AdminPlanTriggerServiceTest`, `ExecutionAdminSecurityTest`, `ExecutionPlanControllersSecurityTest`, `AdminControllersSecurityTest`, `MainControllerAdminPageTest` (scan sans `innerHTML`).
3. Réponds en français, court et technique. Point flou → AskUserQuestion. Suis l'avancement avec le widget de tâches. Compile/teste via le MCP JetBrains ou le ssh-gateway local (JDK 21).

## 1. Décisions de Clem (ne pas remettre en question)
- Actions groupées = **endpoints bulk côté serveur** (pas de boucle JS). **Exécution groupée autorisée**, et l'exécution plan par plan (7 étapes actuelles) **reste disponible**.
- Lot **synchrone**, **petite taille max configurable** (défaut 10), à relever selon durée et ressources mesurées. Pas de job asynchrone / polling.
- Comportement de sélection des candles **inchangé** (provider du lien, fallback tout-ou-rien avec `substituted`). Aucune complétion de série par un autre provider.
- Même stack/UX que l'existant : `textContent` uniquement (jamais `innerHTML`), pas de charte, Bootstrap déjà chargé, `@PreAuthorize("hasRole('ADMIN')")`.
- Garde-fous inchangés : LIVE verrouillé, kill switch, plafonds par ordre/jour, double validation du 1er ordre (l'**armement** est groupable, la **confirmation propriétaire** reste individuelle), jamais de retrait, fiat interdit.
- Constantes métier : mutualiser dans `ExecutionDefaults`, jamais dupliquer. Style : `isEmpty()/getFirst()`, pas de locals redondants, `assertTrue/assertFalse` directs.

## 2. À livrer — Back

### 2.1 Service de lot `AdminBulkPlanService` (`service.admin`)
- `BulkResult execute(BulkAction action, List<Long> planIds, boolean dryRun, String actor)` ; `enum BulkAction { RESET, ARM, RUN, RECONCILE }`.
- Validation : liste non vide, ids distincts, taille ≤ `ExecutionDefaults.BULK_MAX_PLANS` (défaut 10, surchargeable par propriété `tradeio.execution.bulk.max-plans`) sinon `IllegalArgumentException` (REFUSED). Un seul lot à la fois : verrou `tryLock()` non bloquant, sinon refus « lot déjà en cours ». `batchId` = UUID court.
- **Éligibilité calculée côté serveur, source unique** (même méthode pour `dryRun` et pour l'exécution réelle ; jamais dupliquée en JS pour le lot) :
  - RESET : mêmes règles que `OrderPlanService#requireResettable` + plan = dernière révision ; réutiliser, ne pas recopier (extraire un prédicat/`Optional<reason>` si besoin).
  - ARM : plan actif (`PLANNED`/`EXECUTING`) ; bindings de l'user ayant des étapes dans le plan et non encore armés ; aucun ⇒ SKIPPED `NOTHING_TO_ARM`.
  - RUN : statut `PLANNED` + tous les bindings concernés armés **et** confirmés + kill switch levé.
  - RECONCILE : plan `EXECUTING` ou étapes `SUBMITTED`/`UNKNOWN` (kill switch non requis, comme l'unitaire — vérifier le comportement actuel de `OrderExecutor`).
  - Raisons = `enum BulkSkipReason` (`NOT_FOUND`, `NOT_RESETTABLE`, `NOT_ACTIVE`, `NOTHING_TO_ARM`, `NOT_PLANNED`, `NOT_ARMED`, `NOT_CONFIRMED`, `KILL_SWITCH_ENGAGED`, `NOT_IN_FLIGHT`, `SUPERSEDED`).
- Traitement **séquentiel**, un échec n'arrête pas le lot (outcome `FAILED` + raison/exception simple). Chaque plan délègue aux services unitaires existants (journal par plan conservé, `batchId` ajouté au payload via surcharge interne ; signatures publiques existantes inchangées).
- `dryRun=true` : aucune mutation ; renvoie pour chaque plan `ELIGIBLE`/`SKIPPED`+raison ; pour RUN ajoute `orders` (nb d'étapes) et `quoteTotal` (somme des `quoteAmount` USDC), pour le récap de la modale.
- **Kill switch en lot (RUN/RECONCILE)** : `AdminExecutionService.run` rengage aujourd'hui dans son `finally` à chaque plan ⇒ casserait les plans suivants. Refactorer en `runWithoutReengage(planId, actor, batchId)` (cœur actuel) + `run(...)` (inchangé fonctionnellement : appelle le cœur puis rengage). Le lot capture `lifted` au début, appelle le cœur pour chaque plan, puis **rengage une seule fois dans un `finally`** (succès, blocage ou exception), journal `AUTO_REENGAGE` cible `batch:<id>`. Si le kill switch est engagé au départ ⇒ tous les RUN sont SKIPPED `KILL_SWITCH_ENGAGED` (le lot ne lève jamais le kill switch lui-même ; l'admin le lève via l'endpoint existant).
- **RUN exige une confirmation** : champ `confirm` du corps = `"EXECUTER"` (sans accent, identique côté UI) sinon refus ; non requis pour `dryRun`.
- Journal : nouvelle action `AdminAction.BULK`, une ligne par lot (cible `batch:<id>`, payload `{action, requested, done, skipped, failed, durationMs}`) en plus d'une ligne par plan (existante) portant `batchId`.
- `BulkResult` (record JSON, additif) : `batchId, action, dryRun, requested, done, skipped, failed, durationMs, items[]` ; item : `planId, userId, username, day, outcome (DONE|SKIPPED|FAILED|ELIGIBLE), reason, detail, durationMs, orders?, quoteTotal?`.

### 2.2 Contrôleur `ExecutionBulkAdminController`
- `POST /api/admin/execution/plans/bulk/{action}` (action = `reset|arm|run|reconcile`, insensible à la casse ; inconnue ⇒ 400 via `AdminControllerAdvice`). Corps `BulkRequest(List<Long> planIds, boolean dryRun, String confirm)`. Auteur via `IAuthenticationFacade` comme les voisins. `@PreAuthorize("hasRole('ADMIN')")`.

### 2.3 Reset groupé sans rechargement des séries
- Problème : `RainbowLiveExecutionService#recomputeDay(user, asOf, day)` crée un `PassContext` neuf à chaque appel ⇒ N users = N chargements D1 par (actif, source).
- Ajouter une **session de recalcul** : `RecomputeBatch newRecomputeBatch(Instant asOf)` (objet opaque public, contient un `PassContext` par jour) et surcharge `recomputeDay(User, Instant, LocalDate, RecomputeBatch)` qui réutilise le contexte du jour ; l'ancienne signature délègue avec un batch jetable (comportement identique). Même `asOf` pour tout le lot (pris une fois au début). Attention : `synchronized` conservé ; le contexte ne doit servir qu'à un même `asOf`/jour.
- `AdminPlanTriggerService` : surcharge `reset(planId, actor, RecomputeBatch, batchId)` utilisée par le lot ; l'unitaire garde son comportement.

### 2.4 Mesures de durée (pour décider d'une parallélisation ultérieure)
- `PassSummary` (record) : ajouter, de façon additive, `loadMs`, `signalMs`, `linksMs`, `totalMs` (Counters cumule des `System.nanoTime()` autour de `seriesFor/loadSeries`, `runPreset`, `runLinks`) ; mêmes champs sur `recomputeDay` ; log INFO de fin de passe les affiche. Adapter `RainbowLiveAdminController`/JS (`livePass`) pour afficher `totalMs`. Aucun changement de logique métier.

## 3. À livrer — Front (onglet Console)

- **Barre de filtres** : `Jour` (date, défaut = jour UTC courant, boutons ◀ ▶), `Utilisateur` (liste via `GET /api/admin/users`, « Tous »), `Statut` (« Tous » + `PlanStatus`), ⟳. Données via `GET /api/admin/execution/plans?from=<jour>&to=<jour>&userId&status` (filtres déjà supportés côté back). Option « Tous les jours » ⇒ sans `from/to` (borner à une fenêtre raisonnable si le back l'impose).
- **Actions globales du jour** (existantes, regroupées) : `Lancer la passe`, `Resynchroniser`, `Planifier tous` (= `POST /plan?day`), état du **kill switch** avec boutons `Lever` / `Réengager` (endpoints existants).
- **Chips de statut** (comptes sur le jour + utilisateur filtrés, avant filtre statut) ; clic = filtre statut.
- **Tableau de plans** (remplace le `<select>`) : ☑ · Jour · User · Rév · Statut · Actifs/étapes · Armé · Confirmé · Blocage/erreur · ▸. Armé/Confirmé : jointure client avec `GET /api/admin/bindings` (comme `context()` actuel). Case d'en-tête = tout le filtré. Raccourci « EXPIRED/BLOCKED du jour ».
- **Détail** (clic ▸) : panneau sous le tableau = `renderSummary` + **les 7 étapes actuelles pour ce plan** (exécution individuelle, code existant conservé, sans régression). Un seul plan « ouvert » à la fois.
- **Barre d'actions groupées** (visible si ≥ 1 case cochée) : `N sélectionnés · M users`, boutons Réinitialiser / Armer / Exécuter / Réconcilier. Au clic : appel `dryRun` ⇒ affiche `éligibles X / N` et la liste des inéligibles avec raison ; confirmation puis appel réel. Pour **Exécuter** : modale (Bootstrap) avec récap (`X plans, Y ordres, total USDC, users`), saisie de `EXECUTER`, bouton actif seulement si saisi. Pas de logique d'éligibilité dupliquée en JS pour le lot (uniquement le serveur).
- **Résultat du lot** : tableau par plan `planId · user · outcome · raison` + durée totale ; bouton « Relancer les erreurs » (re-sélectionne les `FAILED`). Motifs traduits par une **table unique `BULK_REASONS` dans `admin-core.js`** (à côté de `BLOCK_MESSAGES`, sans doublon).
- Contraintes : `textContent`/`el()` uniquement ; un fichier JS par onglet (`console.js` reste l'unique fichier de l'onglet, `admin-core.js` pour les helpers partagés : table/modale) ; mobile non requis (admin PC).

## 4. Tests (à placer à côté de leurs voisins directs ; ne pas créer de doublon de casse de package)
- `AdminBulkPlanServiceTest` : éligibilité par action (chaque `BulkSkipReason`), `dryRun` sans mutation, lot partiel (un FAILED n'arrête pas), taille max, verrou de lot concurrent, `confirm` requis pour RUN, **kill switch engagé ⇒ tous SKIPPED**, **rengagement unique** en fin de lot (succès, blocage et exception), journal (1 ligne `BULK` + 1 par plan avec `batchId`).
- `AdminExecutionServiceTest` : `run` unitaire inchangé (rengage à chaque appel) ; `runWithoutReengage` ne rengage pas.
- `RainbowLiveExecutionService` : `recomputeDay` avec `RecomputeBatch` ⇒ **`MarketDatasetEngine#getDailyDatasetForAsset` appelé 1 fois** pour N users partageant (actif, source) ; sans batch ⇒ comportement identique à l'existant ; `PassSummary` expose les durées.
- Sécurité : `ExecutionBulkAdminController` réservé ADMIN (non connecté ⇒ 401/redirect, USER ⇒ 403), dans le style de `ExecutionPlanControllersSecurityTest`.
- `MainControllerAdminPageTest` (scan sans `innerHTML`) reste vert ; ajouter les éléments d'UI du lot au scan si le test énumère les IDs.
- Suite complète verte (`mvn test`, JDK 21) ; signaler les échecs pré-existants sans les corriger.

## 5. Documentation (à mettre à jour dans le même lot, état courant uniquement, rien d'historique)
- `architecture/10-execution-plan.md` : endpoints bulk, règles d'éligibilité, kill switch en lot, reset groupé avec session de recalcul, journal `BULK`/`batchId`, taille max configurable.
- `architecture/11-front-app.md` : nouvelle Console (filtres, tableau, actions groupées, détail 7 étapes).
- Mentionner `tradeio.execution.bulk.max-plans` dans la doc de config/opérations existante (`operations/`).
- `etudes/etude-console-admin-multi-users.md` : cocher les décisions réalisées (journal de conception, ne pas le transformer en doc d'état). Ce prompt pourra être supprimé une fois exécuté.

## 6. Hors lot
Parallélisation et mémoïsation du signal par (actif, source, config) ; job asynchrone + polling ; complétion de séries D1 par un provider générique ; confirmation propriétaire groupée ; export/impression des résultats ; version mobile de la Console.

## 7. Critères d'acceptation
1. Console filtrable par jour / user / statut ; chips de statut ; tableau avec cases à cocher.
2. Reset / Armer / Exécuter / Réconcilier groupés avec prévisualisation `dryRun`, éligibles/inéligibles expliqués, résultat par plan.
3. Exécuter groupé : modale + saisie `EXECUTER` + kill switch rengagé une seule fois en fin de lot.
4. Exécution individuelle des 7 étapes toujours fonctionnelle depuis le détail d'un plan.
5. Reset groupé de N users ⇒ séries D1 chargées une fois par (actif, source) ; durées de passe visibles.
6. Tests verts, docs à jour, aucun `innerHTML`.
