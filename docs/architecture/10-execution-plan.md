---
statut: courant
vérifié_le: 2026-10-12
modifié_le: 2026-11-11
empreinte: fe11b843d5
---
# Plan d'ordres dry-run (lot B, étape b)

Vérifié le : 2026-10-12 (`service/execution/**`, `model/entity/execution/**`, `repository/execution/**`, `service/scheduler/ExecutionPlanJob`, `controller/{ExecutionPlanAdminController,RainbowLiveExecutionPlanController}`, `rainbow-live.js`).

**Le plan n'envoie rien.** Il calcule et enregistre ce qu'on achèterait / vendrait, par quel chemin et à quel coût ; les classes de `service.execution.plan` et le job de plan ne dépendent d'aucun port d'ordre. L'exécution réelle (re-devis, envoi, audit) est un composant séparé, verrouillé : [`14-execution-reelle.md`](14-execution-reelle.md). Chemin d'achat chiffré (catalogue, frais, carnet, `PathFinder`, Fee Test) : [`08-rainbow-live.md`](08-rainbow-live.md) §Chemin d'achat chiffré.

## Mode et activation

- `tradeio.execution.mode` ∈ `OFF|DRY_RUN|LIVE` (`ExecutionSettings`, défaut `DRY_RUN`). `OFF` coupe aussi le dry-run (aucun plan). **`LIVE` est refusé au démarrage** (`IllegalStateException`) sans `tradeio.execution.live-unlocked=true` et sans `TRADEIO_MASTER_KEY` ([`14-execution-reelle.md`](14-execution-reelle.md)) ; valeur inconnue refusée aussi.
- `RainbowLiveBinding.executionEnabled` (défaut `false`, modifié par `PUT /api/rainbow-live/bindings/{id}/execution`). Binding ni actif ni en simulation ⇒ actif `DISABLED` (visible, sans étape) ; tous les actifs désactivés ⇒ plan `DISABLED`.
- **Simulation** (`RainbowLiveBinding.simulation`, modifiée par `PUT …/execution {mode}`) : l'actif est planifié comme un actif actif (chemin, coûts, carry, cash partagé) et la ligne d'actif porte `simulated = true` (tag « Simulation » au Journal et dans Actifs gérés). `OrderExecutor` **saute** les actifs simulés : aucun ordre n'est envoyé. `simulated` entre dans le hash des entrées.
- **Achat minimum** (`minBuyUsd` du binding) : un achat dont le montant est inférieur au minimum est `BLOCKED`/`BELOW_MIN` avec avertissement ; le mécanisme de carry ci-dessous le cumule jusqu'au minimum (le minimum entre dans le hash des entrées).
- Constantes mutualisées (`ExecutionDefaults`) : durée de vie du plan 5 min (`tradeio.execution.plan-ttl`), tolérance de slippage du prix plafond 0,1 % (`tradeio.execution.slippage-tolerance-pct`), seuils Fee Test, nœuds interdits (fiat + `USDT`), intermédiaires (`BTC`, `ETH`) dont seul `BTC` est choisi automatiquement, profondeur max 2.

## Déclenchement

- `ExecutionPlanJob` (`service/scheduler`) : `tradeio.execution.plan-cron`, défaut `-` (désactivé), cron cible `0 58 23 * * *`, `zone = "UTC"` ; plan du jour UTC (`RainbowLiveExecutionService.dayFor(now)`). Il lit les runs **en base** (la passe quotidienne ne dépend pas du plan).
- Manuel : `POST /api/admin/execution/plan[?day=]` (ADMIN) ⇒ `PlanSummary{day, planned, blocked, disabled, unchanged, skipped, errors}`. Une erreur sur un user n'arrête pas les autres ; pas de rattrapage des jours manquants ; user sans run du jour ⇒ ignoré.

