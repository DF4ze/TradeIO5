---
statut: courant
modifié_le: 2026-11-24
empreinte: 9af41e2356
---
# Docteur — diagnostic et alarmes (Admin)

Observateur : il lit l'état du système, déduit des verdicts et tient des alarmes. Il **n'agit jamais** (aucune correction, aucun relancement de job). Étude : [`etudes/etude-diagnostic-docteur-admin.md`](../etudes/etude-diagnostic-docteur-admin.md) ; reste à faire : [`known-gaps/doctor-diagnostic.md`](../known-gaps/doctor-diagnostic.md).

## Journal des jobs (`job_run`)
`JobRunRecorder` enveloppe chaque job planifié (Rainbow 23:55, plan, exécution, backup historique, média ×2, ETF flow, snapshot, archivage) et les déclenchements manuels (`RainbowLiveAdminController.run`, `AdminPlanTriggerService.plan`). Une ligne par exécution : `jobName`, `triggeredBy` (CRON/MANUAL), `status` (OK/PARTIAL/FAILED), `startedAt`, `durationMs`, `errors`, `summary`, `errorMessage`. Best-effort : un échec d'écriture est loggé en WARN et n'affecte jamais le job ; l'exception du job est toujours relancée. `DecisionOrchestratorJob` n'est pas journalisé (hors périmètre).

## Contrôles (`DoctorCheck`)
Un contrôle est une sonde pure qui renvoie un `CheckResult` (sujet, statut OK/UNKNOWN/WARN/CRIT, message) par sujet. Famille (PLANNING/DATA/EXECUTION/INFRA/LOGS) et impact (EXECUTION/QUALITY/INFO) portés par le contrôle.

