# Docteur — diagnostic et alarmes (Admin)

Observateur : il lit l'état du système, déduit des verdicts et tient des alarmes. Il **n'agit jamais** (aucune correction, aucun relancement de job). Étude : [`etudes/etude-diagnostic-docteur-admin.md`](../etudes/etude-diagnostic-docteur-admin.md) ; reste à faire : [`known-gaps/doctor-diagnostic.md`](../known-gaps/doctor-diagnostic.md).

## Journal des jobs (`job_run`)
`JobRunRecorder` enveloppe chaque job planifié (Rainbow 23:55, plan, exécution, backup historique, média ×2, ETF flow, snapshot, archivage) et les déclenchements manuels (`RainbowLiveAdminController.run`, `AdminPlanTriggerService.plan`). Une ligne par exécution : `jobName`, `triggeredBy` (CRON/MANUAL), `status` (OK/PARTIAL/FAILED), `startedAt`, `durationMs`, `errors`, `summary`, `errorMessage`. Best-effort : un échec d'écriture est loggé en WARN et n'affecte jamais le job ; l'exception du job est toujours relancée. `DecisionOrchestratorJob` n'est pas journalisé (hors périmètre).

## Contrôles (`DoctorCheck`)
Un contrôle est une sonde pure qui renvoie un `CheckResult` (sujet, statut OK/UNKNOWN/WARN/CRIT, message) par sujet. Famille (PLANNING/DATA/EXECUTION/INFRA/LOGS) et impact (EXECUTION/QUALITY/INFO) portés par le contrôle.

| Id | Vérifie |
|---|---|
| `JOB_SCHEDULE` | Pour chaque job attendu : cron présent et valide, dernier `job_run` présent, pas en retard (> 10 min après le dernier tir théorique + 60 s), pas PARTIAL (WARN) / FAILED (CRIT). Aucun run enregistré ⇒ UNKNOWN |
| `EXECUTION_STATE` | Mode, clé maître, kill switch (levé > 6 h ⇒ WARN) |
| `PLAN_PROBLEMS` | Plans des 3 derniers jours en erreur / bloqués > 30 min |
| `TRANSIT_STOCK` | Stocks de transit non résolus depuis > 12 h |
| `DATABASE` | Requête de test, lenteur > 1 s |
| `DISK` | Espace libre < 15 % (WARN) / < 5 % (CRIT) |
| `LOG_ERRORS` | ERROR ≥ 1 ou WARN ≥ 50 sur 24 h (`DoctorLogBuffer`, appender logback en mémoire, borné, vidé au redémarrage) |

Un contrôle qui lève une exception produit un WARN sujet `_contrôle`. Seuils : constantes de `DoctorDefaults`. Jobs attendus : propriété `tradeio.doctor.expected-jobs` (défaut `RainbowLivePass2355Job,ExecutionPlanJob,ExecutionJob`) ; le cron est lu dans l'environnement (valeur `-` = désactivé ⇒ CRIT pour un job attendu).

## Alarmes (`doctor_alarm`)
`AlarmService.reconcile` après chaque passe. Clé = `checkId:subject`. États ACTIVE / ACKED / RESOLVED.
- WARN/CRIT ⇒ crée ou met à jour (`lastSeen`, `occurrences`).
- OK, ou sujet disparu d'un contrôle évalué ⇒ RESOLVED. UNKNOWN ne crée ni ne résout.
- Acquitter / reporter (1–720 h) masque sans résoudre ; réouverture si la gravité monte ou à l'échéance du report.
- Les alarmes résolues et les `job_run` sont purgés après 90 jours (`JobRunPurgeJob`).

## Exécution
`DoctorJob` (premier passage 60 s après le démarrage, puis toutes les 5 min) appelle `DoctorService.runAll` (synchronisé). Propriétés : `tradeio.doctor.interval-ms`, `tradeio.doctor.initial-delay-ms`, `tradeio.doctor.purge-cron`, `tradeio.doctor.expected-jobs`. Les profils sans crons (dev) doivent surcharger `expected-jobs`.

## API et interface
Endpoints `/api/admin/doctor/*` : [`api/rest-endpoints.md`](../api/rest-endpoints.md). Ack et report journalisés (`AdminAction.DOCTOR`, cible `alarm:<id>`).
Onglet « Diagnostic » de `/admin` (`fragments/admin/doctor.html`, `js/admin/doctor.js`) : statut global, alarmes (Acquitter / Reporter 1 h, 24 h, 7 j, option « résolues »), contrôles par famille, bouton « Relancer », journal des jobs paginé. Pastille de l'onglet = alarmes ouvertes (CRIT rouge, sinon WARN), rafraîchie toutes les 60 s.
