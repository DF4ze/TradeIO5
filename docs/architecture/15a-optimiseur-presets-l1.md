---
statut: courant
sujet: Optimiseur de presets L1 par actif — PresetOptimizer (mode PRESET_L1) : espace δ configurable, évaluation V6, veto d'exposition, walk-forward, cadences
vérifié_le: 2026-11-17
modifié_le: 2026-11-20
empreinte: 510829477a
---
# Optimiseur de presets L1 (`service/optimizer/Preset*`)

Vérifié le : 2026-11-17 (`service/optimizer/Preset*`, `OptimizerService`, tests `PresetSpaceTest`, `PresetOptimizerTest`). Socle (évaluateur, moteur de recherche, rapport, lancement) : [`15-optimiseur.md`](15-optimiseur.md). Position de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Optimiseur de presets L1 »). Mesures : [`../etudes/etude-optimisation-presets.md`](../archive/etudes/etude-optimisation-presets.md).

Cherche, **par actif**, le jeu Rainbow Bull / Bear qui gagne le plus maintenant, en partant du preset en place (point de départ **et** comparateur). **Challenger seulement** : rien n'est appliqué ni persisté (pas de migration), live inchangé.

## Espace de recherche
- `PresetParam` (10 paramètres libérables) : `atrMultDown2/Down1/Up1/Up2/Up3` [0, 20], `trailingStopBuyPct/SellPct` [0,1 ; 50], `cooldownDays` [0 ; 365] et `fixedDelayDays` [1 ; 365] (entiers), `sellFraction` [0,01 ; 1]. Gelés par construction : `smaPeriod`, `atrPeriod`, ATH, Moon, multiplicateurs, montant de base (celui du lien), Trend.
- `PresetSpaceConfig` (record, source remplaçable `PresetSpaceSource`, défauts `PresetSpaceSource.defaults()`) : par paramètre libre / gelé + δ relatif (défaut ±25 %, `OptimizerDefaults.PRESET_DELTA`, configurable par paramètre) ; clé inconnue ⇒ `IllegalArgumentException`. Pas d'UI. Chaque paramètre s'applique au set Bear **et** au set Bull (`bear.<p>` / `bull.<p>`, ~20 dimensions).
- `PresetParams.build` : rayon = |valeur en place| × δ, grille `PRESET_GRID_STEPS` (4) crans par rayon, bornes dures du domaine, entiers arrondis (cran ≥ 1). Le départ de la recherche = le preset en place exactement. Une valeur nulle (rayon relatif vide) ou sans cran entier dans le rayon est **gelée avec motif** (rapporté). `validator` : ordre des seuils ATR conservé (`RainbowAtrTuning.violations()`), tout candidat hors domaine ⇒ `INVALID_CANDIDATE` sans rejeu, jamais retenu.
- Au-delà de 12 dimensions, `SobolSequence` complète avec un tirage uniforme à graine fixe (déterministe).

## Évaluation (`PresetScoring`, `PresetObjective`)
`ReplayScorer` inchangé : le template du lien est rejoué avec les sets du candidat (`LinkSignalReplay.Config.withSets`). Un actif à la fois, wallet à un lien, mêmes fenêtres 12 m / 24 m / test que le protocole V6, **référence DCA fixe à autonomie 360 j jamais ajustée au gain** (`CalibrationConfig.V6_REFERENCE_AUTONOMY_DAYS`), montant de base = celui du lien, Trend figée, politique de bande constante du profil (`ResolvedCandidate(null, athCap du profil, 1.0)`). Veto supplémentaire **`EXPOSURE_BAND`** : exposition moyenne d'une fenêtre notée > borne haute de la bande du profil. Timing pur rapporté (excès à exposition égale). Bousculades : fin −N j. Pool dédié, compteur d'essais, un `PresetObjective` par actif.

## Walk-forward (`PresetOptimizer.run`)
Plis semestriels (`PRESET_FOLD_ENDS` : 2023-05-15 → 2025-11-15), test 6 mois suivants, entraînement coupé à la fin du pli (aucun futur), une graine, un run. Par pli : recherche (`SearchEngine`), puis test du challenger **et** du preset en place.
- Pli à entraînement vetoé (candidat retenu vetoé ou preset vetoé au départ) : `GARDER_PRESET` / `ENTRAINEMENT_VETOE`, `chosen` nul, jamais un `INVALID_CANDIDATE`.
- Historique insuffisant : actif `NON_OPTIMISABLE` (`HISTORIQUE_INSUFFISANT`), bougies disponibles / requises rapportées ; aucun paramètre libre : `NON_OPTIMISABLE` (`AUCUN_PARAMETRE_LIBRE`). Jamais d'exclusion silencieuse.
- `PresetVerdict` (pure) : `ADOPTER` si majorité de plis gagnés, aucun veto bloquant en test (drawdown, capital, mise finale), médiane Δ hors échantillon > 0 et part de plis à entraînement vetoé < `PRESET_MAX_TRAIN_BLOCKED_SHARE` (30 %) ; sinon `GARDER_PRESET` (résultat nul valide).
- **Cadences** (`runWithCadences`, 30 / 90 / 180 j depuis `PRESET_CADENCE_START`) : à chaque ré-optimisation le challenger est appliqué aux X jours suivants, comparé au preset statique sur les mêmes segments (états remis à zéro par segment) ; gain cumulé hors échantillon de la chaîne vs statique. Recherches mémoïsées par fin d'entraînement (la grille 30 j contient les deux autres). `estimateCadenceBudget` : budget estimé depuis les durées mesurées du walk-forward.

## Rapport et lancement
`PresetOptimizationReport` (JSON, par actif : preset en place, espace + gelés, plis, challenger, Δ OOS, exposition, timing pur, vetos, essais, verdict ; multiplicité : recherches, essais, rejeux) ; mêmes entrées + même graine ⇒ JSON identique hors `timings` (`toJsonWithoutTimings`), empreintes des bougies par pli.
- Runner manuel `-Doptimizer.manual=preset` (walk-forward + budget estimé, `target/preset-optimization.*`) et `preset-cadences` (`target/preset-cadences.*`, bench coûteux : demander avant de lancer).
- Endpoint admin : `mode = PRESET_L1` (`OptimizerRunRequest.presetL1`, profil BALANCED par défaut ; `CUSTOM` ⇒ erreur), cible tracée `optimizer:preset-l1`, walk-forward seul (cadences via le runner). Mêmes garde-fous que `POLICY_L2` : désactivé par défaut, un run à la fois, mémoire + TTL.