| Id | Vérifie |
|---|---|
| `JOB_SCHEDULE` | Pour chaque job attendu : cron présent et valide, dernier `job_run` présent, pas en retard (> 10 min après le dernier tir théorique + 60 s), pas PARTIAL (WARN) / FAILED (CRIT). Aucun run enregistré ⇒ UNKNOWN |
| `EXECUTION_STATE` | Mode, clé maître, kill switch (levé = état normal en LIVE ; WARN « levé > N h » seulement si `tradeio.doctor.kill-switch-lifted-warn-hours` > 0, défaut 0 = désactivé) |
| `PLAN_PROBLEMS` | Plans des 3 derniers jours en erreur / bloqués > 35 min ; step `UNKNOWN` = WARN pendant la réconciliation auto (≤ 35 min), CRIT au-delà ([`14-execution-reelle.md`](14-execution-reelle.md)) |
| `TRANSIT_STOCK` | Stocks de transit non résolus depuis > 12 h |
| `KEY_REJECTED` | Clés à paire générée (Revolut X) refusées par l'exchange : sonde réelle de chaque credential active (`ProviderKeyPairSupport.verify`) au plus toutes les 60 min (`KEY_PROBE_INTERVAL_MINUTES`) ; refus (401/403) ⇒ CRIT, exchange injoignable ⇒ verdict précédent conservé ; credential désactivée ou supprimée ⇒ alarme résolue |
| `LINK_SUSPENDED` | Famille DATA, impact EXÉCUTION : une alarme WARN par lien suspendu (`LinkStateService` : actif en liste noire, provider déconnecté ou clé supprimée, route d'achat retirée du catalogue). Sujet `lien#<id> <ACTIF> (user <id>)` : pas de doublon, acquittable, clôturée à la passe où la cause disparaît. Catalogue en base, aucun réseau. Notification de l'utilisateur : hors périmètre |
| `DATABASE` | Requête de test, lenteur > 1 s |
| `DISK` | Espace libre < 15 % (WARN) / < 5 % (CRIT) |
| `INTERNET_ACCESS` | Deux sujets. `internet-ip` : TCP/443 vers `1.1.1.1` / `9.9.9.9` sans DNS, les deux en échec ⇒ CRIT. `dns` : résolution des hôtes d'exchanges (`api.kraken.com`, `www.okx.com`, `api.binance.com`) ; aucun résolu avec l'accès IP OK ⇒ CRIT « DNS en panne », une partie ⇒ WARN ; accès IP coupé ⇒ UNKNOWN (pas de double alarme). Aucune requête vers un exchange, délai max 3 s par sonde (`DoctorDefaults.INTERNET_PROBE_*`) |
| `LOG_ERRORS` | ERROR ≥ 1 ou WARN ≥ 50 sur 24 h (`DoctorLogBuffer`, appender logback en mémoire, borné, vidé au redémarrage) |

Un contrôle qui lève une exception produit un WARN sujet `_contrôle`. Seuils : constantes de `DoctorDefaults`. Jobs attendus : propriété `tradeio.doctor.expected-jobs` (défaut `RainbowLivePass2355Job,ExecutionPlanJob,ExecutionJob`) ; le cron est lu dans l'environnement (valeur `-` = désactivé ⇒ CRIT pour un job attendu).

## Alarmes (`doctor_alarm`)
`AlarmService.reconcile` après chaque passe. Clé = `checkId:subject`. États ACTIVE / ACKED / RESOLVED.
- WARN/CRIT ⇒ crée ou met à jour (`lastSeen`, `occurrences`).
- OK, ou sujet disparu d'un contrôle évalué ⇒ RESOLVED. UNKNOWN ne crée ni ne résout.
- Défaut revenu < 24 h après sa résolution (`ALARM_REOPEN_WINDOW_HOURS`, ex. tampon de logs vidé au redémarrage) ⇒ **même alarme rouverte** (`occurrences` cumulées), pas de nouvelle ligne ; au-delà ⇒ nouvelle alarme.
- Alarme levée **hors passe** : `NEW_ASSET_PROPOSAL:<actif>` (« Actif à valider », WARN, DATA / QUALITY), ouverte par `AlarmService.raise` quand une proposition de nouvel actif est enregistrée, close par Valider / Refuser, lien `/admin#assets` ; aucun contrôle ne la réconcilie ([`15m-pipeline-nouvel-actif.md`](15m-pipeline-nouvel-actif.md)).
- Acquitter / reporter (1–720 h) masque sans résoudre ; réouverture si la gravité monte ou à l'échéance du report.
- Les alarmes résolues et les `job_run` sont purgés après 90 jours (`JobRunPurgeJob`).

## Exécution
`DoctorJob` (premier passage 60 s après le démarrage, puis toutes les 5 min) appelle `DoctorService.runAll` (synchronisé). Propriétés : `tradeio.doctor.interval-ms`, `tradeio.doctor.kill-switch-lifted-warn-hours`, `tradeio.doctor.initial-delay-ms`, `tradeio.doctor.purge-cron`, `tradeio.doctor.expected-jobs`, `tradeio.doctor.internet-probe-hosts` (hôtes sondés par `INTERNET_ACCESS`, liste séparée par des virgules). Les profils sans crons (dev) doivent surcharger `expected-jobs`. **Profil `recette`** : `DoctorService` ignore, sans verdict ni alarme, `JOB_SCHEDULE` (crons coupés), `EXECUTION_STATE:master-key` (clé maître absente) et `LOG_ERRORS` (ERROR attendues : clé maître, 401 des smoke tests ; la recette surveille son journal elle-même) — `DoctorDefaults.RECETTE_IGNORED`. Les autres contrôles restent actifs.

## API et interface
Endpoints `/api/admin/doctor/*` : [`api/rest-endpoints.md`](../api/rest-endpoints.md). Ack et report journalisés (`AdminAction.DOCTOR`, cible `alarm:<id>`).
Onglet « Diagnostic » de `/admin` (`fragments/admin/doctor.html`, `js/admin/doctor.js`) : statut global, alarmes (Acquitter / Reporter 1 h, 24 h, 7 j, option « résolues »), contrôles par famille, bouton « Relancer », journal des jobs paginé. Pastille de l'onglet = alarmes ouvertes (CRIT rouge, sinon WARN), rafraîchie toutes les 60 s.
Le même compteur (CRIT ⇒ rouge, sinon orange, masqué à 0) augmenté des propositions de nouvel actif à valider apparaît sur l'entrée « Administration » du menu `/app` pour les admins : `app/fragments.html` (`#tio-admin-count`), `static/assets/app/js/admin-alarms.js` (poll `/api/admin/doctor` toutes les 60 s, lit `menuCount`), style `.tab-count` dans `tradeio.css`. `menuCount` = CRIT + WARN ouvertes non acquittées − alarmes `NEW_ASSET_PROPOSAL` ouvertes + propositions `PROPOSE` (`pendingAssets`) : une proposition = 1 point, conservée après acquittement de son alarme ; la pastille de l'onglet « Diagnostic » reste le compte des alarmes.
