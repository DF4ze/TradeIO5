# Endpoints REST

Vérifié le : 2026-10-13 (`controller/**`, dont `RainbowLiveController`, `RainbowLiveBindingController`).

## Web (Thymeleaf, `MainController`)

| Méthode | Chemin | Rôle requis | Description |
|---|---|---|---|
| GET | `/` | public | Page de présentation (landing) ; utilisateur connecté ⇒ redirection `/app` |
| GET | `/login` | public | Formulaire de login (connecté ⇒ `/app`) |
| GET | `/register` | public | Formulaire d'inscription (connecté ⇒ `/app`) ; affiche `ok`, `exception` et les messages de validation reçus en paramètres de redirection |
| GET | `/user` | USER/MODERATOR/ADMIN | Page utilisateur |
| GET | `/admin` | ADMIN | Page admin |

## Authentification (`AuthController`, `/api/auth`)

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| POST | `/api/auth/signinForm` | public | Login formulaire, pose le cookie JWT, restaure l'owner si `archivedAt != null` |
| POST | `/api/auth/signupForm` | public | Inscription formulaire ; le compte créé reçoit toujours `ROLE_USER` (rôles postés ignorés) |
| GET | `/api/auth/signoutForm` | public | Logout (nettoie le cookie JWT) |

Les endpoints JSON (`/signin`, `/signup`, `/signout`) sont **commentés dans le code**, non actifs — seules les variantes `*Form` (POST formulaire, réponses `ModelAndView`) sont en service.

## Décision / scénarios (admin, `/api/admin/decision`)

Tous ces endpoints partagent le préfixe `/api/admin/decision`. Introduits progressivement (Palier 3) pour tester manuellement le pipeline sans passer par la base de données.

| Méthode | Chemin | Rôle | Action |
|---|---|---|---|
| GET | `/opinion?symbol=&scope=` | ADMIN | Calcule une Opinion à la demande (`TreeAnalysisFacade.getOpinion`), scope `LOCAL` utilise les mêmes strategies par défaut que l'orchestrateur automatique |
| GET | `/scenarios[?owner=]` | ADMIN ou API_AGENT | Liste les scénarios actifs (lecture seule) |
| GET | `/decisions[?owner=]` | ADMIN ou API_AGENT | Liste les décisions actives (lecture seule) |
| POST | `/orchestrate` | ADMIN | Déclenche un cycle complet (`DecisionOrchestrator.runCycle()`) — équivalent manuel de `DecisionOrchestratorJob` (désactivé par défaut) |
| POST | `/snapshot` | ADMIN | Déclenche la photo quotidienne — équivalent manuel de `DecisionScenarioSnapshotJob` (désactivé par défaut) |
| POST | `/archive` | ADMIN | Déclenche l'archivage sur inactivité — équivalent manuel de `UserArchivalJob` (désactivé par défaut) |

`owner` : `"SYSTEM"` ou id numérique utilisateur (`ScenarioOwner.fromString`), omis = tous owners.

## ETF flow (admin, `EtfFlowAdminController`)

| Méthode | Chemin | Rôle | Action |
|---|---|---|---|
| POST | `/api/admin/etf-flow/backfill` | ADMIN | Backfill historique BTC+ETH via SoSoValue (synchrone, ~300 lignes/asset, quelques secondes). Idempotent par `(asset, date)` |

## Run Rainbow (admin, `RainbowLiveAdminController`)

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| POST | `/api/admin/rainbow-live/run[?day=YYYY-MM-DD]` | ADMIN | Exécute le run quotidien (tous users actifs, presets `enabled`) sur la bougie D1 ouverte ; `day` (UTC) force le jour. Réponse : `{day, processed, skipped, errors}`. Aucun ordre réel |
| PUT | `/api/admin/rainbow-live/strategies/{id}` | ADMIN | Modifie une stratégie Actif : corps `{analysisWindowMonths, trendConfig}` (mêmes validations que les presets) ⇒ `StrategyDto` avec `revision` + 1 ; prise en compte au prochain run chez les users qui la suivent. 400 `{error}` si invalide |

