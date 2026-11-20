---
statut: courant
sujet: Réglage récent (étape D0) — réglage conjoint sur les 24 mois avant T, test en avançant à 3 et 6 mois, comparateurs, règle de verdict, runner recent-tune
vérifié_le: 2026-11-20
modifié_le: 2026-11-20
empreinte: 1fff1a320a
---
# Réglage récent persistant ? (`RecentTuneStudy`, `RecentTune`, `RecentTuneSpace`, `RecentTuneObjective`, `RecentTuneConfig`, `RecentTuneReport`)

Outil de mesure (hors prod, rien appliqué ni persisté). Il répond à : réglé sur les 24 mois avant une date T, le preset tient-il dans les 3 et 6 mois suivants, mieux que le preset en place et mieux qu'un bruit aléatoire ? Constats chiffrés : [`../etudes/etude-reglage-recent.md`](../archive/etudes/etude-reglage-recent.md).

## Dates et configuration
- `RecentTuneConfig.defaults(lastCandle)` : T tous les `stepMonths` (2) dès `firstT` (2020-11-17) tant que T + 6 mois ≤ dernière bougie ; vue de réglage `tuneMonths` (24) ; horizons 3 et 6 ; `rounds` (2) ; `trialsPerSearch` (1 000) ; `exposureGuardFactor` (1,25) ; `perturbations` (20, ≤ 100 tirages) ; graine `OptimizerDefaults.SEED` ; `minShare` (0,6) ; budget 10 min.
- Graines dérivées par (T, jeu, tour) : reproductible quel que soit le pool.

## Réglage de T (`RecentTune.tune`)
- **Aucune donnée > T** : `truncated(in, T)` reconstruit le dataset sur les bougies ≤ T (`withEndDate(T)`), donc ATH et indicateurs ne voient pas le futur par construction.
- Méthode : jeu Bull seul (Bear en place), puis Bear seul (Bull = résultat), `rounds` tours. Recherche conjointe `SearchEngine` (Sobol 12 dimensions + pseudo-aléatoire à graine fixe au-delà, halving, plateau) ; gel de sensibilité désactivé (seuil 0), 2 points par réglage (ordre d'évaluation seulement).
- Espace (`RecentTuneSpace`, 31 dimensions par jeu = `SensitivityParam.ALL`) : continus pas = plage / 200, entiers pas 1, interrupteurs et modes catégoriels. Inclus : multX2, multTriggered, athBuyMin, athBuyMax, athRefDdBuyPct ; exclus : périodes SMA / ATR, Trend, baseAmount.
- Bornes ATR (`resolve`) : chaque tirage est trié (Down1 ≤ Down2 ; Up1 ≤ Up2 ≤ Up3 ; athBuyMin ≤ athBuyMax) ; Down1 / Up1 / Up2 > 0 autorisés, ce qui active les zones X1 (down1 > 0 ou up1 > 0) et X0,5 (up2 > up1). Candidat resté au départ = valeur exacte du preset (pas d'arrondi de grille).
- Objectif (`RecentTuneObjective`) : score = gain total « gel » du régime (USDC : ventes + position − investi, étape A). Seul garde-fou : exposition moyenne du cycle réel (deux jeux) ≤ `exposureGuardFactor` × celle du preset en place ; c'est un veto (score −1000), jamais une pénalité. Vetos : `INVALID_CANDIDATE`, `NO_ELIGIBLE_WINDOW`, `EXPOSURE_GUARD`. Mémoïsé par clé.
- Un jeu n'est adopté que s'il bat STRICTEMENT le départ dans l'échantillon, sinon il reste inchangé (Δ = 0 au test).

## Test en avançant (`RecentTuneStudy.forward`)
- Rejeu [T, T+h] pour h = 3 et 6 de : preset en place, preset réglé, DCA égal (dichotomie de [`15d`](15d-mesure-exposition-egale.md) calée sur l'exposition du preset réglé), médiane de N perturbations.
- Perturbations (`RecentTune.perturbations`) : mêmes réglages déplacés que le réglage, même amplitude normalisée dans la plage, sens aléatoire (réfléchi au bord), option tirée pour un mode / interrupteur, mêmes tris, garde-fou appliqué sur la vue de réglage (tirage refusé redessiné). Sans réglage retenu : perturbation = preset en place.
- Phase de marché par signe du prix sur [T, T+h]. Rapportés : Δ en points de capital et en % du PnL du preset (si > 0), exposition, drawdown, achats / ventes.

## Verdict (règle fixée avant lecture)
Horizon le plus long : `RÉGLAGE_RÉCENT_PERSISTANT` ssi Δ > 0 sur ≥ `minShare` des T, médiane Δ > 0 et le réglé bat la médiane aléatoire sur ≥ `minShare` des T ; idem à l'horizon court, à part. Sinon `NON_PERSISTANT` avec cause : gain (part ou médiane du Δ), bruit (pas mieux que l'aléatoire), phase (règle tenue dans un seul type de marché). Événements effectifs ≈ nombre de T / 3 à 6 mois (périodes chevauchées).
Compléments sans verdict : réglages qui bougent le plus, zones X1 / X0,5, comparaison avec l'étape A, réglages du dernier T (exports Expert « réglage récent T », jamais appliqués).

## Runner
`./mvnw test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=recent-tune` (script `run-optimizer-manual.bat recent-tune [-Doptimizer.recent.trials=N] [-Doptimizer.recent.confirm=true]`). BTC ; pool dédié ; empreinte des bougies. Estimation (`RecentTuneStudy.estimate`, mesure ms par candidat) écrite dans `target/recent-tune-plan.txt` avant le run ; arrêt si > 10 min sauf `-Doptimizer.recent.confirm=true`. Sorties : `target/recent-tune.json|md`, `target/recent-tune-latest-bull|bear.json`.

## Tests (`RecentTuneTest`, données CSV du repo, sans DB)
Dates ; bougies futures empoisonnées ⇒ même réglage ; reproductibilité (série contre pool) ; ordre des bornes ATR avec zones activables ; preset inchangé = comparateur (Δ = 0) ; garde-fou rejette (facteur 0) ; perturbations dans les bornes et le garde-fou ; règle de verdict ; rapport identique hors durées.

## Limites
BTC seul ; un run, une configuration ; SobolSequence ne fournit que 12 dimensions quasi-aléatoires ; critère « gel » sans plafond de drawdown ni fenêtre multiple ; garde-fou sur la vue de réglage, pas sur le test ; chevauchement des périodes de test.
