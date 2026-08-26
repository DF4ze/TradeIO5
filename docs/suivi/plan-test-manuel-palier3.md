# Plan de test manuel — Palier 3 (branchement, orchestrateur, persistance)

Révisé le 2026-08-17 après implémentation de 3 endpoints admin manquants (OPINION + READ scénarios/
décisions), suite à la remarque de Clem : "tout vient de là à la base" (le calcul d'Opinion est la
racine de toute la chaîne Scenario/Decision, et il n'existait aucun moyen REST de le déclencher ni de
relire l'état vivant qui en résulte). Les 3 nouveaux endpoints sont implémentés, testés (569 tests,
0 échec/erreur imputable à ce lot — cf. §9) et documentés ci-dessous.

## 0. Prérequis avant de commencer

- Application démarrée.
- Un compte avec **ROLE_ADMIN** (sélectionnable directement au formulaire d'inscription `/register`).
- Au moins un compte **ROLE_USER** actif (`enabled=true`, `archived_at IS NULL`) pour observer la
  propagation de l'orchestrateur.

Les gaps identifiés dans la première version de ce plan (aucun endpoint de lecture, obligation de
passer par la DB/les logs) sont désormais comblés — plus besoin de client SQL pour l'essentiel du test.

---

## 1. Endpoints disponibles (tous confirmés en code, 2026-08-17)

| Méthode | Chemin | Rôle | Déclenche / lit | Réponse |
|---|---|---|---|---|
| `GET` | `/api/admin/decision/opinion?symbol=&scope=` | `ROLE_ADMIN` | **Nouveau.** Calcule une Opinion à la demande (`TreeAnalysisFacade.getOpinion`) | `OpinionSignalResponse` |
| `GET` | `/api/admin/decision/scenarios[?owner=]` | `ROLE_ADMIN` | **Nouveau.** Lit les scénarios actifs (tous owners, ou un seul si `owner` fourni) | `List<ScenarioSummaryResponse>` |
| `GET` | `/api/admin/decision/decisions[?owner=]` | `ROLE_ADMIN` | **Nouveau.** Lit les décisions actives (tous owners, ou un seul si `owner` fourni) | `List<DecisionSummaryResponse>` |
| `POST` | `/api/admin/decision/orchestrate` | `ROLE_ADMIN` | `DecisionOrchestrator.runCycle()` | `OrchestrationResult{signalsComputed, activeUsersFound, usersProcessed, usersSkippedLocked, runAt}` |
| `POST` | `/api/admin/decision/snapshot` | `ROLE_ADMIN` | `DecisionScenarioSnapshotService.takeSnapshot()` | `SnapshotResult{scenarioCount, decisionCount, snapshotAt}` |
| `POST` | `/api/admin/decision/archive` | `ROLE_ADMIN` | `UserArchivalService.archiveInactiveUsers()` | `ArchivalResult{archivedCount, archivedAt}` |
| `POST` | `/api/auth/signinForm` | — | Login ; met à jour `lastLogin` ; restaure l'owner si `archivedAt != null` | redirection |
| MCP | `get_macro_calendar(fromDate, toDate, minImpact?)` | — | Liste les événements macro sur une fenêtre | JSON liste d'événements |
| MCP | `check_macro_risk_window(windowHours, minImpact)` | — | Indique si une fenêtre à risque est active maintenant | JSON booléen |

`owner` (endpoints READ) : `"SYSTEM"` ou l'id numérique de l'utilisateur (`ScenarioOwner.fromString`).
Omis = tous owners confondus.

`scope` (endpoint OPINION) : `LOCAL`/`GLOBAL`/`MACRO`/`EXTERNAL`. Pour `LOCAL`, les 4 strategies par
défaut de l'orchestrateur (TrendConfirmation + MovementQualification + OrderFlow + EtfFlow) sont
appliquées automatiquement (`DefaultLocalOpinionParamsProvider`, partagé avec `DecisionOrchestrator` —
même calcul, pas une implémentation parallèle). `symbol` reste requis même pour `GLOBAL`/`MACRO`
(contrat de `TreeAnalysisFacade`) mais est ignoré par ces deux scopes.

Les 3 crons équivalents (`snapshot-cron`/`archival-cron`/`orchestrator-cron`) restent **désactivés par
défaut** (§7).

---

## 2. Étape 1-3 (branchement, nettoyage, extensions de modèle) — pas de test dédié

Fondations consommées par les étapes 4/7/8 — vérifiées indirectement ci-dessous (si `/opinion` et
`/orchestrate` fonctionnent sans erreur, et que `/scenarios` renvoie bien un `scope` par ligne, ces
étapes fonctionnent).

---

## 3. Vérifier la racine : le calcul d'Opinion

1. `GET /api/admin/decision/opinion?symbol=BTC&scope=LOCAL` → attendu : `OpinionSignalResponse` avec
   `symbol="BTC"`, `scope="LOCAL"`, `majoritySignal`/`weightedSignal` renseignés, `sources` listant les
   4 strategies (TrendConfirmation + les 3 modulateurs de confiance).
2. `GET /api/admin/decision/opinion?symbol=BTC&scope=GLOBAL` → `symbol=null` en sortie (vérifie au
   passage le fix du bug de propagation de symbole de l'étape 7 — si `symbol` n'est pas `null` ici,
   c'est une régression).
3. `GET /api/admin/decision/opinion?symbol=BTC&scope=MACRO` → même vérification `symbol=null`.
4. Répéter pour `ETH`/`PAXG` en `LOCAL` si besoin de comparer plusieurs actifs.

Si un de ces 4 appels échoue ou renvoie un signal incohérent, tout ce qui suit (scénarios, décisions,
orchestrateur) part d'une base cassée — à isoler en premier avant de tester le reste.

---

## 4. Étape 7 — Orchestrateur (propagation vers les owners)

1. `POST /api/admin/decision/orchestrate`.
2. Vérifier la réponse : `signalsComputed=5` (3 LOCAL + GLOBAL + MACRO), `activeUsersFound` = nombre de
   comptes actifs, `usersProcessed = activeUsersFound` (premier run), `usersSkippedLocked=0`.
3. **Lire directement le résultat** (nouveau, plus besoin de DB) :
   ```
   GET /api/admin/decision/scenarios
   GET /api/admin/decision/decisions
   ```
   Attendu dans `/scenarios` : des lignes `scope=LOCAL` avec `symbol` parmi `BTC`/`ETH`/`PAXG`, des
   lignes `scope=GLOBAL`/`MACRO` avec `symbol=null`. `owner` = l'id de chaque compte actif.
4. Filtrer par owner pour observer un compte précis :
   `GET /api/admin/decision/scenarios?owner=<id>` / `GET /api/admin/decision/decisions?owner=<id>`.
5. Relancer immédiatement `/orchestrate` une 2e fois → le verrou anti-doublon (1h) doit jouer :
   `usersSkippedLocked` reflète les owners déjà traités, `usersProcessed` chute d'autant. Pas de moyen
   de forcer le déverrouillage via l'API — redémarrer l'application le réinitialise (verrou en mémoire).

---

## 5. Étape 4 — Photo quotidienne + rejeu au redémarrage

1. Avoir des scénarios/décisions vivants (§4 ci-dessus).
2. `POST /api/admin/decision/snapshot` → `scenarioCount`/`decisionCount` > 0.
3. Redémarrer l'application (test du rejeu, `DecisionScenarioRestoreRunner`).
4. `GET /api/admin/decision/scenarios` et `/decisions` → comparer au relevé d'avant redémarrage (pris
   avec les mêmes endpoints juste avant l'étape 3) : même contenu, pas de doublon ni de perte.

(Optionnel, pour inspecter la persistance elle-même plutôt que le résultat du rejeu : `SELECT * FROM
scenario_snapshots;` / `decision_snapshots;` en DB.)

---

## 6. Étapes 5-6 — Connexion / archivage sur inactivité

1. `POST /api/auth/signinForm` avec un compte de test → `lastLogin` mis à jour (vérifiable en DB,
   `SELECT last_login FROM users WHERE username = '...'`, aucun endpoint de lecture dédié pour ce champ).
2. Positionner artificiellement un `last_login` ancien (> 60 jours) en DB sur un user de test.
3. `POST /api/admin/decision/archive` → `archivedCount >= 1`.
4. `GET /api/admin/decision/scenarios?owner=<id archivé>` → doit renvoyer une liste **vide** (owner
   évincé de la mémoire active, cf. `evictOwner`).
5. Se reconnecter avec ce compte (`signinForm`) → `archived_at` repasse à `NULL` en DB, logs "restauré
   depuis l'archive à la reconnexion", et `GET /scenarios?owner=<id>` redevient non vide après un
   nouveau `/orchestrate`.

---

## 7. Étape 8 — Calendrier macro (modulation de confidence GLOBAL/MACRO)

1. `check_macro_risk_window(windowHours=2, minImpact="HIGH")` (MCP) → indique si une fenêtre à risque
   est active **maintenant**.
2. **Si oui** (rare, `HIGH` seulement) :
   `GET /api/admin/decision/opinion?symbol=BTC&scope=GLOBAL` → noter `confidence`.
   Comparer à un appel identique hors fenêtre (avant/après l'échéance) → la confidence doit être réduite
   d'un facteur ~0.5 (valeur par défaut, non calibrée) pendant la fenêtre. Plus simple qu'avant (pas
   besoin de creuser `stateJson` en DB — l'endpoint `/opinion` donne directement la confidence).
3. **Si non** (cas le plus probable) : `get_macro_calendar(fromDate, toDate, minImpact="HIGH")` (MCP)
   pour trouver la prochaine échéance `HIGH` (FOMC/NFP/CPI) et reprogrammer ce test à ce moment-là. Pas
   de moyen de simuler artificiellement une fenêtre active — le service interroge Finnhub/ForexFactory
   en direct à chaque appel.

---

## 8. Activer les crons pour un test "en conditions réelles" (optionnel)

Décommenter dans le fichier de profil actif :
```properties
tradeio.decision.snapshot-cron=0 0 6 * * *
tradeio.decision.archival-cron=0 0 6 * * *
tradeio.decision.orchestrator-cron=0 0 * * * *
```
Remplacer par un créneau rapproché (ex. `0 */5 * * * *`) le temps du test, puis redémarrer. Ne pas
laisser un créneau de test en config au-delà de la session de vérification.

---

## 9. Ce qui a changé par rapport à la version précédente de ce plan

Implémenté le 2026-08-17 (code + tests, 569 tests exécutés via `test:tradeio-5`) :
- `DefaultLocalOpinionParamsProvider` (nouveau `@Component`) : extrait la combinaison des 4 strategies
  par défaut hors de `DecisionOrchestrator`, pour que l'endpoint `/opinion` calcule exactement la même
  chose que le cycle automatique — pas une implémentation parallèle qui aurait pu diverger.
- `OpinionAdminController` (`GET /opinion`) et `DecisionStateAdminController` (`GET /scenarios`,
  `GET /decisions`).
- `OpinionSignalResponse`/`ScenarioSummaryResponse`/`DecisionSummaryResponse`/`ActionStepResponse` :
  formes JSON plates (le projet n'embarque pas `jackson-datatype-jdk8`, donc les champs `Optional<...>`
  des objets de domaine ne se sérialisent pas correctement tels quels).

**2 échecs de tests pré-existants, sans rapport avec ce lot** (à signaler séparément, pas de mon
ressort dans ce travail) : `DxyIndicatorCacheSharingTest` (2 tests, cache de l'indicateur DXY/Twelve
Data) — ni le fichier ni le package touchés par ce lot. Tous les autres tests, y compris les 4 tests
nouveaux/modifiés de ce lot (`DefaultLocalOpinionParamsProviderTest`, `OpinionAdminControllerTest`,
`DecisionStateAdminControllerTest`, `DecisionOrchestratorTest` mis à jour), passent.

**Gaps restants** (inchangés, non traités par ce lot) :
1. Étape 8 non testable à la demande sans attendre une vraie échéance macro `HIGH`.
2. Verrou anti-doublon sans override — redémarrer l'application reste le seul moyen rapide de
   l'enchaîner pour tester l'étape 7 plusieurs fois de suite.
