# Endpoints REST

Vérifié le : 2026-10-10 (`controller/**`, dont `RainbowLiveController`, `RainbowLiveBindingController`).

## Web (Thymeleaf, `MainController`)

| Méthode | Chemin | Rôle requis | Description |
|---|---|---|---|
| GET | `/` | public | Accueil, positions de l'utilisateur connecté si authentifié |
| GET | `/login` | public | Formulaire de login |
| GET | `/register` | public | Formulaire d'inscription |
| GET | `/user` | USER/MODERATOR/ADMIN | Page utilisateur |
| GET | `/admin` | ADMIN | Page admin |

## Authentification (`AuthController`, `/api/auth`)

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| POST | `/api/auth/signinForm` | public | Login formulaire, pose le cookie JWT, restaure l'owner si `archivedAt != null` |
| POST | `/api/auth/signupForm` | public | Inscription formulaire |
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

## Bench Rainbow (admin, `RainbowLiveAdminController`)

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| POST | `/api/admin/rainbow-live/run?pass=T2355\|T0005[&day=YYYY-MM-DD]` | ADMIN | Exécute une passe du bench grandeur nature (tous users actifs, presets `enabled`) ; `day` (UTC) force le jour. Réponse : `{pass, day, processed, skipped, errors}`. Aucun ordre réel |
| PUT | `/api/admin/rainbow-live/strategies/{id}` | ADMIN | Modifie une stratégie Actif : corps `{analysisWindowMonths, trendConfig}` (mêmes validations que les presets) ⇒ `StrategyDto` avec `revision` + 1 ; prise en compte à la prochaine passe chez les users qui la suivent. 400 `{error}` si invalide |

## Plan d'ordres dry-run (admin, `ExecutionPlanAdminController`)

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| POST | `/api/admin/execution/plan[?day=YYYY-MM-DD][&pass=T2355\|T0005]` | ADMIN | Construit et enregistre le plan d'ordres dry-run de tous les users actifs (défaut : passe `T2355`, jour UTC de la passe). Réponse : `{day, pass, planned, blocked, disabled, unchanged, skipped, errors}`. **Rien n'est envoyé** (cf. [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md)) |
| POST | `/api/admin/execution/run?planId=` | ADMIN | Exécute le plan (`PLANNED`) ou réconcilie un plan `EXECUTING`. Réponse `{planId, status, blocked{actif:raison}, sent}`. Hors `LIVE` déverrouillé ou kill switch engagé : aucun ordre (raison `MODE_NOT_LIVE`/`PORT_UNAVAILABLE`/`KILL_SWITCH` sous la clé `*`) |
| GET | `/api/admin/execution/control` | ADMIN | `{killSwitch, maxOrderMultiplier, maxDayMultiplier, updatedAt, updatedBy}` |
| PUT | `/api/admin/execution/kill-switch` | ADMIN | Corps `{engaged}` ⇒ état de contrôle. Défaut au 1ᵉʳ démarrage : engagé |
| POST | `/api/admin/execution/bindings/{id}/arm` | ADMIN | Arme le 1ᵉʳ ordre réel du binding (remet la confirmation du propriétaire à zéro) ⇒ 204 ; inconnu ⇒ 404 |
| POST | `/api/admin/execution/credentials` | ADMIN | Corps `{userId, provider (OKX), apiKey, secretKey, passphrase, enabled?}` ⇒ 201 `{id, userId, provider, scope:TRADE, enabled}` (jamais de secret) ; crée ou remplace la clé TRADE chiffrée ; sans `TRADEIO_MASTER_KEY` ⇒ 409 |

## Bindings Rainbow live (utilisateur, `RainbowLiveBindingController`, `/api/rainbow-live/bindings`)

Collection Postman (login, création de binding, `path-quote`) : [`tools/postman/tradeio5-path-quote.postman_collection.json`](../../tools/postman/tradeio5-path-quote.postman_collection.json).

Authentifié, scopé à l'user ; binding / preset / wallet d'un autre user ou inexistant ⇒ 404. Erreurs via `RainbowLiveControllerAdvice` (`{error}`). Aucun endpoint de ce contrôleur n'envoie d'ordre (l'interrupteur et la confirmation ne font que changer l'état du binding). Aucun solde exposé.

