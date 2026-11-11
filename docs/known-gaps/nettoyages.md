---
statut: gap
vérifié_le: 2026-11-07
modifié_le: 2026-11-11
empreinte: 336d1fc1b7
---
# Nettoyages à prévoir

Vérifié le : 2026-11-07. Points de nettoyage / purge **non faits**, à regrouper ici.

## Déjà en place (pour mémoire)

- Alarmes résolues et `job_run` : purge après 90 jours (`JobRunPurgeJob`, cf. [`../architecture/12-doctor.md`](../architecture/12-doctor.md)).
- Owner inactif du moteur de décision : archivage (cf. architecture multi-utilisateur).

## À faire

- **Actifs explorés jamais affectés** (Espace Expert, simulation d'un actif jamais utilisé) : l'`Asset`, ses `asset_provider` et ses candles restent en base. Définir un critère de purge (durée sans usage, absence de preset ou d'affectation) et ce qui est supprimé (actif seul, ou aussi candles).
- **Restes du modèle d'avant les Affectations** (migration constatée sur le VPS le 2026-11-07, premier cron réel OK ; prêts à supprimer, après sauvegarde de la base) :
  - tables `rainbow_live_preset`, `rainbow_live_trend_config`, `rainbow_asset_strategy`, `rainbow_live_user_settings` (plus aucune écriture ; les deux premières ne servent qu'à `AffectationMigrationService` en lecture seule) ;
  - colonnes `preset_id` (nullables depuis la migration) de `rainbow_live_run`, `rainbow_live_engine_state`, `rainbow_live_binding` ;
  - entités et repositories en lecture seule `model/entity/dca/bench/legacy/*` et `repository/dca/bench/legacy/*`, `AffectationMigrationService` (qui rattache aussi au démarrage les liens `rainbow_live_binding` sans Affectation à celle de leur (utilisateur, actif)) et `AffectationMigrationInitializer` ;
  - colonne `strategy_revision` de `rainbow_live_preset_event` et valeurs d'enum `DETACH` / `STRATEGY_CHANGED` de `RainbowLivePresetEventType` encore possibles dans les lignes historiques ; champ `strategyRevision` de `PresetEventDto` (toujours nul) ;
  - noms hérités dans les DTO et le JS qui désignent maintenant l'Affectation (ex. `PresetEventDto`, `presetBeforeName`, endpoint `/preset-events`, tables `rainbow_live_preset_event`) : à renommer avec leur migration.
- **Simulations Espace Expert** : persistance et liste des simulations, clarification de `maxHorizonDays` dans `GET /api/expert/assets` : voir [`../etudes/etude-espace-expert.md`](../etudes/etude-espace-expert.md).
- **Rappel avant le chantier legacy** : sauvegarde de la base (la migration des Affectations est déjà constatée sur le VPS).
- **Procédure du chantier legacy (Flyway actif)** : 1) retirer le code legacy (`AffectationMigrationService`, `AffectationMigrationInitializer`, entités/repositories `legacy/*`), qui lit `preset_id` et `rainbow_live_preset` ; 2) écrire un `V2__nettoyage_legacy.sql` idempotent (colonnes `preset_id` avec leurs FK et uniques d'abord, puis tables) ; 3) répéter en recette sur copie de prod ([`../operations/recette.md`](../operations/recette.md)) puis déployer. Ne jamais inverser 1) et 2) : constaté en recette le 2026-11-11 (`Unknown column 'preset_id'`).
