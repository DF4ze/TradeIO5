# Endpoints REST

Vérifié le : 2026-10-03 (`controller/**`, dont `RainbowLiveController`).

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

## Bindings Rainbow live (utilisateur, `RainbowLiveBindingController`, `/api/rainbow-live/bindings`)

Authentifié, scopé à l'user ; binding / preset / wallet d'un autre user ou inexistant ⇒ 404. Erreurs via `RainbowLiveControllerAdvice` (`{error}`). Lecture seule côté exchange, aucun ordre. Aucun solde exposé.

| Méthode | Chemin | Description |
|---|---|---|
| GET | `` | Liste `BindingDto` `{id, assetSymbol, presetId, presetName, walletId, walletName, exchange, bagPercent, priority}` (par priorité) |
| POST | `` | Corps `{assetSymbol, presetId, walletId, bagPercent?, priority?}` (défauts : 100, rang de l'actif) ⇒ 201 `{binding, check}` ; binding existant pour l'actif ⇒ 409 ; preset d'un autre actif / `bagPercent` hors [0,100] / actif inconnu ⇒ 400 |
| GET | `/{id}` | `BindingDto` |
| PUT | `/{id}` | Corps `{presetId?, walletId?, bagPercent?, priority?}` (actif immuable, champs nuls inchangés) ⇒ `{binding, check}` ; changer `presetId` = bascule du preset live |
| DELETE | `/{id}` | 204 (le preset reste) |
| GET | `/{id}/check` | Revérifie : `{status, message, ok}`, `status` ∈ `OK, WALLET_DISABLED, CREDENTIAL_INVALID, PROVIDER_UNSUPPORTED, BALANCE_UNAVAILABLE, INSTRUMENT_MISSING, INSTRUMENT_UNAVAILABLE` (appelle l'exchange en lecture seule) |

## Wallet réel des presets live (utilisateur, `RainbowLiveWalletController`, `GET /api/rainbow-live/live-wallet`)

Authentifié, scopé à l'user. Lit **uniquement la base** (bindings + dernier run du preset lié) : aucun appel exchange. Liste vide sans binding. Élément `LiveAssetDto` : `{assetSymbol, bindingId, presetId, presetName, walletId, walletName, exchange, bagPercent, priority, snapshot}` ; `snapshot` nul tant qu'aucune passe live n'est jouée, sinon `{day, pass (T0005 prioritaire sur T2355), status (OK|STALE|UNAVAILABLE), fetchedAt, cashUsdc, positionQty, tradableQty, cashReserved, blockReason (NONE|INSUFFICIENT_CASH|UNAVAILABLE), liveActionType (BUY|SELL|NONE|BLOCKED), liveActionAmountUsdc, liveActionQuantity}`. L'action live est **recommandée, non exécutée**. Ni credential, ni secret, ni solde hors périmètre.

## Bench Rainbow (utilisateur, `RainbowLiveController`, `/api/rainbow-live`)

Authentifié (`@PreAuthorize("isAuthenticated()")` sur la classe), scopé à l'utilisateur connecté ; preset d'un autre user ou inexistant ⇒ 404. JSON, dates `YYYY-MM-DD` (jour UTC), enums en chaîne. Détail : [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/defaults` | `{assets, stablecoin, defaultName, analysisWindowMonths, initialCapitalUsdc, baseAmount, configs{BTC,ETH,PAXG → {tuning, globals}}, reentryModes[], zones[{code,name,label}], trendDefaults{actif → TrendConfigDto}, rangeMappings[], uiModes[], systemPrefix}` (enum `ReentryMode` et libellés de zones fournis par le serveur) |
| GET / PUT | `/ui-mode` | Mode d'affichage de l'user : `{mode: AUTO\|SIMPLE\|ADVANCED\|EXPERT}` (défaut `EXPERT`) ; valeur invalide ⇒ 400 |
| GET | `/templates` | Templates de presets système, **lecture seule** (`TemplateDto`) ; aucun endpoint d'écriture |
| GET | `/presets[?asset=BTC]` | Copie des templates système manquants (inactives, `system=true`) puis liste `PresetDto` ; actif hors BTC/ETH/PAXG ⇒ 400 |
| POST | `/presets` | Corps `{assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, tuning, globals}` ⇒ 201 `PresetDto` ; preset Trend Mix : `mode:"TREND_MIX"` + `trendConfig` (sans `tuning`/`globals`) ; 400 (validation, nom préfixé par le préfixe système réservé), 409 (nom déjà pris pour l'actif) |
| GET | `/presets/{id}` | `PresetDto` |
| PUT | `/presets/{id}` | Corps `{name, enabled, analysisWindowMonths, tuning, globals}` (Trend Mix : `trendConfig` à la place, absent ⇒ inchangé) (actif et capital non modifiables) ⇒ `PresetDto` ; effet au run suivant, historique et wallet conservés ; preset système ⇒ 409 |
| PATCH | `/presets/{id}/enabled` | Corps `{enabled}` ⇒ `PresetDto` ; seule modification permise sur un preset système |
| DELETE | `/presets/{id}` | 204 ; supprime wallet mock et runs ; preset système ⇒ 409 |
| GET | `/presets/{id}/runs[?from&to]` | Liste chronologique `RunDto` |
| GET | `/presets/{id}/performance[?to]` | `PerformanceDto` |
| GET | `/presets/{id}/delta[?from&to]` | Synthèse delta 23:55 vs 00:05 |

Réponses (noms de champs) :
- `PresetDto` : `id, assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, createdAt, updatedAt, tuning, globals, wallet{cashUsdc, positionQuantity, lastClose?, equityUsdc?}, runCount, firstRunDay?, lastRun?{day, pass, close, zone, actionType, actionAmountUsdc, actionQuantity, activeSet?, trendRegime?}, mode (FIXED|TREND_MIX), trendConfig?` (Trend Mix), `system` (copie d'un template) ; `TrendConfigDto` = `{trend{shortWindow, mediumWindow, longWindow, slopeScale, enter, exit, confirm, smaPeriod, atrPeriod, atrMultiplier, wickDown, wickUp}, rangeMapping, bear{tuning, globals}, bull{tuning, globals}}` (`tuning` = champs de `RainbowAtrTuning`, `globals` = champs de `RainbowAtrGlobals`, `baseAmount` inclus).
- `TemplateDto` : `id, assetSymbol, name, mode, analysisWindowMonths, initialCapitalUsdc, config{tuning, globals}, trendConfig?` (Trend Mix).
- `RunDto` : `day, pass2355?, pass0005?` (blocs `{close, sma, atr, boundDown2, boundDown1, boundUp1, boundUp2, boundUp3, zone, athDistance, buyFactor, sellFactor, moonMode, buyArmed, sellArmed, buyLocked, cooldownRemaining, moonReserveQty, actionType, actionAmountUsdc, actionQuantity, actionPrice, cashAfter, positionAfter, configHash, computedAt}`), `deltaActionDiffers, configHash, configChanged, changedParams[]`. Jours manquants absents de la liste.
- `PerformanceDto` : `presetId, assetSymbol, initialCapitalUsdc, days, firstDay, lastDay, metrics{invested, saleProceeds, currentValue, realizedGain, potentialGain, totalGain, pnlPercent, realizedPercent, potentialPercent, position, costBasis, lastClose, fixedInvested, fixedQuantity, fixedValue, fixedGain, fixedPnlPercent, outperformanceGain, outperformancePoints}, wallet{initialCapitalUsdc, cashUsdc, positionQuantity, equityUsdc, pnlPercent}, series[{day, close, zone, actionType, actionAmountUsdc, actionQuantity, invested, saleProceeds, currentValue, position, costBasis, walletEquity, fixedValue, fixedInvested}], markers[{day, type, price, amountUsdc, quantity}], configMarkers[{day, configHash, changedParams[]}]`. Sans run : 200, métriques à 0, séries vides.
- Delta : `daysCompared, daysIgnored, daysActionDiffers, actionDifferences[{day, pass2355{type, amountUsdc, quantity}, pass0005{…}}], indicators{close|sma|atr|boundDown2|boundDown1|boundUp1|boundUp2|boundUp3 → {meanAbs, maxAbs, meanPct, maxPct}}, zoneDivergences[{day, zone2355, zone0005}], stateDivergences[{day, fields[]}]`.
- Erreurs : `{"error": "<message>"}` (400 / 404 / 409, `RainbowLiveControllerAdvice` ; 409 = nom déjà pris ou preset système verrouillé).

## Veille média (admin, `MediaWatchAdminController`)

| Méthode | Chemin | Rôle | Action |
|---|---|---|---|
| POST | `/api/admin/media-watch/ingest` | ADMIN | Relance immédiate de l'ingestion RSS+transcript |
| POST | `/api/admin/media-watch/extract` | ADMIN | Relance immédiate de la classification+extraction LLM |

## Autres

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| GET | `/api/overview?quoteCurrency=&exchangeCode=` | authentifié | Positions détenues par l'utilisateur connecté (`AssetOverviewService`) |
| GET/PUT | `/api/user/risk-cursor` | authentifié | Curseur de risque continu (0-10) de l'utilisateur connecté |
| GET | `/api/calibration/zones?symbol=` | **public, volontairement sans `@PreAuthorize`** | Recalcul à la demande des zones de consolidation/régime ADX, pour l'outil de diagnostic `tools/calibration/zone_view_v2.html`. `@CrossOrigin("*")` car ouvert en `file://` |

Voir [`architecture/07-security.md`](../architecture/07-security.md) pour le fonctionnement réel de l'autorisation (la chaîne de filtres URL est permissive ; seules les annotations `@PreAuthorize` protègent réellement).
