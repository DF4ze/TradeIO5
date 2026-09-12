# Endpoints REST

Vérifié le : 2026-09-12 (`controller/**`).

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
