# Prompt d'implémentation — Espace Expert, lot 5 : finalisation

> Lot 5 de [`prompt-roadmap-espace-expert.md`](prompt-roadmap-espace-expert.md). Prérequis : lots 1 à 4 terminés.
> Étude : [`../etudes/etude-espace-expert.md`](../etudes/etude-espace-expert.md). Lire d'abord `docs/README.md`.

## Objectif

Clore le chantier : code mort retiré, doc d'architecture à jour (sans historique), étude réduite à ce qui reste ouvert, `nettoyages.md` fidèle à la réalité. **Aucune nouvelle fonctionnalité.**

## Décisions déjà prises

- **La migration n'est pas encore constatée sur le VPS** => le legacy nécessaire à la migration reste **en place** dans ce lot :
  `model/entity/dca/bench/legacy/*`, `repository/dca/bench/legacy/*`, `AffectationMigrationService`, `AffectationMigrationInitializer`, tables `rainbow_live_preset`, `rainbow_live_trend_config`, `rainbow_asset_strategy`, `rainbow_live_user_settings`, colonnes `preset_id`.
  => Leur suppression reste listée dans `docs/known-gaps/nettoyages.md` (déjà le cas) comme étape **post-VPS, après sauvegarde DB**. Ne rien dropper.
- **Renommages hérités** (`PresetEventDto`, `/preset-events`, `rainbow_live_preset_event`, `presetBeforeName`, `strategyRevision`) : **hors lot 5**. Ils touchent le schéma (ddl-auto, Flyway non activé => risque de colonnes orphelines) ; ils restent dans `nettoyages.md`, à faire avec leur migration.

## Travail

### 1. Code mort (hors legacy migration)
- Lister, pour chaque classe/fichier ci-dessous, les références (`grep -rlw`, **commandes ciblées, pas de grep global lourd**) puis supprimer si plus aucune référence hors tests :
  - `RainbowAssetStrategyInitializer`, `RainbowAssetStrategyService` et tout dérivé de `RainbowAssetStrategy*`, `RainbowLiveUserSettings*` encore présents en dehors de `legacy/` et de la migration ;
  - enums/valeurs devenus inutilisés (`RainbowLiveUiMode`, `RainbowLiveMode`, `FIXED`) ;
  - endpoints, DTO, méthodes de service/repository sans appelant ;
  - JS / fragments / CSS / templates orphelins (vérifier `static/assets/js`, `templates/fragments`, classes CSS de l'ancienne page `/user`) ;
  - tests devenus sans objet.
- `RainbowLiveDefaultPresets` : vérifier s'il sert encore (presets système de base). Si oui, le garder et le documenter ; sinon le supprimer.
- `UserPreferences` : vérifier que seul `displayMode` utile reste cohérent avec la garde `ExpertAccessGuard`. Ne pas modifier le comportement.
- Toute suppression doit laisser `mvn test` vert. Ne pas toucher aux tests de migration.

### 2. Cohérence des textes
- Messages, libellés, commentaires, Javadoc qui mentionnent l'ancienne page `/user`, le « preset unique par actif », `ui-mode`, `FIXED` => corriger ou supprimer.
- Liens morts dans les templates (menu, navbar, redirections) vers `/user`.

### 3. Documentation (cf. instructions du projet : à jour, pas d'historique)
- `docs/architecture/08-rainbow-live.md` : modèle final (TrendPreset, RainbowPreset, AssetAffectation, origines SYSTEM|USER, revision, 9 presets de base), propagation + reset moteur, règle 409 à la suppression.
- `docs/architecture/11-front-app.md` : pages `/app/expert/{trend|rainbow|affectation}`, garde EXPERT, entrée de menu conditionnelle, bandeau « vue incomplète sur téléphone ». Retirer toute trace d'« ancienne page /user n'existe plus » (historique) si ce n'est plus utile.
- `docs/architecture/07-security.md` : `ExpertAccessGuard`, `/api/expert/**`, droits ADMIN sur presets SYSTEM, audit `admin_action_log`.
- Nouvelle section (ou fichier `docs/architecture/13-espace-expert.md` + entrée dans `docs/README.md`) : simulation asynchrone (types TREND/RAINBOW/AFFECTATION, garde-fous : single-flight par actif/source, 1 simulation active par utilisateur, rate limit, TTL mémoire 30 min, 5 résultats, plage max cachée en base, repli signalé), format `ChartDto`, outil de rejeu `tools/rainbow-replay/`.
- `docs/README.md` : index à jour.
- `docs/etudes/etude-espace-expert.md` : ne garder **que ce qui reste ouvert** (découverte/création d'actifs, vue admin des affectations par utilisateur, persistance des simulations, etc.) + renvoi vers l'architecture. Supprimer le reste (décisions réalisées).
- `docs/known-gaps/nettoyages.md` : repasser point par point ; retirer ce qui est fait, mettre à jour « Vérifié le » à la date du jour, garder les restes post-VPS et renommages.
- `docs/prompts/prompt-roadmap-espace-expert.md` : marquer les lots 1 à 5 comme réalisés ou le supprimer selon la règle d'hygiène du repo (prompts/roadmaps terminés non conservés si la doc d'architecture les remplace — suivre `docs/README.md`).

### 4. Rappel production
Avant déploiement VPS : **sauvegarde de la base**, vérifier ensuite que la migration des Affectations s'est bien exécutée, puis seulement ouvrir le chantier de suppression du legacy.

## Hors lot (à ne pas faire)
Découverte/création d'actifs, vue admin des affectations par utilisateur, nettoyage des actifs explorés jamais affectés, sauvegarde DB segmentée, renommages hérités, suppression du legacy migration.

## Critères de fin
- `mvn test` vert, application démarre, parcours Expert OK (Trend, Rainbow, Affectation, simulation).
- Plus aucune référence à l'ancien modèle hors `legacy/` et la migration.
- Docs d'architecture à jour, sans historique ; étude réduite à l'ouvert ; `nettoyages.md` exact.
- Récap court : fichiers supprimés, docs modifiées, restes post-VPS.
