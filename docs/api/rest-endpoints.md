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

## Bench Rainbow (utilisateur, `RainbowLiveController`, `/api/rainbow-live`)

Authentifié (`@PreAuthorize("isAuthenticated()")` sur la classe), scopé à l'utilisateur connecté ; preset d'un autre user ou inexistant ⇒ 404. JSON, dates `YYYY-MM-DD` (jour UTC), enums en chaîne. Détail : [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/defaults` | `{assets, stablecoin, defaultName, analysisWindowMonths, initialCapitalUsdc, baseAmount, configs{BTC,ETH,PAXG → {tuning, globals}}, reentryModes[], zones[{code,name,label}]}` (enum `ReentryMode` et libellés de zones fournis par le serveur) |
| GET | `/presets[?asset=BTC]` | Seed lazy (une fois par user) puis liste `PresetDto` ; actif hors BTC/ETH/PAXG ⇒ 400 |
| POST | `/presets` | Corps `{assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, tuning, globals}` ⇒ 201 `PresetDto` ; 400 (validation), 409 (nom déjà pris pour l'actif) |
| GET | `/presets/{id}` | `PresetDto` |
| PUT | `/presets/{id}` | Corps `{name, enabled, analysisWindowMonths, tuning, globals}` (actif et capital non modifiables) ⇒ `PresetDto` ; effet au run suivant, historique et wallet conservés |
| DELETE | `/presets/{id}` | 204 ; supprime wallet mock et runs |
| GET | `/presets/{id}/runs[?from&to]` | Liste chronologique `RunDto` |
| GET | `/presets/{id}/performance[?to]` | `PerformanceDto` |
| GET | `/presets/{id}/delta[?from&to]` | Synthèse delta 23:55 vs 00:05 |

Réponses (noms de champs) :
- `PresetDto` : `id, assetSymbol, name, enabled, analysisWindowMonths, initialCapitalUsdc, createdAt, updatedAt, tuning, globals, wallet{cashUsdc, positionQuantity, lastClose?, equityUsdc?}, runCount, firstRunDay?, lastRun?{day, pass, close, zone, actionType, actionAmountUsdc, actionQuantity}` (`tuning` = champs de `RainbowAtrTuning`, `globals` = champs de `RainbowAtrGlobals`, `baseAmount` inclus).
- `RunDto` : `day, pass2355?, pass0005?` (blocs `{close, sma, atr, boundDown2, boundDown1, boundUp1, boundUp2, boundUp3, zone, athDistance, buyFactor, sellFactor, moonMode, buyArmed, sellArmed, buyLocked, cooldownRemaining, moonReserveQty, actionType, actionAmountUsdc, actionQuantity, actionPrice, cashAfter, positionAfter, configHash, computedAt}`), `deltaActionDiffers, configHash, configChanged, changedParams[]`. Jours manquants absents de la liste.
- `PerformanceDto` : `presetId, assetSymbol, initialCapitalUsdc, days, firstDay, lastDay, metrics{invested, saleProceeds, currentValue, realizedGain, potentialGain, totalGain, pnlPercent, realizedPercent, potentialPercent, position, costBasis, lastClose, fixedInvested, fixedQuantity, fixedValue, fixedGain, fixedPnlPercent, outperformanceGain, outperformancePoints}, wallet{initialCapitalUsdc, cashUsdc, positionQuantity, equityUsdc, pnlPercent}, series[{day, close, zone, actionType, actionAmountUsdc, actionQuantity, invested, saleProceeds, currentValue, position, costBasis, walletEquity, fixedValue, fixedInvested}], markers[{day, type, price, amountUsdc, quantity}], configMarkers[{day, configHash, changedParams[]}]`. Sans run : 200, métriques à 0, séries vides.
- Delta : `daysCompared, daysIgnored, daysActionDiffers, actionDifferences[{day, pass2355{type, amountUsdc, quantity}, pass0005{…}}], indicators{close|sma|atr|boundDown2|boundDown1|boundUp1|boundUp2|boundUp3 → {meanAbs, maxAbs, meanPct, maxPct}}, zoneDivergences[{day, zone2355, zone0005}], stateDivergences[{day, fields[]}]`.
- Erreurs : `{"error": "<message>"}` (400 / 404 / 409, `RainbowLiveControllerAdvice`).

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
