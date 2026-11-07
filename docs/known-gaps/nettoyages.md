# Nettoyages à prévoir

Vérifié le : 2026-11-06. Points de nettoyage / purge **non faits**, à regrouper ici.

## Déjà en place (pour mémoire)

- Alarmes résolues et `job_run` : purge après 90 jours (`JobRunPurgeJob`, cf. [`../architecture/12-doctor.md`](../architecture/12-doctor.md)).
- Owner inactif du moteur de décision : archivage (cf. architecture multi-utilisateur).

## À faire

- **Actifs explorés jamais affectés** (Espace Expert, simulation d'un actif jamais utilisé) : l'`Asset`, ses `asset_provider` et ses candles restent en base. Définir un critère de purge (durée sans usage, absence de preset ou d'affectation) et ce qui est supprimé (actif seul, ou aussi candles).
- **Restes du modèle d'avant les Affectations** (à supprimer une fois la migration constatée sur le VPS, après sauvegarde de la base) :
  - tables `rainbow_live_preset`, `rainbow_live_trend_config`, `rainbow_asset_strategy`, `rainbow_live_user_settings` (plus aucune écriture ; les deux premières ne servent qu'à `AffectationMigrationService` en lecture seule) ;
  - colonnes `preset_id` (nullables depuis la migration) de `rainbow_live_run`, `rainbow_live_engine_state`, `rainbow_live_binding` ;
  - entités et repositories en lecture seule `model/entity/dca/bench/legacy/*` et `repository/dca/bench/legacy/*`, `AffectationMigrationService` et `AffectationMigrationInitializer` ;
  - colonne `strategy_revision` de `rainbow_live_preset_event` et valeurs d'enum `DETACH` / `STRATEGY_CHANGED` de `RainbowLivePresetEventType` encore possibles dans les lignes historiques ; champ `strategyRevision` de `PresetEventDto` (toujours nul) ;
  - noms hérités dans les DTO et le JS qui désignent maintenant l'Affectation (ex. `PresetEventDto`, `presetBeforeName`, endpoint `/preset-events`, tables `rainbow_live_preset_event`) : à renommer avec leur migration.
- **Runs et états moteur des anciens presets non retenus** : supprimés par la migration (un seul preset par actif est converti en Affectation) ; rien à purger côté données.
