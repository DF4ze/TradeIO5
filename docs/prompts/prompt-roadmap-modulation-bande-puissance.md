# Roadmap — Bande d'exposition, puissance, biais, onboarding

Séquence le chantier ; le design et les décisions vivent dans [`../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md`](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md) (§2 et §4 à §7, source de vérité). **Point avec Clem après chaque étape**, avant de lancer la suivante. Respecter `docs/CODING_RULES.md`, les constantes métier mutualisées, `DomainClock` (jamais `Instant.now()`). Chaque prompt d'implémentation d'étape demande, une fois l'étape terminée, de mettre à jour cette roadmap, `architecture/08-rainbow-live.md` et la doc touchée.

| # | Étape | Statut |
|---|---|---|
| 1 | Socle : paramètres admin + réglages utilisateur | fait (2026-10-13) |
| 2 | Moteur : modulation de bande, puissance, biais, journal | fait (2026-10-13) |
| 3a | Écrans (admin « Modulation », réglages utilisateur, « pourquoi ce montant ») | **fait (2026-10-13), point avec Clem à faire** |
| 3b | Onboarding (liaison automatique, baseAmount propre au lien, montants proposés) | **fait (2026-10-13), point avec Clem à faire** |

## Décisions de Clem à respecter (2026-10-13)
- Bande `[min, max]` (garde-fou, pas de cible) ; modulation uniquement dans les marges `m = 25 %` de la largeur, rien au centre ; vente accentuée ×2 max, achat → 0 à la borne.
- Puissance : un seul scalaire. Biais β borné à ±0,5, défaut 0. G = 2 (réglable par l'admin).
- Journal précis de chaque modulateur à chaque action ; garde-fou anti flip-flop.
- Paramètres de modulation réglables depuis l'interface admin.
- **Pas d'ordre `REBALANCE`** : on n'achète et ne vend que quand le Rainbow le dit ; hors bande, le moteur ne fait que moduler les quantités (achat → 0 / vente accentuée). Pas d'anti flip-flop dédié, pas d'alerte de bande (l'alerte est le bag vide) ; l'écran informe l'utilisateur.
- Hors scope : wallet agrégé multi-exchange, backtest de la bande.

## Étape 1 — Socle
Prompt : [`prompt-implementation-modulation-etape1.md`](prompt-implementation-modulation-etape1.md).
- Constantes mutualisées (défauts et bornes absolues, type `ExecutionDefaults`, 8 paramètres : `rebalanceDailyCapPct` retiré, plus d'ordre `REBALANCE`) ; entité singleton `RainbowModulationControl` sur le modèle de `ExecutionControl` (relue à chaque passe, ligne absente ⇒ défauts), `GET`/`PUT /api/admin/rainbow-modulation` (ADMIN, validation par bornes absolues, écriture dans `AdminActionLog`).
- Réglages utilisateur (bande `min`/`max`, puissance, biais) sur `UserTradingSettings` (colonnes nullables) + endpoints scopés user + validation (largeur minimale, bornes admin).
- Critères : défauts et bornes testés, impossible de dépasser une borne absolue par la base, modification admin journalisée, aucun effet sur le moteur encore.
- Réalisé : `service/modulation/{ModulationDefaults, RainbowModulationControlService}`, `model/entity/modulation/RainbowModulationControl`, `RainbowModulationControlInitializer` (`@Order(71)`), `service/admin/AdminModulationService` (`AdminAction.MODULATION`), `AdminModulationController`, `UserModulationSettingsController`, `UserTradingSettingsService#{get,set}ModulationSettings` + `effectiveSettings` (à consommer à l'étape 2). Suite verte (1232 tests), `V1__init.sql` régénéré et `flyway.check` OK.
- **Point**.

## Étape 2 — Moteur (fait)
Prompt : [`prompt-implementation-modulation-etape2.md`](prompt-implementation-modulation-etape2.md). Réalisé : `ExposureModulator` (calcul pur), `ExposureLedger`/`ExposureBook`, `LiveModulation`, branchement dans `LiveSizing` et `RainbowLiveExecutionService`, `LiveBlockReason.BAND_LIMIT`, journal JSON `RainbowLiveLiveBlock.modulation` (exposé dans `LiveSnapshotDto`), hors `inputsHash`. Écart au prompt : journal en JSON (pas de colonnes typées) ; prix manquant ⇒ exposition inconnue (pas de lecture D1 en base).
## Étape 3b — Onboarding (fait)
Voir [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md) (« Onboarding », « Montant de base du lien »). Choix pris sans validation de Clem, à confirmer : cash du wallet réparti à parts égales entre ses liens ; multiplicateur moyen = moyenne de x2, x1, x0,5 ; bande proposée neutre ; déclenchement manuel (bouton) et non à la sauvegarde de la clé.
