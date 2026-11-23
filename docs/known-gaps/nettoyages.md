---
statut: gap
vérifié_le: 2026-11-07
modifié_le: 2026-11-15
empreinte: da3056d88e
---
# Nettoyages à prévoir

Vérifié le : 2026-11-07. Points de nettoyage / purge **non faits**, à regrouper ici.

## Déjà en place (pour mémoire)

- Alarmes résolues et `job_run` : purge après 90 jours (`JobRunPurgeJob`, cf. [`../architecture/12-doctor.md`](../architecture/12-doctor.md)).
- Owner inactif du moteur de décision : archivage (cf. architecture multi-utilisateur).

## À faire

- **Actifs explorés jamais affectés** (Espace Expert, simulation d'un actif jamais utilisé) : l'`Asset`, ses `asset_provider` et ses candles restent en base. Définir un critère de purge (durée sans usage, absence de preset ou d'affectation) et ce qui est supprimé (actif seul, ou aussi candles).
- **Restes du modèle d'avant les Affectations** (code legacy, tables, colonnes `preset_id` et `strategy_revision` retirés par `V5__nettoyage_legacy.sql`, appliquée en prod le 2026-11-23) : reste à traiter :
  - valeurs d'enum `DETACH` / `STRATEGY_CHANGED` de `RainbowLivePresetEventType`, encore possibles dans les lignes historiques ;
  - noms hérités dans les DTO et le JS qui désignent maintenant l'Affectation (ex. `PresetEventDto`, `presetBeforeName`, endpoint `/preset-events`, table `rainbow_live_preset_event`) : à renommer avec leur migration.
- **Simulations Espace Expert** : persistance et liste des simulations, clarification de `maxHorizonDays` dans `GET /api/expert/assets` : voir [`../etudes/etude-espace-expert.md`](../etudes/etude-espace-expert.md).