| Méthode | Chemin | Description |
|---|---|---|
| GET | `` | Liste `BindingDto` `{id, assetSymbol, presetId, presetName, walletId, walletName, exchange, bagPercent, priority, tradability?, quoteMember?, bridgePair?, executionEnabled, firstLiveArmed, firstLiveConfirmed}` (par priorité ; `tradability` = dernier statut `BindingCheck` persisté) |
| POST | `` | Corps `{assetSymbol, presetId, walletId, bagPercent?, priority?}` (défauts : 100, rang de l'actif) ⇒ 201 `{binding, check}` ; binding existant pour l'actif ⇒ 409 ; preset d'un autre actif / `bagPercent` hors [0,100] / actif inconnu ⇒ 400 |
| GET | `/{id}` | `BindingDto` |
| PUT | `/{id}` | Corps `{presetId?, walletId?, bagPercent?, priority?}` (actif immuable, champs nuls inchangés) ⇒ `{binding, check}` ; changer `presetId` = bascule du preset live |
| DELETE | `/{id}` | 204 (le preset reste) |
| PUT | `/{id}/execution` | Corps `{enabled}` : interrupteur d'exécution du binding (défaut éteint) ⇒ `BindingDto` |
| POST | `/{id}/confirm-first-live` | Confirmation du 1ᵉʳ ordre réel par le propriétaire ; sans armement admin préalable ⇒ 409 ⇒ `BindingDto` |
| GET | `/{id}/check` | Revérifie : `{status, message, ok, executable, blocked, quoteMember?, bridgePair?}`, `status` ∈ `OK, TRADABLE_VIA_BRIDGE, NOT_TRADABLE_WITHOUT_FIAT, WALLET_DISABLED, CREDENTIAL_INVALID, PROVIDER_UNSUPPORTED, BALANCE_UNAVAILABLE, INSTRUMENT_UNAVAILABLE` (appelle l'exchange en lecture seule et persiste le statut sur le binding) |
| GET | `/{id}/path-quote?amount=&preferredQuote?` | Devis à la demande du chemin d'achat de `amount` USD vers l'actif du binding (lecture seule, aucun ordre) : `{status OK\|NO_PATH\|BELOW_MIN, source, target, amount, legs[{instId, side BUY\|SELL, sz, refPrice, feePct, spreadPct, slippagePct, costPct, belowMin}], totalCostPct, feeTestLevel GREEN\|WARNING\|RED, estimation BOOK\|TICKER, underfunded, warning?}` (pourcentages en %). Binding d'un autre user ⇒ 404 ; `amount` absent/≤ 0 ⇒ 400 ; clé rejetée ⇒ 409 ; soldes/frais/catalogue/carnet indisponibles ou exchange non pris en charge ⇒ 503 |

## Wallet réel des presets live (utilisateur, `RainbowLiveWalletController`, `GET /api/rainbow-live/live-wallet`)

Authentifié, scopé à l'user. Lit **uniquement la base** (bindings + dernier run du preset lié) : aucun appel exchange. Liste vide sans binding. Élément `LiveAssetDto` : `{assetSymbol, bindingId, presetId, presetName, walletId, walletName, exchange, bagPercent, priority, tradability, tradabilityMessage (texte d'avertissement si `NOT_TRADABLE_WITHOUT_FIAT`, sinon nul), executionEnabled, firstLiveArmed, firstLiveConfirmed, snapshot}` ; `snapshot` nul tant qu'aucune passe live n'est jouée, sinon `{day, pass (T0005 prioritaire sur T2355), status (OK|STALE|UNAVAILABLE|NOT_TRADABLE), fetchedAt, cashUsd (groupe USD), cashByMember {USDC, USDT}, positionQty, tradableQty, cashReserved, blockReason (NONE|INSUFFICIENT_CASH|UNAVAILABLE|NOT_TRADABLE_WITHOUT_FIAT), liveActionType (BUY|SELL|NONE|BLOCKED), liveActionAmountUsdc, liveActionQuantity}`. L'action live est **recommandée, non exécutée**. Ni credential, ni secret, ni solde hors périmètre.

## Plans d'ordres (utilisateur, `RainbowLiveExecutionPlanController`, `/api/rainbow-live/execution-plans`)

Authentifié, propriétaire seulement (jamais le plan d'un autre user), lecture de la base uniquement ; ces endpoints n'envoient rien. Un plan peut porter les résultats d'une exécution réelle (statuts d'étape, fills, frais, slippage réel).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `?from=&to=` | Plans courants (hors révisions remplacées) entre deux jours UTC inclus (défaut : 30 derniers jours), du plus récent au plus ancien. `PlanDto` `{id, day, pass, revision, status PLANNED\|BLOCKED\|EXPIRED\|DISABLED, mode, totalCostPct, feeTestLevel, blockReason?, createdAt, expiresAt, assets[{assetSymbol, action, outcome OK\|BLOCKED\|DISABLED\|NO_ACTION, blockReason?, costPct, feeTestLevel, warning?, steps[{rank, instId, side, ordType, sz, px, quoteAmount, clOrdId, feePct, spreadPct, slippagePct, estimation}]}]}` ; `status` effectif : `EXPIRED` après `expiresAt` (5 min) |
| GET | `/latest` | Plan courant le plus récent ; 204 sans plan. `status` ∈ `PLANNED\|BLOCKED\|EXPIRED\|DISABLED\|EXECUTING\|EXECUTED\|PARTIAL\|FAILED\|CANCELLED` ; en plus : `executionBlockReason?`, `executionStartedAt?`, `executionFinishedAt?` ; par étape : `status`, `filledSz?`, `avgFillPx?`, `feeAmount?`, `feeCurrency?`, `receivedAmount?`, `receivedCurrency?`, `realSlippagePct?`, `lastError?` |
| GET | `/{id}/events?page=0&size=20` | Audit du plan en lecture seule, du plus ancien au plus récent (taille bornée à 100) : `{page, size, totalElements, items[{id, stepId?, clOrdId?, type, payload (JSON texte, sans secret), createdAt}]}` ; plan d'un autre user ou inexistant ⇒ 404 |

## État de l'exécution (utilisateur, `RainbowLiveExecutionStateController`, `GET /api/rainbow-live/execution-state`)

Authentifié. `{mode OFF\|DRY_RUN\|LIVE, liveExecution (LIVE déverrouillé), killSwitch}` : affichage seul, aucun secret ; le kill switch se pilote par `PUT /api/admin/execution/kill-switch`.

## Bench Rainbow (utilisateur, `RainbowLiveController`, `/api/rainbow-live`)

Authentifié (`@PreAuthorize("isAuthenticated()")` sur la classe), scopé à l'utilisateur connecté ; preset d'un autre user ou inexistant ⇒ 404. JSON, dates `YYYY-MM-DD` (jour UTC), enums en chaîne. Détail : [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/defaults` | `{assets, cashCurrency, defaultName, analysisWindowMonths, initialCapitalUsdc, baseAmount, configs{BTC,ETH,PAXG → {tuning, globals}}, reentryModes[], zones[{code,name,label}], trendDefaults{actif → TrendConfigDto}, rangeMappings[], uiModes[], systemPrefix}` (enum `ReentryMode` et libellés de zones fournis par le serveur) |
| GET / PUT | `/ui-mode` | Mode d'affichage de l'user : `{mode: AUTO\|SIMPLE\|ADVANCED\|EXPERT}` (défaut `EXPERT`) ; valeur invalide ⇒ 400 |
| GET | `/strategies` | Stratégies Actif, **lecture seule** côté user (`StrategyDto`) ; l'édition est réservée à System (endpoint admin) |
| GET | `/preset-events[?asset=BTC]` | Historique des switchs de preset du user (`PresetEventDto`), du plus récent au plus ancien |
| GET | `/presets[?asset=BTC]` | Crée les presets qui suivent les stratégies manquants (inactifs, `followsStrategy=true`) puis liste `PresetDto` ; actif hors BTC/ETH/PAXG ⇒ 400 |
| POST | `/presets` | Corps `{assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, tuning, globals[, duplicatedFromId]}` ⇒ 201 `PresetDto` (`duplicatedFromId` = preset dupliqué : s'il suit une stratégie, la création est un détachement, événement `DETACH`) ; preset Trend Mix : `mode:"TREND_MIX"` + `trendConfig` (sans `tuning`/`globals`) ; 400 (validation, nom préfixé par le préfixe système réservé), 409 (nom déjà pris pour l'actif) |
| GET | `/presets/{id}` | `PresetDto` |
| PUT | `/presets/{id}` | Corps `{name, enabled, analysisWindowMonths, tuning, globals}` (Trend Mix : `trendConfig` à la place, absent ⇒ inchangé) (actif et capital non modifiables) ⇒ `PresetDto` ; effet au run suivant, historique et wallet conservés ; preset qui suit une stratégie ⇒ 409 |
| PATCH | `/presets/{id}/enabled` | Corps `{enabled}` ⇒ `PresetDto` ; seule modification permise sur un preset qui suit une stratégie |
| DELETE | `/presets/{id}` | 204 ; supprime wallet mock et runs ; preset qui suit une stratégie ⇒ 409 |
| GET | `/presets/{id}/runs[?from&to]` | Liste chronologique `RunDto` |
| GET | `/presets/{id}/performance[?to]` | `PerformanceDto` |
| GET | `/presets/{id}/delta[?from&to]` | Synthèse delta 23:55 vs 00:05 |

