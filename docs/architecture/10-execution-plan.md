# Plan d'ordres dry-run (lot B, étape b)

Vérifié le : 2026-10-12 (`service/execution/**`, `model/entity/execution/**`, `repository/execution/**`, `service/scheduler/ExecutionPlanJob`, `controller/{ExecutionPlanAdminController,RainbowLiveExecutionPlanController}`, `rainbow-live.js`).

**Le plan n'envoie rien.** Il calcule et enregistre ce qu'on achèterait / vendrait, par quel chemin et à quel coût ; les classes de `service.execution.plan` et le job de plan ne dépendent d'aucun port d'ordre. L'exécution réelle (re-devis, envoi, audit) est un composant séparé, verrouillé : [`10-execution-reelle.md`](10-execution-reelle.md). Chemin d'achat chiffré (catalogue, frais, carnet, `PathFinder`, Fee Test) : [`08-rainbow-live.md`](08-rainbow-live.md) §Chemin d'achat chiffré.

## Mode et activation

- `tradeio.execution.mode` ∈ `OFF|DRY_RUN|LIVE` (`ExecutionSettings`, défaut `DRY_RUN`). `OFF` coupe aussi le dry-run (aucun plan). **`LIVE` est refusé au démarrage** (`IllegalStateException`) sans `tradeio.execution.live-unlocked=true` et sans `TRADEIO_MASTER_KEY` ([`10-execution-reelle.md`](10-execution-reelle.md)) ; valeur inconnue refusée aussi.
- `RainbowLiveBinding.executionEnabled` (défaut `false`, modifié par `PUT /api/rainbow-live/bindings/{id}/execution`). Binding non activé ⇒ actif `DISABLED` (visible, sans étape) ; tous les actifs désactivés ⇒ plan `DISABLED`.
- Constantes mutualisées (`ExecutionDefaults`) : durée de vie du plan 5 min (`tradeio.execution.plan-ttl`), tolérance de slippage du prix plafond 0,1 % (`tradeio.execution.slippage-tolerance-pct`), seuils Fee Test, nœuds interdits (fiat + `USDT`), intermédiaires (`BTC`, `ETH`) dont seul `BTC` est choisi automatiquement, profondeur max 2.

## Déclenchement

- `ExecutionPlanJob` (`service/scheduler`) : `tradeio.execution.plan-cron`, défaut `-` (désactivé), cron cible `0 58 23 * * *`, `zone = "UTC"` ; plan du jour UTC (`RainbowLiveExecutionService.dayFor(now)`). Il lit les runs **en base** (la passe quotidienne ne dépend pas du plan).
- Manuel : `POST /api/admin/execution/plan[?day=]` (ADMIN) ⇒ `PlanSummary{day, planned, blocked, disabled, unchanged, skipped, errors}`. Une erreur sur un user n'arrête pas les autres ; pas de rattrapage des jours manquants ; user sans run du jour ⇒ ignoré.

- Un plan `EXPIRED` n'est pas régénéré tant que les entrées (hash) sont identiques : la réponse est `unchanged`. Pour repartir de zéro sans changement d'entrées, `OrderPlanService#resetPlan` (console admin, bouton « Réinitialiser le plan », `POST /api/admin/execution/plans/{id}/reset`) force une nouvelle révision, uniquement si aucune étape n'est partie (statut `PLANNED`/expiré/`BLOCKED`/`DISABLED`/`CANCELLED`, toutes étapes `PLANNED`) ; mêmes `clOrdId` (rien n'a été envoyé). Un plan exécuté, partiel, en échec ou à réconcilier ne se réinitialise jamais. `clOrdId` ne contient pas de révision. Les plafonds lisent le `baseAmount` du run du preset au `run_day` du plan (`NO_BASE_AMOUNT` sinon).

## Carry : état dérivé, pas de compteur