- Un plan `EXPIRED` n'est pas régénéré tant que les entrées (hash) sont identiques : la réponse est `unchanged`. Pour repartir de zéro sans changement d'entrées, `OrderPlanService#resetPlan` (console admin, bouton « Réinitialiser le plan », `POST /api/admin/execution/plans/{id}/reset`) force une nouvelle révision, uniquement si aucune étape n'est partie (statut `PLANNED`/expiré/`BLOCKED`/`DISABLED`/`CANCELLED`, toutes étapes `PLANNED`) ; mêmes `clOrdId` (rien n'a été envoyé). Pour le **jour UTC ouvert**, `AdminPlanTriggerService#reset` recalcule d'abord le signal Rainbow de l'utilisateur à l'instant du reset (`RainbowLiveExecutionService#recomputeDay` : runs de preset et de lien du jour supprimés, donc ni action figée ni réservation de cash — le `CashLedger` est reconstruit à chaque passe —, puis passe rejouée sur la bougie courante) avant de replanifier ; un jour clôturé garde son run. Un plan exécuté, partiel, en échec ou à réconcilier ne se réinitialise jamais. `clOrdId` ne contient pas de révision. Les plafonds lisent le `baseAmount` du run du preset au `run_day` du plan (`NO_BASE_AMOUNT` sinon).

## Actions groupées (Console admin)

`POST /api/admin/execution/plans/bulk/{reset|arm|run|reconcile}` (`ExecutionBulkAdminController`, ADMIN), corps `{planIds, dryRun, confirm}` ; service `AdminBulkPlanService`. Lot **synchrone**, **séquentiel**, taille max `tradeio.execution.bulk.max-plans` (défaut `ExecutionDefaults.BULK_MAX_PLANS` = 10) ; liste vide, doublons, trop grande ou lot déjà en cours (verrou `tryLock`) ⇒ 400 (REFUSED au journal). Pas de job asynchrone.

- **Éligibilité calculée côté serveur, une seule méthode** pour `dryRun` (prévisualisation, aucune mutation, ajoute `orders` et `quoteTotal` USDC pour RUN) et pour l'exécution. RESET : `OrderPlanService#resetCheck` (mêmes règles que `resetPlan`, dernière révision). ARM : plan `PLANNED`/`EXECUTING` avec des liens non armés (`firstLiveApprovedAt` nul). RUN : plan `PLANNED` non expiré, liens concernés armés **et** confirmés, kill switch engagé ⇒ refusé. RECONCILE : plan `EXECUTING` ou étapes `SUBMITTED`/`UNKNOWN` ; passe par `OrderExecutor#execute` comme l'unitaire, donc exige aussi le kill switch levé. Motifs : `BulkSkipReason`.
- **Exécution** : chaque plan délègue aux services unitaires (journal par plan conservé, `batchId` ajouté au payload) ; un échec n'arrête pas le lot (`FAILED` + détail). `RUN` réel exige `confirm = "EXECUTER"`. L'armement est groupable, la confirmation propriétaire reste individuelle.
- **Kill switch en lot** : le lot ne le lève jamais. S'il était levé à l'entrée, `AdminExecutionService#runWithoutReengage` (cœur de `run`, sans rengagement) est appelé par plan et le lot rengage **une seule fois** dans un `finally` (journal `AUTO_REENGAGE`, cible `batch:<id>`). `run` unitaire inchangé (rengage à chaque appel).
- **Reset groupé** : `RainbowLiveExecutionService#newRecomputeBatch(asOf)` + `recomputeDay(user, asOf, jour, batch)` partagent les séries D1 par (actif, source) entre les users du lot (même `asOf`, un contexte par jour) ; `AdminPlanTriggerService#reset(planId, actor, batch, batchId)`.
- **Journal** : `AdminAction.BULK`, une ligne par lot réel (cible `batch:<id>`, payload `{action, requested, done, skipped, failed, durationMs}`) en plus d'une ligne par plan portant `batchId`.
- **Durées de passe** : `PassSummary` expose `loadMs` (chargement des séries D1), `signalMs` (rejeu des presets), `linksMs` (liens live), `totalMs` ; loguées en fin de passe et de recalcul.

## Carry : état dérivé, pas de compteur