Réponses (noms de champs) :
- `PresetDto` : `id, assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, createdAt, updatedAt, tuning, globals, wallet{cashUsd, positionQuantity, lastClose?, equityUsdc?}, runCount, firstRunDay?, lastRun?{day, pass, close, zone, actionType, actionAmountUsdc, actionQuantity, activeSet?, trendRegime?}, mode (FIXED|TREND_MIX), trendConfig?` (Trend Mix), `followsStrategy`, `strategyRevision?` (révision suivie, null si preset propre) ; `TrendConfigDto` = `{trend{shortWindow, mediumWindow, longWindow, slopeScale, enter, exit, confirm, smaPeriod, atrPeriod, atrMultiplier, wickDown, wickUp}, rangeMapping, bear{tuning, globals}, bull{tuning, globals}}` (`tuning` = champs de `RainbowAtrTuning`, `globals` = champs de `RainbowAtrGlobals`, `baseAmount` inclus).
- `StrategyDto` : `id, assetSymbol, name, mode, revision, updatedAt, analysisWindowMonths, initialCapitalUsdc, config{tuning, globals}, trendConfig?` (Trend Mix).
- `PresetEventDto` : `id, assetSymbol, type (ENABLE|DISABLE|DETACH|LIVE_SWITCH|STRATEGY_CHANGED), presetBeforeId?, presetBeforeName?, presetAfterId?, presetAfterName?, strategyRevision?, occurredAt, reason?`.
- `RunDto` : `day, pass2355?, pass0005?` (blocs `{close, sma, atr, boundDown2, boundDown1, boundUp1, boundUp2, boundUp3, zone, athDistance, buyFactor, sellFactor, moonMode, buyArmed, sellArmed, buyLocked, cooldownRemaining, moonReserveQty, actionType, actionAmountUsdc, actionQuantity, actionPrice, cashAfter, positionAfter, configHash, computedAt}`), `deltaActionDiffers, configHash, configChanged, changedParams[]`. Jours manquants absents de la liste.
- `PerformanceDto` : `presetId, assetSymbol, initialCapitalUsdc, days, firstDay, lastDay, metrics{invested, saleProceeds, currentValue, realizedGain, potentialGain, totalGain, pnlPercent, realizedPercent, potentialPercent, position, costBasis, lastClose, fixedInvested, fixedQuantity, fixedValue, fixedGain, fixedPnlPercent, outperformanceGain, outperformancePoints}, wallet{initialCapitalUsdc, cashUsd, positionQuantity, equityUsdc, pnlPercent}, series[{day, close, zone, actionType, actionAmountUsdc, actionQuantity, invested, saleProceeds, currentValue, position, costBasis, walletEquity, fixedValue, fixedInvested}], markers[{day, type, price, amountUsdc, quantity}], configMarkers[{day, configHash, changedParams[]}]`. Sans run : 200, métriques à 0, séries vides.
- Delta : `daysCompared, daysIgnored, daysActionDiffers, actionDifferences[{day, pass2355{type, amountUsdc, quantity}, pass0005{…}}], indicators{close|sma|atr|boundDown2|boundDown1|boundUp1|boundUp2|boundUp3 → {meanAbs, maxAbs, meanPct, maxPct}}, zoneDivergences[{day, zone2355, zone0005}], stateDivergences[{day, fields[]}]`.
- Erreurs : `{"error": "<message>"}` (400 / 404 / 409, `RainbowLiveControllerAdvice` ; 409 = nom déjà pris ou preset qui suit une stratégie (verrouillé)).

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
