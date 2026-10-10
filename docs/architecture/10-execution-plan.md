# Plan d'ordres dry-run (lot B, étape b)

Vérifié le : 2026-10-10 (`service/execution/**`, `model/entity/execution/**`, `repository/execution/**`, `service/scheduler/ExecutionPlanJob`, `controller/{ExecutionPlanAdminController,RainbowLiveExecutionPlanController}`, `rainbow-live.js`).

**Le plan n'envoie rien.** Il calcule et enregistre ce qu'on achèterait / vendrait, par quel chemin et à quel coût ; les classes de `service.execution.plan` et le job de plan ne dépendent d'aucun port d'ordre. L'exécution réelle (re-devis, envoi, audit) est un composant séparé, verrouillé : [`10-execution-reelle.md`](10-execution-reelle.md). Chemin d'achat chiffré (catalogue, frais, carnet, `PathFinder`, Fee Test) : [`08-rainbow-bench-grandeur-nature.md`](08-rainbow-bench-grandeur-nature.md) §Chemin d'achat chiffré.

## Mode et activation

- `tradeio.execution.mode` ∈ `OFF|DRY_RUN|LIVE` (`ExecutionSettings`, défaut `DRY_RUN`). `OFF` coupe aussi le dry-run (aucun plan). **`LIVE` est refusé au démarrage** (`IllegalStateException`) sans `tradeio.execution.live-unlocked=true` et sans `TRADEIO_MASTER_KEY` ([`10-execution-reelle.md`](10-execution-reelle.md)) ; valeur inconnue refusée aussi.
- `RainbowLiveBinding.executionEnabled` (défaut `false`, modifié par `PUT /api/rainbow-live/bindings/{id}/execution`). Binding non activé ⇒ actif `DISABLED` (visible, sans étape) ; tous les actifs désactivés ⇒ plan `DISABLED`.
- Constantes mutualisées (`ExecutionDefaults`) : durée de vie du plan 5 min (`tradeio.execution.plan-ttl`), tolérance de slippage du prix plafond 0,1 % (`tradeio.execution.slippage-tolerance-pct`), marge de frais du pont 0,3 %, cotation de vente forcée `USDT`, seuils Fee Test, nœuds interdits.

## Déclenchement

- `ExecutionPlanJob` (`service/scheduler`) : `tradeio.execution.plan-cron`, défaut `-` (désactivé), cron cible `0 58 23 * * *`, `zone = "UTC"` ; passe `T2355` du jour UTC. Il lit les runs **en base** (le bench ne dépend pas du plan).
- Manuel : `POST /api/admin/execution/plan[?day=&pass=]` (ADMIN) ⇒ `PlanSummary{day, pass, planned, blocked, disabled, unchanged, skipped, errors}`. Une erreur sur un user n'arrête pas les autres ; pas de rattrapage des jours manquants ; user sans run du jour ⇒ ignoré.

## Entrées et idempotence (`OrderPlanService`)

Par user (une transaction par user) : bindings par `priority` + bloc live de la passe du run du jour (`liveStatus`, `liveActionType`, `liveActionAmountUsdc` = montant USD nominal figé au 23:55, `liveActionQuantity`, `liveCashDetail` par membre). Le **hash des entrées** (SHA-256 : bindings + blocs, jamais les données de marché) pilote l'idempotence : même hash ⇒ rien ne change (rejeu 23:55 ⇒ plan identique, mêmes `clOrdId`) ; hash différent ⇒ la révision courante passe `SUPERSEDED` (ligne d'audit conservée) et une révision `n+1` est créée.

## Planner (`OrderPlanner`, `service/execution/plan`)

Pas de persistance, pas d'écriture, aucune dépendance à un port d'ordre ; lectures de (a) seulement (catalogue en base, frais du compte en cache, carnet public lu une fois par instrument et par plan : `ExchangeLegMarketSource`).

