---
statut: courant
sujet: Calage de l'exposition par profil sur le pire cas avec marge de sécurité mesurée (étape C3) — fenêtres glissantes, calage ancré, règle de k, runner exposure-calibration, équivalence onboarding
vérifié_le: 2026-11-19
modifié_le: 2026-11-20
empreinte: a95fcd27a3
---
# Calage de l'exposition sur le pire cas (`ExposureCalibrationStudy`, `ExposureCalibrationConfig`, `ExposureCalibrationReport`)

Outil de mesure (hors prod, rien appliqué ni persisté). Levier unique = multiplicateur du montant de base du lien (même levier que [C1](15d-mesure-exposition-egale.md) et [C2](15f-politique-exposition-regime.md)) ; preset, Trend, modulation, capital, coûts inchangés. Constats chiffrés : [`../etudes/etude-calage-exposition.md`](../archive/etudes/etude-calage-exposition.md).

## Pire cas (sans marge)
- Fenêtres glissantes de `windowMonths` = 24 mois, pas `windowStepDays` = 30 j, dernière terminée à la fin des bougies ; première fenêtre ≥ `SensitivityEvaluator.firstReplayableDay` (fin du préchauffage) : aucune fenêtre tronquée. Plus l'historique complet.
- Pour chaque niveau de la grille (celle de C1 + élargissements C2, 16 niveaux) : drawdown de calage = pire drawdown sur les fenêtres et l'historique complet (`worstByLevel`).
- Niveau sans marge = `ExposurePolicyStudy.constantMultiplier(grid, worstDd, cap)` (premier franchissement du plafond, interpolé ; statuts INTERPOLE / SOUS_LE_MINIMUM / PLAFOND_NON_ATTEINT). Plafonds lus dans `ModulationDefaults` (−15 / −25 / −40 / −60 %) via `FrontierConfig.profileCaps()`.
- Fenêtres qui contiennent le bear 2021-22 (`bearFrom` → `bearTo`, convention de dates) : comptées, mais chevauchantes donc non indépendantes.

## Marge k (calage ancré)
- `anchors()` : T tous les `anchorStepMonths` = 3 mois entre `anchorFromMonthsBack` = 72 et `anchorToMonthsBack` = 12 (21 dates). Calage à T : seules les fenêtres terminées avant T et l'historique jusqu'à T−1 (`cutoffExclusive`), plafond `cap / k`, `k ∈ margins` = {1 ; 1,1 ; 1,25 ; 1,5 ; 2}.
- Test : évaluateur neuf sur `[début, T + testMonths (12) − 1 j]`, replay depuis T au niveau choisi ; r(T) = drawdown réel / plafond ; plafond « tenu » si drawdown ≥ plafond − 1e-9. Test « bear » si recouvrement avec le bear ≥ `bearMinOverlapDays` = 90 j. Replays de test mis en cache par (T, multiplicateur).
- **Règle (fixée avant lecture)** : k recommandé = plus petit k tel que, pour chacun des 4 profils, la part des T tenus ≥ `holdShare` = 0,90 ; le pire profil est nommé ; si aucun k, le dire.
- Niveau recommandé final = calage sur toutes les données avec `cap / k`. Rapport : exposition moyenne (définition B'), gain médian des 4 blocs de C1, écart au niveau C1, drawdown réel historique complet contre plafond.

## Runner et sorties
- `./mvnw test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=exposure-calibration` (BTC, ETH ; pool dédié `POOL` ; empreinte des bougies). Estimation du coût écrite dans `target/exposure-calibration-plan.txt` avant le run ; arrêt si > 10 min sauf `-Doptimizer.calibration.confirm=true`.
- Sorties : `target/exposure-calibration.json|md` (tableau profil × actif, r(T) par date, tableau des k, équivalence onboarding, multiplicité).
- Deux fenêtres de calage identiques aux dates près ne sont jamais rejouées deux fois (cache par cellule).

## Tests (`ExposureCalibrationTest`, configuration réduite)
Fenêtres complètes ; niveau sans marge sur les 4 blocs = C1 (même interpolation) ; k = 1 = plafond brut ; aucune lecture après T (lignes empoisonnées) ; rejeu avant T indépendant des bougies postérieures (bougies tronquées à T−1 → `Parity` identique) ; règles pures (`pick`, `ratio`, `holds`, `firstSufficient`) ; invariants du rapport ; reproductibilité (série contre pool).

## Équivalence avec l'onboarding (lecture seule, rien implémenté)
- Onboarding : `RainbowLiveOnboardingService.referenceBaseAmount(cash, jours, mult. moyen)` = `cash / (jours × mult. moyen)` arrondi vers le bas, mult. moyen = (multX2 + multX1 + multX0,5) / 3 ; `ModulationDefaults.AUTONOMY_DAYS` = 90, bornes 7–365.
- Le ×1 de C1 / C2 / C3 = base enregistrée du lien (1,00 USDC), pas la proposition d'onboarding (`AssetInput.referenceBase`, lue seulement par `ReplayScorer`). Autonomie équivalente d'un niveau ×m = `cash / (mult. moyen × m × 1,00)`.
- Niveaux recommandés → autonomies de 482 à 3 090 j, hors bornes 7–365 j : mapping profil → autonomie impossible sans lever les bornes ; équivalence exacte sur le seul montant de base (le moteur applique en plus zones, ATH, modulation). Tant que ce n'est pas fait, le mode Full Autonome v1 doit porter le niveau comme multiplicateur de base de profil.

## Limites
Un seul actif par run (cash non partagé) ; plafond = cible calibrée sur l'historique, pas garantie ; 8 ans = un seul grand bear (2021-22) par actif, les T dont le test le recouvre ne sont pas indépendants ; test sur 12 mois alors que le plafond est lu sur 24 mois et l'historique ; frais et glissement réels absents.
