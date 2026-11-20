---
statut: courant
sujet: Politique d'exposition par régime contre exposition constante, à plafond de drawdown égal (étape C2) — REGIME_BAND à 3 paramètres, réglage par blocs, règle de verdict, runner exposure-policy
vérifié_le: 2026-11-19
modifié_le: 2026-11-20
empreinte: 06d502d51f
---
# Politique d'exposition vs exposition constante (`ExposurePolicyStudy`, `ExposurePolicyConfig`, `ExposurePolicyReport`)

Vérifié le : 2026-11-19 (`ExposurePolicyStudy`, `ExposurePolicyConfig`, `ExposurePolicyReport`, `SensitivityEvaluator.parity(…, policy)`, test `ExposurePolicyTest`, runner `OptimizerManualRunnerTest`). Socle : [`15d`](15d-mesure-exposition-egale.md) (frontière C1, levier = montant de base), [`15c`](15c-optimisation-par-blocs.md) (blocs, rotations), [`13a`](13a-rejeu-politique-ath-couts.md) (`REGIME_BAND`) ; mesures : [`../etudes/etude-politique-exposition.md`](../archive/etudes/etude-politique-exposition.md).

Dit si piloter l'exposition par régime gagne plus que l'exposition constante maximale **à plafond de drawdown égal**. Hors ligne : aucun réglage du preset modifié, rien appliqué ni persisté, live inchangé, pas de migration.

## Les deux candidats
- **Constante** : multiplicateur du montant de base seul (`SensitivityEvaluator.parity(…, linkBase)`, sans politique), bande de base inchangée.
- **Politique `REGIME_BAND`** (`RegimeBandPolicy`) à 3 paramètres : borne haute Bull, borne haute Bear, multiplicateur du montant de base. Bornes basses = celles des réglages de base de l'évaluateur (bande neutre 0–100 comme C1). Bornes hautes = 100 (= base) ⇒ chiffres exacts de la constante (testé). Rejeu : `parity(sets, full, shift, fixedBase, linkBase, policy)` ; `policy = null` ⇒ aucun changement.

## Réglage (mêmes blocs que B, `TuneBlocks`)
4 blocs de 24 mois, réglage sur 3, test sur le 4ᵉ, 4 rotations, par profil (plafonds lus dans `FrontierConfig.profileCaps()` = `ModulationDefaults`). Le bloc de test n'est lu que par la mesure finale ; `selectPolicy` ne reçoit que les indices de réglage.
- Constante (`constantMultiplier`) : premier franchissement du plafond en montant la grille (grille C1 + `constantExtra`), interpolé en pire drawdown ; statuts `INTERPOLE`, `SOUS_LE_MINIMUM`, `PLAFOND_NON_ATTEINT`. Le test rejoue le bloc au multiplicateur interpolé.
- Politique (`selectPolicy`) : grille `bounds` × `bounds` × `multipliers` (défaut 5 × 5 × 10 = 250) ; meilleur gain médian des blocs de réglage sous « pire drawdown des blocs de réglage ≥ plafond » (premier de la grille à égalité) ; aucune combinaison ne tient le plafond ⇒ la moins profonde, statut `PLAFOND_NON_TENU_REGLAGE`.
- Cache : un rejeu par (combinaison, bloc) ; tables calculées une fois, rotations et profils les lisent. Pool facultatif, résultats consommés dans l'ordre de soumission.
- Historique complet : réglage sur les 4 blocs, rejeu sur l'historique, lecture seule, hors verdict.

## Règle de verdict (`ExposurePolicyStudy.verdict`, fixée avant lecture, `ExposurePolicyConfig`)
`POLITIQUE_UTILE` si, sur le bloc de test, la politique bat la constante en gain dans ≥ `minWins` (3) rotations sur 4, médiane des Δ gain > 0, et plafond au moins aussi bien tenu (aucune rotation où la politique dépasse le plafond alors que la constante non ; blocs de test dépassés rapportés pour chaque candidat). Sinon `NUL` avec la raison (gain, plafond). Stabilité rapportée hors règle : mêmes bornes hautes dans ≥ `minStable` (3) rotations. Moins de 4 rotations ⇒ `NON_EVALUABLE`.

## Rapport
Par actif et profil : une ligne par rotation (réglages retenus, gain, drawdown, plafond tenu, exposition, achats / ventes, statut, Δ), historique complet, verdict, médiane Δ, stabilité ; multiplicité (combinaisons, rotations, profils, sélections, rejeux). JSON identique à l'octet hors durées (`toJsonWithoutTimings`), markdown (`toMarkdown`).

## Lancement
`run-optimizer-manual.bat exposure-policy` (`-Doptimizer.manual=exposure-policy`, base locale copie de prod ; `-Doptimizer.end=`) ; BTC et ETH ; estimation avant run, arrêt au-delà de 10 min sauf `-Doptimizer.policy.confirm=true` ; pool dédié (`-Doptimizer.pool=N`). Sorties `target/exposure-policy.json|md`, `target/exposure-policy-plan.txt`. Contrôle : constante ×1 sur le bloc BTC récent = +2,41 %.

## Tests (`ExposurePolicyTest`, sans base)
Bornes hautes = base ⇒ constante exacte (blocs et historique, ×1 et ×3) ; une borne basse change la mesure ; ×1 = chiffres de B' / C1 ; bloc de test jamais lu par le réglage (lignes de test nulles) et jamais dans les blocs de réglage d'un run ; plafond appliqué aux blocs de réglage seulement ; interpolation de la constante et statuts ; règle de verdict (utile, nul gain, nul plafond, plafond dépassé aussi par la constante) ; stabilité ; plafonds de `ModulationDefaults` et mesure de test = rejeu direct ; reproductibilité série / pool.