- **Bilan virtuel** par wallet et par membre du groupe USD (USDC, USDT), initialisé du cash du snapshot, parcouru par `priority` croissante comme `CashLedger`. Les ventes ne créditent pas le bilan du jour.
- **Achat** : pour chaque cotation terminale `T` (paire directe `A-T`), solde `T` ≥ montant ⇒ 1 étape ; sinon une jambe **pont** (`PathFinder`, source = autres membres, cible `T`) pour le seul **manque** × (1 + marge 0,3 %), puis l'achat du montant. Candidat de coût brut minimal retenu (ex æquo : moins d'étapes). Ordres : `LIMIT_IOC`, prix plafond = prix de référence ± tolérance, arrondi au tick (BUY ↑, SELL ↓), `sz` arrondi au `lotSz`, `minSz` contrôlé.
- **Vente** : quantité du bloc arrondie au `lotSz` (↓) ; `X-USDT` forcé quand la paire existe, sinon cotation stable naturelle la moins chère + avertissement.
- **Fee Test** (somme brute des jambes) : `WARNING` et `RED` ⇒ étapes + avertissement (le niveau est conservé, aucun blocage).
- **Raisons de blocage** (`PlanBlockReason`) : `NO_PATH`, `BELOW_MIN`, `INSUFFICIENT_FUNDS_AFTER_FEES`, `NOT_TRADABLE_WITHOUT_FIAT` (aucune paire stable, ou lecture `NOT_TRADABLE`), `READING_UNAVAILABLE` (lecture `UNAVAILABLE`/`STALE`/absente, marché ou catalogue illisible). Action `NONE`/`BLOCKED` du bloc ⇒ `NO_ACTION`, aucune étape. Un actif bloqué n'empêche pas les autres.
- **Statut du plan** : `DISABLED` si tous les actifs le sont ; `PLANNED` dès qu'un actif a des étapes (ou rien à faire) ; `BLOCKED` si des actions voulues n'ont donné aucune étape. `totalCostPct` / `feeTestLevel` du plan = le pire parmi les actifs exécutables ; `blockReason` = `BTC:NO_PATH;PAXG:BELOW_MIN`.

## Entités (`model/entity/execution`)

| Table | Contenu |
|---|---|
| `rainbow_live_order_plan` | user, `run_day`, `pass_code`, `revision` (unique avec les 3 précédents), `status` (`PLANNED|BLOCKED|DISABLED|SUPERSEDED|EXECUTING|EXECUTED|PARTIAL|FAILED|CANCELLED` persistés ; les quatre derniers de l'exécution réelle), `mode`, coût, niveau Fee Test, `block_reason`, `inputs_hash`, `created_at`, `expires_at` |
| `rainbow_live_order_plan_asset` | par actif : priorité, wallet, action voulue, `outcome` (`OK|BLOCKED|DISABLED|NO_ACTION`), raison, coût, niveau, avertissement (FK plan, `ON DELETE CASCADE`) |
| `rainbow_live_order_event` | audit **append-only** de l'exécution (plan, étape, `clOrdId`, type, charge JSON) ; `execution_control` : ligne unique (kill switch, multiplicateurs) |
| `rainbow_live_order_step` | `step_rank`, actif, `inst_id`, `side`, `ord_type` (`LIMIT_IOC`), `sz`, `px` (plafond), `quote_amount`, `cl_ord_id`, frais / spread / slippage %, `status` (`PLANNED|SUBMITTED|FILLED|PARTIAL|REJECTED|CANCELED|UNKNOWN`), `estimation` (`BOOK|TICKER`), et après exécution : `ord_id`, tailles envoyée / remplie, prix moyen, frais (+ devise), quantité reçue, mid du re-devis, slippage réel, horodatages, dernière erreur (FK plan, `ON DELETE CASCADE`) |

`EXPIRED` n'est jamais persisté : le statut effectif est dérivé à la lecture (`PLANNED` dont `expires_at` est atteint). Un plan expiré n'est pas recalculé à entrées identiques.

`clOrdId` (`ClOrdIds`) : `t5` + userId (base 36) + actif + `yyyyMMdd` + passe (1 = 23:55, 2 = 00:05) + rang sur 2 chiffres ; alphanumérique, ≤ 32 caractères, déterministe.

## Lecture utilisateur et page

- `GET /api/rainbow-live/execution-plans[?from=&to=]` (défaut 30 jours) et `/latest` (204 si aucun) : `RainbowLiveExecutionPlanController` → `OrderPlanQueryService` (base uniquement), propriétaire seulement, DTO dédiés (`OrderPlanDtos`), révisions remplacées non exposées. Cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md).
- Panneau live (`rainbow-live.js`, `textContent` uniquement) : bloc « Plan d'exécution (simulation) » + mention « non envoyé » : statut, passe, validité / « expiré », étapes de l'actif sélectionné, coût et badge Fee Test (vert / orange / rouge), raison de blocage. Le panneau d'exécution réelle (kill switch, interrupteur, 1er ordre, audit) est décrit dans [`10-execution-reelle.md`](10-execution-reelle.md) § Page.

## Garde-fous (tests)

`ExecutionPlanArchitectureTest` (ni port / client d'ordre ni `ProviderApiService` en dépendance transitive du plan, du job et des endpoints ; aucune méthode `placeOrder/newOrder/createOrder/cancelOrder/submit`), `ExecutionOrderArchitectureTest`, `ConnectorNoOrderMethodTest` et `RainbowLiveNoExchangeDependencyTest` inchangés, `OrderPlanServiceTest` (mode `LIVE` refusé sans déverrouillage), `ExecutionPlanControllersSecurityTest` (sécurité par méthode active).