Le carry n'est pas stocké sur le binding : `carry_in` = `carry_out` de la dernière ligne d'actif (même user, wallet et actif) d'un plan courant (hors `SUPERSEDED`) **strictement antérieur** au jour planifié, quel que soit l'écart de jours (Rainbow n'achète pas tous les jours). Rejeu, révision et reset d'un plan recalculent donc la même valeur. Il est remis à zéro quand le `base_amount` du preset a changé depuis cette ligne (`base_amount` = instantané du `baseAmount` du run du preset au jour du plan) ; base inconnue d'un côté : carry conservé. Il suit sinon les changements de preset et de wallet-actif du binding (clé = user × wallet × actif). Il entre dans le hash des entrées.

## Entrées et idempotence (`OrderPlanService`)

Par user (une transaction par user) : bindings par `priority` + bloc live du `RainbowLiveBindingRun` du jour pour chaque binding (`status`, `actionType`, `actionAmountUsdc` = montant USD nominal figé au 23:55, `actionQuantity`, `cashDetail` par membre). Un même actif peut avoir plusieurs bindings (un par wallet) : chacun donne son propre actif de plan, planifié et exécuté indépendamment. Le **hash des entrées** (SHA-256 : bindings + blocs, jamais les données de marché) pilote l'idempotence : même hash ⇒ rien ne change (rejeu 23:55 ⇒ plan identique, mêmes `clOrdId`) ; hash différent ⇒ la révision courante passe `SUPERSEDED` (ligne d'audit conservée) et une révision `n+1` est créée.

## Planner (`OrderPlanner`, `service/execution/plan`)

Pas de persistance, pas d'écriture, aucune dépendance à un port d'ordre ; lectures de (a) seulement (catalogue en base, frais du compte en cache, carnet public lu une fois par instrument et par plan : `ExchangeLegMarketSource`).

