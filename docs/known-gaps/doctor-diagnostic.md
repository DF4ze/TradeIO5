# Docteur / Diagnostic Admin — chantier ouvert

Vérifié le : 2026-11-06 (code + décisions de Clem). **Rien n'est implémenté.** Conception : [`etudes/etude-diagnostic-docteur-admin.md`](../etudes/etude-diagnostic-docteur-admin.md).

## Besoin
Une page Administration « Diagnostic » qui résume les points de contrôle cruciaux à l'exécution (dernier run de planification, provider de candles, erreurs/warnings des logs, plans, credentials, infra) avec un gestionnaire d'alarmes (acquittement).

## État actuel (manques)
- Jobs : aucune trace persistée des exécutions (`log.info` seul) → pas de « dernier run OK ? ».
- Logs : fichiers logback, WARN non isolé, rien en GUI.
- Providers : pas de mémoire dernier succès/échec.
- Seules alertes existantes : `UserAlertService` (par utilisateur, sans acquittement).

## Décisions
- Observateur uniquement : le Docteur n'agit jamais seul (phase dev/test).
- Pas de notification pour l'instant ; page Admin seule.
- Alarmes visibles côté Admin uniquement.

## Périmètre
- `DecisionOrchestratorJob` (cron 06:00) est **exclu** de l'analyse du Docteur pour l'instant (décision 2026-11-06).

## TODO
- [ ] Socle : journal `job_run` + wrapper des jobs.
- [ ] Moteur de checks, table `alarm`, acquittement, page Admin.
- [ ] Agrégation des logs ERROR/WARN.
- [ ] **Bot Telegram** : push mobile des alarmes CRIT.
- [ ] **« Doctor advertising »** : le Docteur pousse activement ses alarmes (sans ouvrir la page).
- [ ] **Notifications par utilisateur** (alertes `UserAlertService` / credentials KO poussées à l'utilisateur concerné).
- [ ] Heartbeat sortant (méta-surveillance du Docteur).
- [ ] À l'implémentation : intégrer `DecisionOrchestratorJob` au Docteur (journal `job_run`, contrôles A2/A6) et analyser sa stack trace récurrente (`TradeIO5-error.2026-11-04.log`, déjà vue les 11 et 12/10).
