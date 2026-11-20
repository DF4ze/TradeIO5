---
statut: courant
sujet: Mise en production du calage d'exposition (étape C4) — niveau en taux de base, invariance au capital, bandes du live et onboarding, wallet BTC + ETH à cash commun, facteur de réduction f, runner exposure-production
vérifié_le: 2026-11-19
modifié_le: 2026-11-20
empreinte: f2946d9bab
---
# Mise en production du calage d'exposition (`ExposureProductionStudy`, `ExposureProductionConfig`, `ExposureProductionReport`, `WalletEvaluator`)

Outil de mesure (hors prod, rien appliqué ni persisté). Il rend exploitable le calage de [C3](15g-calage-exposition-pire-cas.md) : aucun nouveau levier, le niveau de chaque profil (multiplicateur du montant de base, calé au plafond / k) est exprimé en taux de base puis rejoué dans des conditions proches du live. Constats chiffrés : [`../etudes/etude-mise-en-production-exposition.md`](../archive/etudes/etude-mise-en-production-exposition.md).

## Niveau, taux, autonomie
- Niveau k = `ExposureProductionConfig.k` (1,1 ; k = 1 en repère) : premier franchissement du plafond / k sur le pire drawdown par niveau, recalculé avec les fonctions de C3 (`ExposureCalibrationStudy.windows`, `worstByLevel`, `pick`) ; mêmes 78 fenêtres de 24 mois + historique complet.
- Taux de base = base par jour / cash = multiplicateur × base du ×1 (1,00 USDC) / capital rejoué. Autonomie équivalente = 1 / (taux × multiplicateur moyen des zones) ; comparée aux bornes de `ModulationDefaults.AUTONOMY_DAYS`.

## Mesures (même pire cas partout)
- `Rig` : fenêtres + historique complet + 4 blocs de C1 d'une configuration ; `measure` = pire drawdown (fenêtres + historique), drawdown de l'historique, exposition et gain médian des blocs. Un actif : `SensitivityEvaluator.parity` (rejeu de C3) ; wallet : `WalletEvaluator` (même moteur `MultiLinkReplayEngine`, plusieurs liens, une base par lien, départ commun au plus tardif des premiers jours rejouables).
- **Invariance** : même taux rejoué aux capitaux de C3, ≈ 1 000 et ≈ 10 000 (`ScorerInputs.withCash0`, base = taux × capital). OK ssi écart du pire drawdown ≤ 1 pt et du gain médian ≤ 2 pts (`ddTolerancePts`, `gainTolerancePts`).
- **Configurations** : A = base recommandée, bande neutre (= C3) ; B = A + bande du `StartingProfile` (min / max, puissance ×1, biais 0, marge admin) injectée par `ScorerInputs.withBaseSettings` ; C = proposition d'onboarding (`RainbowLiveOnboardingService.referenceBaseAmount(cash, autonomyDays, mean)`) + bande du profil. La bande « compte » si B s'écarte de A d'au moins 1 pt de drawdown ou 2 pts de gain.
- **Wallet** : capital = somme des capitaux de C3, part `walletShare` (0,5) par lien, base du lien = f × taux de son actif × cash × part ; bande neutre puis bande du profil. Conforme si le pire drawdown ≥ plafond du profil ; sinon `searchF` : f = 1 testé d'abord, puis dichotomie à une dimension (`fIterations` = 8, résolution 1/256), le plus grand f conforme mesuré ; f = 0 si aucun.

## Runner et sorties
- `./mvnw test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=exposure-production` (BTC, ETH ; pool dédié `POOL` ; empreinte des bougies ; script `run-optimizer-manual.bat exposure-production`). Estimation (`ExposureProductionStudy.estimate`) écrite dans `target/exposure-production-plan.txt` avant le run ; arrêt si > 10 min sauf `-Doptimizer.calibration.confirm=true`.
- Sorties : `target/exposure-production.json|md` (invariance, configurations A / B / C, wallet, table de calage par profil, multiplicité, limites). Le runner compare les niveaux k = 1 à `target/exposure-calibration.json` s'il existe.

## Tests (`ExposureProductionTest`, configuration réduite, données CSV du repo)
Taux de base × capital = base du niveau et la configuration A redonne C3 (pire drawdown par `constantLine` sur chaque fenêtre et historique, gain médian des blocs) ; wallet à un lien = évaluateur d'un actif ; f rend le wallet conforme (`holds` ou f = 0, bases = f × taux × cash × part) et dichotomie monotone et serrée (f = 1 sans recherche si déjà conforme) ; règle d'invariance appliquée telle quelle ; reproductibilité (série contre pool, rapport sans durées identique).

## Limites
Un seul grand bear par actif (2021-22) ; plafond = cible calibrée sur l'historique, pas garantie ; coûts simplifiés (aucun minimum d'ordre ni arrondi d'exchange : l'invariance est exacte dans le modèle, pas prouvée pour le live) ; wallet BTC + ETH 50 / 50 seulement (PAXG exclu) ; f ≤ 1 (marge d'un wallet diversifié non exploitée) ; niveau calé en bande neutre, puissance ×1, biais 0.