Le carry n'est pas stocké sur le binding : `carry_in` = `carry_out` de la dernière ligne d'actif (même user, wallet et actif) d'un plan courant (hors `SUPERSEDED`) **strictement antérieur** au jour planifié, quel que soit l'écart de jours (Rainbow n'achète pas tous les jours). Rejeu, révision et reset d'un plan recalculent donc la même valeur. Il est remis à zéro quand le `base_amount` du preset a changé depuis cette ligne (`base_amount` = instantané du `baseAmount` du run du preset au jour du plan) ; base inconnue d'un côté : carry conservé. Il suit sinon les changements de preset et de wallet-actif du binding (clé = user × wallet × actif). Il entre dans le hash des entrées.

## Entrées et idempotence (`OrderPlanService`)

Par user (une transaction par user) : bindings par `priority` + bloc live du `RainbowLiveBindingRun` du jour pour chaque binding (`status`, `actionType`, `actionAmountUsdc` = montant USD nominal figé au 23:55, `actionQuantity`, `cashDetail` par membre). Un même actif peut avoir plusieurs bindings (un par wallet) : chacun donne son propre actif de plan, planifié et exécuté indépendamment. Le **hash des entrées** (SHA-256 : bindings + blocs, jamais les données de marché) pilote l'idempotence : même hash ⇒ rien ne change (rejeu 23:55 ⇒ plan identique, mêmes `clOrdId`) ; hash différent ⇒ la révision courante passe `SUPERSEDED` (ligne d'audit conservée) et une révision `n+1` est créée.

## Planner (`OrderPlanner`, `service/execution/plan`)

Pas de persistance, pas d'écriture, aucune dépendance à un port d'ordre ; lectures de (a) seulement (catalogue en base, frais du compte en cache, carnet public lu une fois par instrument et par plan : `ExchangeLegMarketSource`).