- **Bilan virtuel** par wallet et par membre du groupe USD (USDC seul), initialisé du cash du snapshot, parcouru par `priority` croissante comme `CashLedger`. Les ventes ne créditent pas le bilan du jour. Le **stock en transit** actif du wallet (`TransitStock`) compte comme source d'achat et est déduit de la position du lien portant la même devise ; il entre dans les entrées et le hash du plan.
- **Achat** : `PathFinder` (source = cash USDC + stock en transit, cible = l'actif) donne les routes ; la route choisie est de coût brut minimal parmi les intermédiaires choisis (BTC), ex æquo : moins de jambes. Route ETH chiffrée et affichée, jamais choisie automatiquement. **Route imposée** : si le lien porte `preferredRoute` (choix de l'utilisateur à l'onboarding), `PathFinder` ne retient que cette route (même via un intermédiaire affiché seulement) ; indisponible ⇒ `NO_PATH` (achat bloqué, jamais de route de remplacement). Jambes dimensionnées en séquence sur le **net reçu** de la précédente (frais déduits), `sz` arrondi au `lotSz`, `minSz`/`costMin` contrôlés. Ordres : `LIMIT_IOC`, prix plafond = prix de référence ± tolérance, arrondi au tick (BUY ↑, SELL ↓). Un stock en transit pour la cible est terminé (jambe 2, `BUY actif-intermédiaire`) avant tout nouvel achat ; à la vente, un stock de sens `SELL` est terminé par `SELL intermédiaire-USDC` en tête du plan de vente.
- **Vente** : quantité du bloc arrondie au `lotSz` (↓), vendue en USDC (route `X>USDC`, ou `X>BTC>USDC` quand seule cette route existe, ex PAXG sur Kraken) ; l'intermédiaire n'est jamais conservé.
- **Fee Test** (somme brute des jambes) : `WARNING` et `RED` ⇒ étapes + avertissement (le niveau est conservé, aucun blocage).
- **Raisons de blocage** (`PlanBlockReason`) : `NO_PATH`, `BELOW_MIN` (taille < `minSz` ou coût < `costMin` du catalogue), `INSUFFICIENT_FUNDS_AFTER_FEES`, `NOT_TRADABLE_WITHOUT_FIAT` (aucune route sans fiat, ou lecture `NOT_TRADABLE`), `READING_UNAVAILABLE` (lecture `UNAVAILABLE`/absente, marché ou catalogue illisible). Action `NONE`/`BLOCKED` du bloc ⇒ `NO_ACTION`, aucune étape. Un actif bloqué n'empêche pas les autres.
- **Carry (achat sous le minimum du provider)** : la mise effective d'un achat = `actionAmountUsdc` du jour + `carry_in`. Si le chemin est `BELOW_MIN`, toute la mise effective est reportée (`carry_out` = mise effective, plan `BLOCKED` pour cet actif, raison conservée) ; si l'achat passe, le carry est consommé (`carry_out` = 0) ; vente, `NO_ACTION`, `DISABLED` et tout autre blocage laissent le carry inchangé (`carry_out` = `carry_in`). Pas de sur-achat jusqu'au minimum.
- **Statut du plan** : `DISABLED` si tous les actifs le sont ; `PLANNED` dès qu'un actif a des étapes (ou rien à faire) ; `BLOCKED` si des actions voulues n'ont donné aucune étape. `totalCostPct` / `feeTestLevel` du plan = le pire parmi les actifs exécutables ; `blockReason` = `BTC:NO_PATH;PAXG:BELOW_MIN`.

## Entités (`model/entity/execution`)

| Table | Contenu |
|---|---|
| `rainbow_live_order_plan` | user, `run_day`, `revision` (unique avec les 2 précédents), `status` (`PLANNED|BLOCKED|DISABLED|SUPERSEDED|EXECUTING|EXECUTED|PARTIAL|FAILED|CANCELLED` persistés ; les quatre derniers de l'exécution réelle), `mode`, coût, niveau Fee Test, `block_reason`, `inputs_hash`, `created_at`, `expires_at` |
| `rainbow_live_order_plan_asset` | par actif : priorité, wallet, action voulue, `outcome` (`OK|BLOCKED|DISABLED|NO_ACTION`), raison, coût, niveau, avertissement, `carry_in_usd`, `carry_out_usd`, `base_amount`, `simulated` (FK plan, `ON DELETE CASCADE`) |
| `rainbow_live_order_event` | audit **append-only** de l'exécution (plan, étape, `clOrdId`, type, charge JSON) ; `execution_control` : ligne unique (kill switch, multiplicateurs) |
| `rainbow_live_order_step` | `step_rank`, actif, `wallet_id`, `inst_id`, `side`, `ord_type` (`LIMIT_IOC`), `sz`, `px` (plafond du plan ; recalculé à l'envoi, voir [14](14-execution-reelle.md)), `quote_amount`, `cl_ord_id`, frais / spread / slippage %, `status` (`PLANNED|SUBMITTED|FILLED|PARTIAL|REJECTED|CANCELED|UNKNOWN`), `estimation` (`BOOK|TICKER`), et après exécution : `ord_id`, tailles envoyée / remplie, prix moyen, frais (+ devise), quantité reçue, mid du re-devis, slippage réel, horodatages, dernière erreur (FK plan, `ON DELETE CASCADE`) |

`EXPIRED` n'est jamais persisté : le statut effectif est dérivé à la lecture (`PLANNED` dont `expires_at` est atteint). Un plan expiré n'est pas recalculé à entrées identiques.

`clOrdId` (`ClOrdIds`) : `t5` + userId et walletId (base 36, préfixés de leur longueur) + actif + `yyyyMMdd` + rang sur 2 chiffres ; alphanumérique, ≤ 32 caractères, déterministe.

## Lecture utilisateur et page

- `GET /api/rainbow-live/execution-plans[?from=&to=]` (défaut 30 jours) et `/latest` (204 si aucun) : `RainbowLiveExecutionPlanController` → `OrderPlanQueryService` (base uniquement), propriétaire seulement, DTO dédiés (`OrderPlanDtos`), révisions remplacées non exposées. Cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md).
- Affichage : frise de décision de la fiche actif (`/app/assets/{symbole}`) et Journal (`/app/journal`) : statut, jour (23:55 UTC), validité / « expiré », étapes, coût et badge Fee Test (vert / orange / rouge), raison de blocage. Exécution réelle : [`14-execution-reelle.md`](14-execution-reelle.md).

## Garde-fous (tests)

`ExecutionPlanArchitectureTest` (ni port / client d'ordre ni `ProviderApiService` en dépendance transitive du plan, du job et des endpoints ; aucune méthode `placeOrder/newOrder/createOrder/cancelOrder/submit`), `ExecutionOrderArchitectureTest`, `ConnectorNoOrderMethodTest` et `RainbowLiveNoExchangeDependencyTest` inchangés, `OrderPlanServiceTest` (mode `LIVE` refusé sans déverrouillage), `ExecutionPlanControllersSecurityTest` (sécurité par méthode active).