## Plan d'ordres dry-run (admin, `ExecutionPlanAdminController`)

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| POST | `/api/admin/execution/plan[?day=YYYY-MM-DD]` | ADMIN | Construit et enregistre le plan d'ordres dry-run de tous les users actifs (journalisé `PLAN` ; défaut : jour UTC courant). Réponse : `{day, planned, blocked, disabled, unchanged, skipped, errors}`. **Rien n'est envoyé** (cf. [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md)) |
| POST | `/api/admin/execution/plans/{id}/reset` | ADMIN | Remet à zéro un plan **courant dont aucun ordre n'est parti** (statut `PLANNED`, expiré, `BLOCKED`, `DISABLED` ou `CANCELLED`, étapes toutes `PLANNED`) : recalcule une nouvelle révision même si les entrées sont inchangées (journalisé `PLAN`, cible `reset#<id>`). Réponse : même forme que `/plan`. 400 si le plan est remplacé, exécuté, partiel, en échec ou à réconcilier |
| POST | `/api/admin/execution/run?planId=` | ADMIN | Exécute le plan (`PLANNED`) ou réconcilie un plan `EXECUTING`. Réponse `{planId, status, blocked{actif:raison}, sent}`. Hors `LIVE` déverrouillé ou kill switch engagé : aucun ordre (raison `MODE_NOT_LIVE`/`PORT_UNAVAILABLE`/`KILL_SWITCH` sous la clé `*`). **Si le kill switch était levé à l'appel, il est réengagé en sortie** (même en erreur) et l'appel est journalisé (`RUN` ou `RECONCILE`, + `AUTO_REENGAGE`) |
| GET | `/api/admin/execution/control` | ADMIN | `{killSwitch, maxOrderMultiplier, maxDayMultiplier, updatedAt, updatedBy}` |
| PUT | `/api/admin/execution/kill-switch` | ADMIN | Corps `{engaged}` ⇒ état de contrôle. Défaut au 1ᵉʳ démarrage : engagé. Journalisé (`KILL_SWITCH`) |
| POST | `/api/admin/execution/bindings/{id}/arm` | ADMIN | Arme le 1ᵉʳ ordre réel du binding (remet la confirmation du propriétaire à zéro) ⇒ 204 ; inconnu ⇒ 404 ; journalisé (`ARM_BINDING`) |
| POST | `/api/admin/execution/credentials` | ADMIN | Corps `{userId, provider (OKX|KRAKEN), apiKey, secretKey, passphrase, enabled?}` ⇒ 201 `{id, userId, provider, scope:TRADE, enabled}` (jamais de secret) ; crée ou remplace la clé TRADE chiffrée d'un user ; sans `TRADEIO_MASTER_KEY` ⇒ 409 ; journalisé (`CREDENTIAL_STORED`) |
| GET | `/api/admin/execution/state` | ADMIN | `AdminStateDto` : `{mode, liveUnlocked, liveExecution, masterKeyConfigured, killSwitch, killSwitchUpdatedBy, killSwitchUpdatedAt, maxOrderMultiplier, maxDayMultiplier, absoluteMaxOrderUsd, absoluteMaxDayUsd, orderProviders[]}` (bandeau de `/admin`, aucun secret) |
| GET | `/api/admin/execution/plans[?from=&to=&userId=&status=]` | ADMIN | Plans courants de **tous** les users (révisions `SUPERSEDED` exclues, plus récent d'abord ; défaut : 7 derniers jours UTC ; `from` > `to` ⇒ 400). `AdminPlanDto` = `PlanDto` + `userId`, `username`. Base seule, aucun port d'ordre |
| GET | `/api/admin/execution/plans/{id}/events[?page=&size=]` | ADMIN | Audit du plan (tous users), même forme que côté propriétaire ; inconnu ⇒ 404 |
| GET | `/api/admin/credentials` | ADMIN | `[{id, userId, username, provider, scope, enabled, createdAt, apiKeyHint}]` ; `apiKeyHint` = 4 derniers caractères (READ) ou `chiffrée` (TRADE) ; jamais de secret |
| PUT | `/api/admin/credentials/{id}/enabled` | ADMIN | Corps `{enabled}` ⇒ `AdminCredentialDto` ; inconnu ⇒ 404 ; journalisé (`CREDENTIAL_ENABLED`) |
| GET | `/api/admin/bindings` | ADMIN | Bindings de tous les users : `{id, userId, username, assetSymbol, exchange, walletId, walletName, presetId, presetName, bagPercent, priority, baseAmount, executionEnabled, tradability, firstLiveArmed, firstLiveConfirmed}` (aucun solde) |
| GET | `/api/admin/catalog/instruments?provider=&q=` | ADMIN | Paires du catalogue d'un exchange (filtre `q` sur `instId`, 200 max, lecture seule) : `[{instId, base, quote, state, minSz, lotSz, tickSz, costMin, nativePair, feeCurrency}]` ; catalogue indisponible ⇒ 503 `{error}` |
| POST | `/api/admin/execution/order-test/real` | ADMIN | Envoi d'un ordre RÉEL (testeur) : corps `{order: {userId, provider, instId, side, sz\|quoteAmount, px}, confirm: "ENVOYER"}` ⇒ `{status SENT\|BLOCKED\|REFUSED\|REJECTED\|UNKNOWN, validation, clOrdId, ordId, phase, filledSz, avgPx, fills[{tradeId, px, sz, fee, feeCcy}], message}` ; confirmation absente ⇒ 400 ; kill switch levé requis puis rengagé ; journal `ORDER_SEND` |
| POST | `/api/admin/execution/order-test/reconcile` | ADMIN | Relecture seule d'un ordre du testeur : corps `{userId, provider, instId, clOrdId}` ⇒ même forme (`RECONCILED\|NOT_FOUND\|BLOCKED\|UNKNOWN`) ; journal `ORDER_READ` |
| POST | `/api/admin/execution/order-test/validate` | ADMIN | Corps `{userId, provider, instId, side, sz\|quoteAmount, px}` ⇒ `{accepted, blockedBy, localChecks[{name, ok, detail}], normalizedRequest{instId, side, ordType, sz, px, cost}, exchangeCode, message, rawFiltered, limits{minSz, costMin, lotSz, tickSz, feeCurrency}}`. **Aucun ordre créé** : Kraken `AddOrder validate=true`, OKX contrôles locaux seuls. Mêmes verrous qu'un ordre réel (`blockedBy` : `MODE_NOT_LIVE, LIVE_LOCKED, MASTER_KEY_MISSING, KILL_SWITCH, PORT_UNAVAILABLE, CREDENTIAL_INVALID`, sans appel exchange) ; demande incohérente ⇒ 400 ; journalisé (`ORDER_VALIDATE`) |
| POST | `/api/admin/execution/order-test/validate-route` | ADMIN | Valide une route multi-jambes sans rien envoyer : corps `{legs:[{userId, provider, instId, side, sz\|quoteAmount, px}…]}` ⇒ `{legs:[ValidationReportDto…], allAccepted, note?}` ; `note` signale une jambe suivante refusée faute de fonds (attendu tant que l'intermédiaire n'est pas détenu). Journal `ORDER_VALIDATE` par jambe |
| GET | `/api/admin/actions[?page=&size=]` | ADMIN | Journal `admin_action_log`, plus récent d'abord : `{page, size, totalElements, items[{id, actor, action, target, payload, outcome, createdAt}]}` ; `size` borné (max 100) |
| POST | `/api/credentials/read` | authentifié | Saisie de **sa propre** clé de LECTURE : corps `{provider (OKX\|KRAKEN), apiKey, secretKey, passphrase (OKX), enabled?}` (aucun `userId`) ⇒ 201 `{id, provider, scope:READ, enabled}` ; stockée chiffrée, crée ou rattache le wallet de l'exchange (rotation = mise à jour) ; sans `TRADEIO_MASTER_KEY` ⇒ 409 ; exchange non géré ou champ manquant ⇒ 400 |
| POST | `/api/credentials/trade` | authentifié | Saisie de **sa propre** clé TRADE : corps `{provider (OKX|KRAKEN), apiKey, secretKey, passphrase, enabled?}` (aucun `userId` : toujours l'utilisateur connecté) ⇒ 201 `{id, provider, scope:TRADE, enabled}` ; sans `TRADEIO_MASTER_KEY` ⇒ 409 ; journalisé (`CREDENTIAL_STORED`, sans secret) |
| GET | `/api/rainbow-live/wallets` | authentifié | Wallets de l'utilisateur connecté : `[{id, name, exchange}]` (liste déroulante de la création de binding) |
| POST | `/api/rainbow-live/onboarding/link` | authentifié | Corps `{walletId}` : `BindingCheck` puis un lien **inactif** par actif BTC/ETH/PAXG sur le preset système ; réponse `[{assetSymbol, outcome (LINKED\|ALREADY_LINKED\|NOT_LINKED), bindingId, presetName, status, message}]` (non lié : statut du check + raison). Idempotent ; wallet d'un autre user ⇒ 404 |
| GET | `/api/rainbow-live/onboarding/proposals?walletId=&days=` | authentifié | `days` optionnel = durée d'étalement du cash (défaut : `autonomyDays` admin ; hors bornes absolues 7–365 ⇒ 400) ; réponse `{autonomy{defaultDays, minDays, maxDays}, proposals[{bindingId, assetSymbol, currentBaseAmount, presetBaseAmount, cashAllocated, averageMultiplier, autonomyDays, proposedBaseAmount, belowMinimum, feeTestLevel, warning}], suggestedBand{exposureMin, exposureMax}}` ; lecture seule (soldes + devis du chemin), rien enregistré : l'application passe par `PUT /bindings/{id}` `{baseAmount}` |

Erreurs des endpoints de la page Admin (`AdminControllerAdvice`) : 404/409/400 en JSON `{error}`.

## Bindings Rainbow live (utilisateur, `RainbowLiveBindingController`, `/api/rainbow-live/bindings`)

Collection Postman (login, création de binding, `path-quote`) : [`tools/postman/tradeio5-path-quote.postman_collection.json`](../../tools/postman/tradeio5-path-quote.postman_collection.json).

Authentifié, scopé à l'user ; binding / preset / wallet d'un autre user ou inexistant ⇒ 404. Erreurs via `RainbowLiveControllerAdvice` (`{error}`). Aucun endpoint de ce contrôleur n'envoie d'ordre (l'interrupteur et la confirmation ne font que changer l'état du binding). Aucun solde exposé.

| Méthode | Chemin | Description |
|---|---|---|
| GET | `` | Liste `BindingDto` `{id, assetSymbol, presetId, presetName, walletId, walletName, exchange, bagPercent, priority, baseAmount?, tradability?, quoteMember?, routeKey?, executionEnabled, firstLiveArmed, firstLiveConfirmed}` (par priorité ; `tradability` = dernier statut `BindingCheck` persisté) |
| POST | `` | Corps `{assetSymbol, presetId, walletId, bagPercent?, priority?, baseAmount?}` (défauts : 100, rang de l'actif, montant du preset) ⇒ 201 `{binding, check}` ; binding existant pour (actif, wallet) ⇒ 409 (un même preset peut alimenter plusieurs bindings) ; preset d'un autre actif / `bagPercent` hors [0,100] / actif inconnu ⇒ 400 |
| GET | `/candidates?walletId=&asset=` | Presets proposables pour (wallet, actif) (crée au besoin les presets système de l'user) : `[{id, name, mode (FIXED|TREND_MIX), origin (SYSTEM|CUSTOM), alreadyLinkedBindingId?}]` ; wallet d'un autre user ⇒ 404, actif inconnu ⇒ 400 |
| GET | `/{id}` | `BindingDto` |
| PUT | `/{id}` | Corps `{presetId?, walletId?, bagPercent?, priority?, baseAmount?, resetBaseAmount?}` (actif immuable, champs nuls inchangés ; `baseAmount` > 0 sinon 400 ; `resetBaseAmount=true` = retour au montant du preset) ⇒ `{binding, check}` ; changer `presetId` = bascule du preset live |
| DELETE | `/{id}` | 204 (le preset reste) |
| PUT | `/{id}/execution` | Corps `{enabled}` : interrupteur d'exécution du binding (défaut éteint) ⇒ `BindingDto` |
| POST | `/{id}/confirm-first-live` | Confirmation du 1ᵉʳ ordre réel par le propriétaire ; sans armement admin préalable ⇒ 409 ⇒ `BindingDto` |
| GET | `/{id}/check` | Revérifie : `{status, message, ok, executable, blocked, quoteMember?, routeKey?}`, `status` ∈ `OK, TRADABLE_VIA_INTERMEDIARY, NOT_TRADABLE_WITHOUT_FIAT, WALLET_DISABLED, CREDENTIAL_INVALID, PROVIDER_UNSUPPORTED, BALANCE_UNAVAILABLE, INSTRUMENT_UNAVAILABLE` (appelle l'exchange en lecture seule et persiste le statut sur le binding) |
| GET | `/{id}/path-quote?amount=` | Devis à la demande de la route d'achat de `amount` USD vers l'actif du binding (lecture seule, aucun ordre) : `{status OK\|NO_PATH\|BELOW_MIN, source, target, amount, legs[{instId, side BUY\|SELL, sz, refPrice, feePct, spreadPct, slippagePct, costPct, belowMin}], totalCostPct, feeTestLevel GREEN\|WARNING\|RED, estimation BOOK\|TICKER, underfunded, routeKey, routes[{routeKey, source, legs[], totalCostPct, feeTestLevel, status, estimation, chosen, warnings[]}], warning?}` (`routes` : toutes les routes chiffrées, `chosen=false` pour ETH, affiché jamais choisi) (pourcentages en %). Binding d'un autre user ⇒ 404 ; `amount` absent/≤ 0 ⇒ 400 ; clé rejetée ⇒ 409 ; soldes/frais/catalogue/carnet indisponibles ou exchange non pris en charge ⇒ 503 |

## Wallet réel des presets live (utilisateur, `RainbowLiveWalletController`, `GET /api/rainbow-live/live-wallet`)

Authentifié, scopé à l'user. Lit **uniquement la base** (bindings + dernier `RainbowLiveBindingRun` de chaque lien) : aucun appel exchange. Liste vide sans binding ; une entrée par binding (un actif sur deux wallets ⇒ deux éléments). Élément `LiveAssetDto` : `{assetSymbol, bindingId, presetId, presetName, walletId, walletName, exchange, bagPercent, priority, tradability, tradabilityMessage (texte d'avertissement si `NOT_TRADABLE_WITHOUT_FIAT`, sinon nul), executionEnabled, firstLiveArmed, firstLiveConfirmed, routeKey, transit[{currency, quantity, finishSide BUY\|SELL, createdAt}], snapshot}` ; `snapshot` nul tant qu'aucun run live n'est joué, sinon `{day, status (OK|UNAVAILABLE|NOT_TRADABLE), fetchedAt, cashUsd (groupe USD), cashByMember {USDC, USDT}, positionQty, tradableQty, cashReserved, blockReason (NONE|INSUFFICIENT_CASH|UNAVAILABLE|NOT_TRADABLE_WITHOUT_FIAT), liveActionType (BUY|SELL|NONE|BLOCKED), liveActionAmountUsdc, liveActionQuantity, modulation (journal JSON de modulation ou nul)}`. L'action live est **recommandée, non exécutée**. Ni credential, ni secret, ni solde hors périmètre.

## Patrimoine et performance (utilisateur, `PortfolioController`)

Base seule (instantanés quotidiens `portfolio_snapshot` et transactions), aucun appel exchange, scopé à l'utilisateur connecté.

| Endpoint | Réponse |
|---|---|
| `GET /api/portfolio/summary?period=7d\|30d\|90d\|1y\|all` (défaut `30d`) | `PortfolioSummaryDto` : `series[{day, total, stable, assets, exposurePct}]` (un point par jour, wallets additionnés ; valeurs nulles si un wallet n'était pas exploitable), `current` (dernier point), `pnl{amount, pct, since}` = variation de valeur entre le premier et le dernier point exploitable (nul sous deux points), `autonomyDays` (stable ÷ dépense quotidienne moyenne des liens, nul si indéterminable), `flowsIncluded=false` : **dépôts/retraits non suivis** (mention à afficher). Période inconnue ⇒ 400 |
| `GET /api/portfolio/assets` | `[AssetPerformanceDto]` par actif du dernier instantané : `quantity, price, value, investedNet` (achats − ventes, frais exclus), `pnl` (valeur − investi net), `pnlPct`, `avgBuyPrice` ; nuls si le prix manque |
| `GET /api/portfolio/holdings[?walletId=]` (`HomeOverviewService`) | `HoldingsDto` : **soldes live** (lecture seule des exchanges via `ReadOnlyBalanceReader`, cache 60 s) de tous les wallets activés — avec ou sans passe — valorisés au dernier cours D1 (cache DB des bougies, jamais un prix par exchange). `{walletId, fetchedAt, total, stable, assetsValue, exposurePct, partial, assets[{assetSymbol, quantity, price, value, sharePct, stable, parts[{walletId, walletName, exchange, quantity, value}]}], unavailable[{walletId, walletName, exchange, reason}]}` ; actifs identiques regroupés (détail dans `parts`), stablecoins (USDC, USDT, DAI… + groupe USD) au nominal ; `partial=true` si un actif n'a pas de cours (exclu des totaux). Wallet inconnu ou d'un autre user ⇒ 404 |
| `GET /api/portfolio/history[?walletId=&period=]` | `PortfolioHistoryDto` `{walletId, period, from, to, estimated=true, flowsIncluded=false, pnl, series[{day, total, stable, assets, exposurePct}]}` : historique **reconstruit** = soldes actuels remontés à rebours en annulant les `Transaction` (frais en actif ou en quote), valorisés à la clôture D1 du jour (720 j max). Estimation : dépôts/retraits non suivis. Jour sans cours ⇒ valeurs nulles |
| `GET /api/portfolio/transactions[?walletId=]` | `[TradeLineDto]` `{id, timestamp, side, assetSymbol, quantity, price, notional, fee, feeCurrency, walletId, walletName, exchange}`, plus récent d'abord, 300 max |
| `GET /api/rainbow-live/bindings/{id}/bands?period=&simulated=` (`RainbowLiveBandsController`, période défaut `90d`, `simulated` défaut `false` ; `simulated=true` ⇒ bandes recalculées par `RainbowBandsSimulator` sur les bougies D1 du lien, Trend Mix : jeu Bull/Bear du régime du jour, `simulated=true` dans la réponse, aucune écriture) | `RainbowBandsDto` : bandes **figées** (valeurs vues par le moteur à chaque passe) du preset du lien, un point par jour joué `{day, close, sma, atr, boundDown2, boundDown1, boundUp1, boundUp2, boundUp3, zone, activeSet, trendRegime}`, `simulated=false` (bandes recalculées avant le premier run : non faites). Période invalide ⇒ 400 ; lien d'autrui ou inconnu ⇒ 404 |
| `GET /api/rainbow-live/next-pass` (`RainbowLiveNextPassController`) | `{enabled, nextRunAt}` : prochain déclenchement du cron de la passe (UTC) ; `enabled=false` et `nextRunAt` nul si le planificateur est désactivé |
| `GET /api/rainbow-live/bindings/{id}/transactions` | `BindingTradesDto` : `avgBuyPrice` et `trades[{id, timestamp, side, quantity, price, fee, feeCurrency}]` du wallet et de l'actif du lien ; lien inconnu ou d'un autre utilisateur ⇒ 404 |

## Décisions quotidiennes (utilisateur, `RainbowLiveDecisionController`, `GET /api/rainbow-live/decisions`)

Lecture seule, base uniquement, scopé à l'utilisateur connecté. `?from=&to=&assetSymbol=&walletId=` (défaut : 30 derniers jours UTC). `[DecisionDto]` du plus récent au plus ancien : `{bindingId, assetSymbol, walletId, day, status, action, amountUsdc, quantity, blockReason, modulation (JSON texte), computedAt}` (snapshot live de la passe, indépendant de l'exécution).

## Préférences et alertes (utilisateur, `/api/user`)

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/preferences` | `{theme, displayMode SIMPLE\|ADVANCED\|EXPERT, startingProfile, alertsDisabled[AlertType], caps{maxOrderUsd, maxDayUsd, absoluteMaxOrderUsd, absoluteMaxDayUsd}, warnings[]}` : valeurs par défaut si jamais réglé |
| PUT | `/preferences` | Corps `{theme, alertsDisabled, maxOrderUsd, maxDayUsd}` (remplace le bloc ; plafonds nuls = pas de plafond personnel). Plafond > plafond absolu (30 USD/ordre, 100 USD/jour) ou valeur invalide ⇒ 400, rien n'écrit. `warnings` non vide dès 80 % du plafond absolu |
| PUT | `/preferences/profile` | Corps `{profile PRUDENT\|BALANCED\|DYNAMIC}` : applique la bande d'exposition du profil (20–50 / 30–70 / 40–90), réponse `{profile, modulation}` ; profil absent ⇒ 400 |
| GET | `/alerts` | `[AlertDto{type, severity WARNING\|ERROR, assetSymbol, bindingId, message, day}]` dérivés des statuts existants (aucun stockage), seulement dans l'app, hors types coupés : `BUY_BLOCKED, DATA_UNAVAILABLE, NOT_TRADABLE, CREDENTIAL_PROBLEM, PLAN_PROBLEM, TRANSIT_STOCK` |

Les plafonds personnels ne font que **resserrer** les plafonds absolus de l'exécution (`OrderExecutor` : min des deux, raisons `CAP_ORDER`/`CAP_DAY`).

## Plans d'ordres (utilisateur, `RainbowLiveExecutionPlanController`, `/api/rainbow-live/execution-plans`)

Authentifié, propriétaire seulement (jamais le plan d'un autre user), lecture de la base uniquement ; ces endpoints n'envoient rien. Un plan peut porter les résultats d'une exécution réelle (statuts d'étape, fills, frais, slippage réel).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `?from=&to=&assetSymbol=&walletId=&status=&blockReason=` | Plans courants (hors révisions remplacées) entre deux jours UTC inclus (défaut : 30 derniers jours), du plus récent au plus ancien ; filtres serveur facultatifs : `assetSymbol`/`walletId` (un actif du plan correspond), `status` du plan, `blockReason` (plan, exécution ou actif). `PlanDto` `{id, day, revision, status PLANNED\|BLOCKED\|EXPIRED\|DISABLED, mode, totalCostPct, feeTestLevel, blockReason?, createdAt, expiresAt, assets[{assetSymbol, action, outcome OK\|BLOCKED\|DISABLED\|NO_ACTION, blockReason?, costPct, feeTestLevel, warning?, steps[{rank, instId, side, ordType, sz, px, quoteAmount, clOrdId, feePct, spreadPct, slippagePct, estimation}], walletId, carryInUsd, carryOutUsd}]}` ; `status` effectif : `EXPIRED` après `expiresAt` (5 min) |
| GET | `/latest` | Plan courant le plus récent ; 204 sans plan. `status` ∈ `PLANNED\|BLOCKED\|EXPIRED\|DISABLED\|EXECUTING\|EXECUTED\|PARTIAL\|FAILED\|CANCELLED` ; en plus : `executionBlockReason?`, `executionStartedAt?`, `executionFinishedAt?` ; par étape : `status`, `filledSz?`, `avgFillPx?`, `feeAmount?`, `feeCurrency?`, `receivedAmount?`, `receivedCurrency?`, `realSlippagePct?`, `lastError?` |
| GET | `/{id}/events?page=0&size=20` | Audit du plan en lecture seule, du plus ancien au plus récent (taille bornée à 100) : `{page, size, totalElements, items[{id, stepId?, clOrdId?, type, payload (JSON texte, sans secret), createdAt}]}` ; plan d'un autre user ou inexistant ⇒ 404 |

## État de l'exécution (utilisateur, `RainbowLiveExecutionStateController`, `GET /api/rainbow-live/execution-state`)

Authentifié. `{mode OFF\|DRY_RUN\|LIVE, liveExecution (LIVE déverrouillé), killSwitch}` : affichage seul, aucun secret ; le kill switch se pilote par `PUT /api/admin/execution/kill-switch`.

## Rainbow live (utilisateur, `RainbowLiveController`, `/api/rainbow-live`)

Authentifié (`@PreAuthorize("isAuthenticated()")` sur la classe), scopé à l'utilisateur connecté ; preset d'un autre user ou inexistant ⇒ 404. JSON, dates `YYYY-MM-DD` (jour UTC), enums en chaîne. Détail : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/defaults` | `{assets, cashCurrency, defaultName, analysisWindowMonths, baseAmount, configs{BTC,ETH,PAXG → {tuning, globals}}, reentryModes[], zones[{code,name,label}], trendDefaults{actif → TrendConfigDto}, rangeMappings[], uiModes[], systemPrefix}` (enum `ReentryMode` et libellés de zones fournis par le serveur) |
| GET / PUT | `/ui-mode` | Mode d'affichage de l'user : `{mode: AUTO\|SIMPLE\|ADVANCED\|EXPERT}` (défaut `EXPERT`) ; valeur invalide ⇒ 400 |
| GET | `/strategies` | Stratégies Actif, **lecture seule** côté user (`StrategyDto`) ; l'édition est réservée à System (endpoint admin) |
| GET | `/preset-events[?asset=BTC]` | Historique des switchs de preset du user (`PresetEventDto`), du plus récent au plus ancien |
| GET | `/presets[?asset=BTC]` | Crée les presets qui suivent les stratégies manquants (inactifs, `followsStrategy=true`) puis liste `PresetDto` ; actif hors BTC/ETH/PAXG ⇒ 400 |
| POST | `/presets` | Corps `{assetSymbol, name, enabled, analysisWindowMonths, tuning, globals[, duplicatedFromId]}` ⇒ 201 `PresetDto` (`duplicatedFromId` = preset dupliqué : s'il suit une stratégie, la création est un détachement, événement `DETACH`) ; preset Trend Mix : `mode:"TREND_MIX"` + `trendConfig` (sans `tuning`/`globals`) ; 400 (validation, nom préfixé par le préfixe système réservé), 409 (nom déjà pris pour l'actif) |
| GET | `/presets/{id}` | `PresetDto` |
| PUT | `/presets/{id}` | Corps `{name, enabled, analysisWindowMonths, tuning, globals}` (Trend Mix : `trendConfig` à la place, absent ⇒ inchangé) (actif non modifiable) ⇒ `PresetDto` ; effet au run suivant, historique conservé ; preset qui suit une stratégie ⇒ 409 |
| PATCH | `/presets/{id}/enabled` | Corps `{enabled}` ⇒ `PresetDto` ; seule modification permise sur un preset qui suit une stratégie |
| DELETE | `/presets/{id}` | 204 ; supprime ses runs ; preset qui suit une stratégie ⇒ 409 |

Réponses (noms de champs) :
- `PresetDto` : `id, assetSymbol, name, enabled, analysisWindowMonths, createdAt, updatedAt, tuning, globals, runCount, firstRunDay?, lastRun?{day, close, zone, actionType, actionAmountUsdc, actionQuantity, activeSet?, trendRegime?}, mode (FIXED|TREND_MIX), trendConfig?` (Trend Mix), `followsStrategy`, `strategyRevision?` (révision suivie, null si preset propre) ; `TrendConfigDto` = `{trend{shortWindow, mediumWindow, longWindow, slopeScale, enter, exit, confirm, smaPeriod, atrPeriod, atrMultiplier, wickDown, wickUp}, rangeMapping, bear{tuning, globals}, bull{tuning, globals}}` (`tuning` = champs de `RainbowAtrTuning`, `globals` = champs de `RainbowAtrGlobals`, `baseAmount` inclus).
- `StrategyDto` : `id, assetSymbol, name, mode, revision, updatedAt, analysisWindowMonths, config{tuning, globals}, trendConfig?` (Trend Mix).
- `PresetEventDto` : `id, assetSymbol, type (ENABLE|DISABLE|DETACH|LIVE_SWITCH|STRATEGY_CHANGED), presetBeforeId?, presetBeforeName?, presetAfterId?, presetAfterName?, strategyRevision?, occurredAt, reason?`.
- Erreurs : `{"error": "<message>"}` (400 / 404 / 409, `RainbowLiveControllerAdvice` ; 409 = nom déjà pris ou preset qui suit une stratégie (verrouillé)).

## Modulation Rainbow (paramètres admin et réglages utilisateur)

Les réglages module les quantités des achats/ventes du Rainbow (cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md)) ; aucun ordre n'est créé par la modulation.

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| GET | `/api/admin/rainbow-modulation` | ADMIN | `{parameters{<nom>{value, defaultValue, min, max}}, updatedAt, updatedBy}` (8 paramètres, bornes absolues incluses) |
| PUT | `/api/admin/rainbow-modulation` | ADMIN | Corps = jeu complet `{regulationMarginPct, sellBoostMax, biasMax, powerMin, powerMax, minBandWidthPts, modulationProductMax, autonomyDays}` ⇒ même forme que le GET ; hors bornes ou `powerMin ≤ 1 ≤ powerMax` violé ⇒ 400 `{error}` ; journal `MODULATION` |
| GET | `/api/user/modulation-settings` | authentifié | `{settings{exposureMin, exposureMax, power, bias} (stockés ou neutres 0/100/1/0), limits{minBandWidthPts, powerMin, powerMax, biasMax}}` |
| PUT | `/api/user/modulation-settings` | authentifié | Corps `{exposureMin, exposureMax, power, bias}` (tous obligatoires) ⇒ même forme que le GET ; validation contre les limites admin courantes, 400 `{error}` sinon ; `riskCursor` inchangé. Pris en compte à la passe suivante (valeurs clampées aux bornes admin à ce moment-là) |

## Veille média (admin, `MediaWatchAdminController`)

| Méthode | Chemin | Rôle | Action |
|---|---|---|---|
| POST | `/api/admin/media-watch/ingest` | ADMIN | Relance immédiate de l'ingestion RSS+transcript |
| POST | `/api/admin/media-watch/extract` | ADMIN | Relance immédiate de la classification+extraction LLM |

## Autres

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| GET | `/api/overview?quoteCurrency=&exchangeCode=` | authentifié | Positions détenues par l'utilisateur connecté (`AssetOverviewService`) ; un wallet dont les soldes sont illisibles (ou sans client d'exchange, ex. OKX) est ignoré (WARN) |
| GET/PUT | `/api/user/risk-cursor` | authentifié | Curseur de risque continu (0-10) de l'utilisateur connecté |
| GET | `/api/calibration/zones?symbol=` | **public, volontairement sans `@PreAuthorize`** | Recalcul à la demande des zones de consolidation/régime ADX, pour l'outil de diagnostic `tools/calibration/zone_view_v2.html`. `@CrossOrigin("*")` car ouvert en `file://` |

Voir [`architecture/07-security.md`](../architecture/07-security.md) pour le fonctionnement réel de l'autorisation (la chaîne de filtres URL est permissive ; seules les annotations `@PreAuthorize` protègent réellement).
