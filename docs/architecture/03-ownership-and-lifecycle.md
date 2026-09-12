# Multi-utilisateur : ownership, credentials, cycle de vie

Vérifié le : 2026-09-12 (`service/tree/decision/{UserArchivalService,OwnerRefreshGuard,DecisionScenarioSnapshotService,DecisionScenarioRestoreService,DecisionScenarioRestoreRunner}.java`, `model/dto/tree/scenario/ScenarioOwner`, `controller/AuthController`).

## ScenarioOwner

Toute la mémoire vivante du moteur `tree` (scénarios, décisions) est indexée par `ScenarioOwner`, pas par symbole seul. Deux natures d'owner :

- Un utilisateur réel (`ScenarioOwner.of(User)`), résolu depuis un id numérique côté API (`ScenarioOwner.fromString("<id>")`).
- Un owner technique spécial, `"SYSTEM"` (`SystemOwner`), utilisé pour la résolution de credentials qui ne sont pas propres à un utilisateur (ex. clés API de providers de marché partagées).

Les endpoints de lecture (`GET /api/admin/decision/scenarios`, `/decisions`) acceptent un paramètre `owner` optionnel dans cette même convention ; omis, ils retournent tous les owners confondus. **Exception notée dans le code** : `DecisionEngine.getAllActiveDecisions()` n'a pas de variante owner-scopée — le filtrage par owner sur `/decisions` est fait côté contrôleur, pas côté moteur.

## Résolution de credentials

`IndicatorCredentialResolver` (indicateurs) et `MediaCredentialResolver` (veille média) résolvent la credential à utiliser pour un provider donné. Le détail exact de la logique de fallback (utilisateur → système ?) n'a pas été retracé ligne à ligne dans ce lot — à vérifier dans `IndicatorCredentialResolver` si un comportement précis de fallback doit être documenté ici.

## Verrou anti-double-traitement : OwnerRefreshGuard

Empêche `DecisionOrchestrator` de retraiter un owner déjà traité dans la dernière heure. **En mémoire, pas en base** — un redémarrage de l'application le réinitialise entièrement (pas de persistance du verrou). Aucun endpoint pour le forcer/déverrouiller manuellement au 2026-09-12.

## Photo quotidienne + rejeu au redémarrage

- `DecisionScenarioSnapshotService.takeSnapshot()` : sérialise l'état vivant (scénarios + décisions actifs, tous owners) en base (`scenario_snapshots`/`decision_snapshots`). Déclenché manuellement via `POST /api/admin/decision/snapshot`, ou par le job `DecisionScenarioSnapshotJob` — **désactivé par défaut** (`tradeio.decision.snapshot-cron=-`).
- `DecisionScenarioRestoreService` + `DecisionScenarioRestoreRunner` : au démarrage de l'app, rejoue la dernière photo pour ne pas repartir d'un état vide. Vérifié par test dédié (pas seulement supposé).

## Archivage sur inactivité

`UserArchivalService.archiveInactiveUsers()` : évince de la mémoire active (`evictOwner`) les utilisateurs inactifs (seuil observé dans le plan de test manuel : `last_login` > 60 jours), et marque `archived_at` en base. Déclenché manuellement via `POST /api/admin/decision/archive`, ou par `UserArchivalJob` — **désactivé par défaut** (`tradeio.decision.archival-cron=-`).

**Restauration à la reconnexion** : `AuthController#authenticateUserForm` (`POST /api/auth/signinForm`) vérifie si `archivedAt != null` sur l'utilisateur qui se connecte ; si oui, appelle `DecisionScenarioRestoreService.restoreOwner(...)` et remet `archivedAt = null`. L'utilisateur redevient actif dès la connexion, mais ses scénarios/décisions ne réapparaissent dans `/scenarios`/`/decisions` qu'après le **prochain** cycle d'orchestration (pas immédiat à la connexion).

## Ce qui manque encore pour un vrai profil utilisateur (lié au gap décision→ordre)

`User` (`security/model/User.java`) n'a, au 2026-09-12, aucun champ de profil de risque. `RiskProfile` (enum `LOW/MEDIUM/HIGH`) et `UserProfile` (DTO avec `maxAllocationPerAsset`, `minCashReserve`, etc.) existent comme types mais ne sont peuplés nulle part avec de vraies données utilisateur. Détail et plan : [`known-gaps/decision-to-order-gap.md`](../known-gaps/decision-to-order-gap.md).

Un curseur de risque continu (0-10, distinct de l'enum `RiskProfile` à 3 paliers) existe et est déjà branché côté API/persistance : `UserTradingSettingsController` (`GET`/`PUT /api/user/risk-cursor`) + `UserTradingSettingsService`. Son utilisation réelle en aval (dans le calcul de sizing) n'a pas été vérifiée dans ce lot — à confirmer avant de le présenter comme "consommé par le moteur de décision".