- **Bilan virtuel** par wallet et par membre du groupe USD (USDC seul), initialisé du cash du snapshot, parcouru par `priority` croissante comme `CashLedger`. Les ventes ne créditent pas le bilan du jour. Le **stock en transit** actif du wallet (`TransitStock`) compte comme source d'achat et est déduit de la position du lien portant la même devise ; il entre dans les entrées et le hash du plan.
- **Achat** : `PathFinder` (source = cash USDC + stock en transit, cible = l'actif) donne les routes ; la route choisie est de coût brut minimal parmi les intermédiaires choisis (BTC), ex æquo : moins de jambes. Route ETH chiffrée et affichée, jamais choisie. Jambes dimensionnées en séquence sur le **net reçu** de la précédente (frais déduits), `sz` arrondi au `lotSz`, `minSz`/`costMin` contrôlés. Ordres : `LIMIT_IOC`, prix plafond = prix de référence ± tolérance, arrondi au tick (BUY ↑, SELL ↓). Un stock en transit pour la cible est terminé (jambe 2, `BUY actif-intermédiaire`) avant tout nouvel achat ; à la vente, un stock de sens `SELL` est terminé par `SELL intermédiaire-USDC` en tête du plan de vente.
- **Vente** : quantité du bloc arrondie au `lotSz` (↓), vendue en USDC (route `X>USDC`, ou `X>BTC>USDC` quand seule cette route existe, ex PAXG sur Kraken) ; l'intermédiaire n'est jamais conservé.
- **Fee Test** (somme brute des jambes) : `WARNING` et `RED` ⇒ étapes + avertissement (le niveau est conservé, aucun blocage).
- **Raisons de blocage** (`PlanBlockReason`) : `NO_PATH`, `BELOW_MIN` (taille < `minSz` ou coût < `costMin` du catalogue), `INSUFFICIENT_FUNDS_AFTER_FEES`, `NOT_TRADABLE_WITHOUT_FIAT` (aucune route sans fiat, ou lecture `NOT_TRADABLE`), `READING_UNAVAILABLE` (lecture `UNAVAILABLE`/absente, marché ou catalogue illisible). Action `NONE`/`BLOCKED` du bloc ⇒ `NO_ACTION`, aucune étape. Un actif bloqué n'empêche pas les autres.
- **Carry (achat sous le minimum du provider)** : la mise effective d'un achat = `actionAmountUsdc` du jour + `carry_in`. Si le chemin est `BELOW_MIN`, toute la mise effective est reportée (`carry_out` = mise effective, plan `BLOCKED` pour cet actif, raison conservée) ; si l'achat passe, le carry est consommé (`carry_out` = 0) ; vente, `NO_ACTION`, `DISABLED` et tout autre blocage laissent le carry inchangé (`carry_out` = `carry_in`). Pas de sur-achat jusqu'au minimum.
- **Statut du plan** : `DISABLED` si tous les actifs le sont ; `PLANNED` dès qu'un actif a des étapes (ou rien à faire) ; `BLOCKED` si des actions voulues n'ont donné aucune étape. `totalCostPct` / `feeTestLevel` du plan = le pire parmi les actifs exécutables ; `blockReason` = `BTC:NO_PATH;PAXG:BELOW_MIN`.

## Entités (`model/entity/execution`)

| Table | Contenu |
|---|---|
| `rainbow_live_order_plan` | user, `run_day`, `revision` (unique avec les 2 précédents), `status` (`PLANNED|BLOCKED|DISABLED|SUPERSEDED|EXECUTING|EXECUTED|PARTIAL|FAILED|CANCELLED` persistés ; les quatre derniers de l'exécution réelle), `mode`, coût, niveau Fee Test, `block_reason`, `inputs_hash`, `created_at`, `expires_at` |
| `rainbow_live_order_plan_asset` | par actif : priorité, wallet, action voulue, `outcome` (`OK|BLOCKED|DISABLED|NO_ACTION`), raison, coût, niveau, avertissement, `carry_in_usd`, `carry_out_usd`, `base_amount` (FK plan, `ON DELETE CASCADE`) |
| `rainbow_live_order_event` | audit **append-only** de l'exécution (plan, étape, `clOrdId`, type, charge JSON) ; `execution_control` : ligne unique (kill switch, multiplicateurs) |
| `rainbow_live_order_step` | `step_rank`, actif, `wallet_id`, `inst_id`, `side`, `ord_type` (`LIMIT_IOC`), `sz`, `px` (plafond), `quote_amount`, `cl_ord_id`, frais / spread / slippage %, `status` (`PLANNED|SUBMITTED|FILLED|PARTIAL|REJECTED|CANCELED|UNKNOWN`), `estimation` (`BOOK|TICKER`), et après exécution : `ord_id`, tailles envoyée / remplie, prix moyen, frais (+ devise), quantité reçue, mid du re-devis, slippage réel, horodatages, dernière erreur (FK plan, `ON DELETE CASCADE`) |

`EXPIRED` n'est jamais persisté : le statut effectif est dérivé à la lecture (`PLANNED` dont `expires_at` est atteint). Un plan expiré n'est pas recalculé à entrées identiques.

`clOrdId` (`ClOrdIds`) : `t5` + userId et walletId (base 36, préfixés de leur longueur) + actif + `yyyyMMdd` + rang sur 2 chiffres ; alphanumérique, ≤ 32 caractères, déterministe.

## Lecture utilisateur et page

- `GET /api/rainbow-live/execution-plans[?from=&to=]` (défaut 30 jours) et `/latest` (204 si aucun) : `RainbowLiveExecutionPlanController` → `OrderPlanQueryService` (base uniquement), propriétaire seulement, DTO dédiés (`OrderPlanDtos`), révisions remplacées non exposées. Cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md).
- Panneau live (`rainbow-live.js`, `textContent` uniquement) : bloc « Plan d'exécution (dry-run) » + mention « non envoyé » : statut, jour (23:55 UTC), validité / « expiré », étapes de l'actif sélectionné, coût et badge Fee Test (vert / orange / rouge), raison de blocage. Le panneau d'exécution réelle (kill switch, interrupteur, 1er ordre, audit) est décrit dans [`10-execution-reelle.md`](10-execution-reelle.md) § Page.

## Garde-fous (tests)

`ExecutionPlanArchitectureTest` (ni port / client d'ordre ni `ProviderApiService` en dépendance transitive du plan, du job et des endpoints ; aucune méthode `placeOrder/newOrder/createOrder/cancelOrder/submit`), `ExecutionOrderArchitectureTest`, `ConnectorNoOrderMethodTest` et `RainbowLiveNoExchangeDependencyTest` inchangés, `OrderPlanServiceTest` (mode `LIVE` refusé sans déverrouillage), `ExecutionPlanControllersSecurityTest` (sécurité par méthode active).
