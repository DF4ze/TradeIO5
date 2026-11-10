# Docteur / Diagnostic Admin — reste à faire

Vérifié le : 2026-11-06. Le socle, le moteur, l'API et l'onglet Admin sont en place : voir [`architecture/12-doctor.md`](../architecture/12-doctor.md). Conception : [`etudes/etude-diagnostic-docteur-admin.md`](../etudes/etude-diagnostic-docteur-admin.md).

## Décisions
- Observateur uniquement : le Docteur n'agit jamais seul (phase dev/test).
- Pas de notification pour l'instant ; page Admin seule, alarmes visibles côté Admin uniquement.
- `DecisionOrchestratorJob` (cron 06:00) exclu du périmètre.

## TODO
- [ ] Agrégation des logs ERROR/WARN par signature (V1 : simple comptage 24 h en mémoire, vidé au redémarrage).
- [ ] Contrôles non faits en V1 : fraîcheur des candles (B3), ping des providers (B1/B2), test des credentials (C3), jobs secondaires (A6), écarts de config.
- [ ] **Catégorie « Clés API »** dans la page Diagnostic (famille dédiée, aujourd'hui `KEY_REJECTED` est classé EXECUTION) et **diagnostic de toutes les clés** : étendre la sonde réelle (`KEY_REJECTED`, limitée aux providers à paire générée) à toutes les credentials actives (OKX, Kraken, etc.), c'est le contrôle C3 ci-dessus.
- [ ] **Bot Telegram** : push mobile des alarmes CRIT.
- [ ] **« Doctor advertising »** : le Docteur pousse activement ses alarmes (sans ouvrir la page).
- [ ] **Notifications par utilisateur** (alertes `UserAlertService` / credentials KO poussées à l'utilisateur concerné).
- [ ] Heartbeat sortant (méta-surveillance du Docteur).
- [ ] À l'implémentation : intégrer `DecisionOrchestratorJob` au Docteur (journal `job_run`, contrôles A2/A6) et analyser sa stack trace récurrente (`TradeIO5-error.2026-11-04.log`, déjà vue les 11 et 12/10).
