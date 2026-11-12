---
statut: courant
vérifié_le: 2026-11-07
modifié_le: 2026-11-11
empreinte: 5676526fa5
---
# Endpoints REST

Vérifié le : 2026-11-07 (`controller/**`, dont `Expert*Controller`, `RainbowLiveController`, `RainbowLiveBindingController`).

## Web (Thymeleaf, `MainController`)

| Méthode | Chemin | Rôle requis | Description |
|---|---|---|---|
| GET | `/` | public | Page de présentation (landing) ; utilisateur connecté ⇒ redirection `/app` |
| GET | `/login` | public | Formulaire de login (connecté ⇒ `/app`) |
| GET | `/register` | public | Formulaire d'inscription (connecté ⇒ `/app` ; inscription fermée ⇒ `/login`) ; affiche `ok`, `exception` et les messages de validation reçus en paramètres de redirection |
| GET | `/admin` | ADMIN | Page admin |

## Authentification (`AuthController`, `/api/auth`)

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| POST | `/api/auth/signinForm` | public | Login formulaire, pose le cookie JWT, restaure l'owner si `archivedAt != null` |
| POST | `/api/auth/signupForm` | public | Inscription formulaire, **fermée par défaut** (`tradeio.signup.enabled=false` ⇒ redirige vers `/login`, rien n'est créé) ; le compte créé reçoit toujours `ROLE_USER` (rôles postés ignorés) |
| GET | `/api/auth/signoutForm` | public | Logout (nettoie le cookie JWT) |

Les endpoints JSON (`/signin`, `/signup`, `/signout`) sont **commentés dans le code**, non actifs — seules les variantes `*Form` (POST formulaire, réponses `ModelAndView`) sont en service.

## Utilisateurs et accès VPS (admin, `AdminUserAccessController`, `/api/admin/users`, `ROLE_ADMIN`)

Erreurs au format `{"error": "..."}` (400, 404, 409, 502). Cf. [`../architecture/07-security.md`](../architecture/07-security.md).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/api/admin/users` | Liste (id, nom), pour les sélecteurs |
| POST | `/api/admin/users` | `{username, sshAccess}` : crée l'utilisateur (`ROLE_USER`, mot de passe généré renvoyé une seule fois) ; `sshAccess` ⇒ `PENDING_KEY` |
| GET | `/api/admin/users/access` | Utilisateurs avec `enabled`, `sshStatus`, `sshMessage`, `sshUpdatedAt` |
| POST | `/api/admin/users/{id}/ssh-key` | `{publicKey}` : crée le compte VPS (`ssh-ed25519`) |
| POST | `/api/admin/users/{id}/ssh/sync` | « Réessayer » : aligne le VPS sur l'état applicatif |
| PUT | `/api/admin/users/{id}/enabled` | `{enabled}` : (dés)active l'utilisateur et son accès VPS |
| DELETE | `/api/admin/users/{id}` | Supprime l'accès VPS puis l'utilisateur |
| GET | `/api/admin/users/ssh/reconcile` | Écarts app / VPS (orphelins, manquants, divergences, clés en attente) |
| GET | `/api/admin/users/{id}/ssh/client-conf` | `tradeio.conf` prérempli (texte) |
| GET | `/api/admin/users/{id}/ssh/client-package` | Zip : scripts de `tradeio.ssh.client-dir` + `tradeio.conf` du compte |

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
| GET | `/api/overview?quoteCurrency=&exchangeCode=` | authentifié | Positions détenues par l'utilisateur connecté (`AssetOverviewService`) ; un wallet dont les soldes sont illisibles (ou sans client d'exchange, ex. OKX) est ignoré (WARN) |
| GET/PUT | `/api/user/risk-cursor` | authentifié | Curseur de risque continu (0-10) de l'utilisateur connecté |
| GET | `/api/calibration/zones?symbol=` | **public, volontairement sans `@PreAuthorize`** | Recalcul à la demande des zones de consolidation/régime ADX, pour l'outil de diagnostic `tools/calibration/zone_view_v2.html`. `@CrossOrigin("*")` car ouvert en `file://` |

Voir [`architecture/07-security.md`](../architecture/07-security.md) pour le fonctionnement réel de l'autorisation (la chaîne de filtres URL est permissive ; seules les annotations `@PreAuthorize` protègent réellement).

## Voir aussi (suite de ce document)

- [`rest-execution-plans.md`](rest-execution-plans.md) — Endpoints REST — Plans d'ordres et état d'exécution
- [`rest-rainbow-live.md`](rest-rainbow-live.md) — Endpoints REST — Rainbow live (bindings, wallet, décisions, modulation, run)
- [`rest-expert-portfolio.md`](rest-expert-portfolio.md) — Endpoints REST — Espace Expert, patrimoine, préférences
