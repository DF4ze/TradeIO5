---
statut: courant
sujet: Mesure à exposition égale Rainbow (étape B') et frontière exposition → gain / drawdown (étape C1) — DCA fixe calé, bande de bruit, règles de verdict, niveau par profil
vérifié_le: 2026-11-19
modifié_le: 2026-11-20
empreinte: f1fc17f328
---
# Mesure à exposition égale (`service/optimizer/ExposureParity`, `ParityConfig`, `ParityReport`)

Vérifié le : 2026-11-19 (`ExposureParity`, `ParityConfig`, `ParityReport`, `SensitivityEvaluator.parity`, test `ExposureParityTest`, runner `OptimizerManualRunnerTest`). Socle : [`15b`](15b-carte-sensibilite-rainbow.md) (évaluateur, mesure « cycle »), [`15c`](15c-optimisation-par-blocs.md) (blocs) ; mesures : [`../etudes/etude-exposition-egale.md`](../archive/etudes/etude-exposition-egale.md), [`../etudes/etude-frontiere-exposition.md`](../archive/etudes/etude-frontiere-exposition.md) (C1).

Dit si le preset Rainbow en place fait mieux qu'un DCA fixe **à exposition moyenne égale** (le « % du capital total » compte le cash non investi comme une perte). Hors ligne : aucun réglage modifié, rien d'appliqué ni persisté, live inchangé, pas de migration.

## Exposition (définition unique, écrite en tête du rapport)
Valeur de la position / valeur totale du portefeuille, moyenne sur les jours de la plage : celle déjà rapportée pour le preset (`SensitivityEvaluator.cycle`, étape A). Pour le DCA fixe : `(fixedValue − fixedCash) / fixedValue` du même wallet-jour du moteur, même moyenne.

## Rejeu (`SensitivityEvaluator.parity`)
Un rejeu du moteur (`MultiLinkReplayEngine`) donne les deux lignes (preset, DCA fixe) : mêmes coûts simplifiés, modulation neutre, capital commun, même contrainte de cash (DCA tout-ou-rien, jamais au-delà du cash, sans recharge). `fixedBase` = `LinkSignalReplay.Config.fixedBaseAmount` : change le DCA seul, jamais le preset (testé). `null` = DCA habituel (base du lien) : mêmes chiffres que `cycle` (testé). `shiftDays` décale le début de la plage.

## Calibrage (`ExposureParity.calibrate`)
Dichotomie sur le montant de base dans `]0 ; capital]` (l'exposition du DCA croît avec lui) ; cible = exposition moyenne du preset sur la plage. Arrêt dès que l'écart est sous `tolerancePts / 5` (0,1 pt) ou à `maxIterations` (40) ; le meilleur essai est gardé. `Calib.reached` = écart ≤ `tolerancePts` (0,5 pt). Un DCA calé sur sa propre exposition garde son montant (testé).

## Bande de bruit
Le DCA calé relancé avec `shiftCount` (20) décalages de début réguliers `round(i × 60 / 19)` jours (0 et 60 inclus), même fin de plage : la dispersion de ses gains = bruit dû au seul point d'entrée (min, P20, médiane, P80, max ; rapports gain / |drawdown| aussi). Déterministe, aucun tirage.

## Règle de verdict (`ExposureParity.verdict`, fixée avant lecture, `ParityConfig`)
Par bloc rejouable (4 blocs de 24 mois comme l'étape B, historique complet rapporté hors verdict) : réussi si le drawdown du preset n'est pas pire que celui du DCA égal de plus de 5 pts **et** si, sur le gain % du capital OU sur gain / |drawdown|, le preset bat le DCA égal ET dépasse le P80 de la bande (même mesure). `TIMING_UTILE` si ≥ 3 blocs réussis et médiane des Δ gain (preset − DCA égal, % du capital) > 0 ; sinon `NON_DEMONTRE`, avec le côté de l'échec : `gain` (ni gain ni rapport battus), `drawdown`, `bruit` (battu mais dans la bande) — cause la plus fréquente ; première cause testée dans cet ordre pour un bloc.

## Rapport (`ParityReport`)
Par actif, par bloc et historique complet : preset (gain, gain / exposition, exposition, drawdown, gain / |drawdown|, achats / ventes, réalisé, latent = valeur de la position au dernier jour), DCA égal (mêmes colonnes, montant, écart d'exposition, rejeux), bande, DCA habituel et challenger de l'étape B (colonnes de repère, sans conclusion). JSON identique à l'octet hors durées pour mêmes entrées (`toJsonWithoutTimings`), markdown (`toMarkdown`).

## Lancement
`mvn test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=exposure-parity` (`run-optimizer-manual.bat exposure-parity` ; base locale copie de prod, jamais dans la suite normale ; `-Doptimizer.end=AAAA-MM-JJ` fixe J-1). Actifs : BTC et ETH (mêmes `Case` que les autres modes) ; challenger de l'étape B recalculé pour BTC par `BlockTuner` sur les 4 blocs (déterministe), absent pour ETH. Estimation avant run (rejeux au pire × `replayMs`), arrêt au-delà de `budgetMinutes` (5) sauf `-Doptimizer.parity.confirm=true`. Sorties `target/exposure-parity.json|md`, `target/exposure-parity-plan.txt`. Sur BTC dans le runner : le bloc le plus récent doit redonner +2,41 % (preset) et −7,28 % (DCA habituel), sinon échec.

## Tests (`ExposureParityTest`, sans base)
Calibrage ≤ 0,5 pt ; DCA déjà calé = montant inchangé ; montant du DCA sans effet sur le preset et preset / DCA habituel = `cycle` ; décalages réguliers et rapport déterministe ; percentile ; verdict (`TIMING_UTILE`, `NON_DEMONTRE` par bruit / drawdown / médiane).

## Frontière exposition → gain / drawdown (étape C1 : `ExposureFrontier`, `FrontierConfig`, `FrontierReport`)
Dit si l'avantage de timing tient quand l'exposition monte et quelle exposition tient le plafond de drawdown de chaque profil. Hors ligne : rien appliqué ni persisté, preset / Trend / modulation inchangés.

- **Levier unique** : multiplicateur du montant de base du lien (`SensitivityEvaluator.parity(..., fixedBase, linkBase)`, `referenceBase`). ×1 = `linkBase` nul : mêmes chiffres que B' (testé). Grille `FrontierConfig` : ×0,5 / 1 / 1,5 / 2 / 3 / 4 / 6 / 8 / 12 / 16 ; élargissement unique (haut ×24 / 32 / 48 / 64 si un plafond n'est pas encadré et que l'exposition ne sature pas ; bas ×0,25 / 0,125 si le plus bas niveau dépasse un plafond).
- **Par niveau et plage** : mesure de B' (`ExposureParity.calibrate(..., linkBase)` sur le montant du DCA, tolérance 0,5 pt, bande de 20 décalages) ; niveau non conclu si le DCA calé n'atteint pas la tolérance sur un bloc.
- **Règle de niveau** (`ExposureFrontier.passes`, fixée avant lecture) : ≥ `minBlocksPass` (3) blocs où le preset bat le DCA égal en gain, médiane du Δ gain > 0, aucun bloc où son drawdown est pire de plus de `drawdownMargin` (5) pts. La bande de bruit est rapportée, hors règle.
- **Saturation** : niveau saturé si l'exposition moyenne des blocs gagne moins de `saturationElasticity` (0,25) de la croissance proportionnelle au multiplicateur par rapport au niveau précédent. Élasticités gain et drawdown rapportées entre niveaux consécutifs (log / log).
- **Niveau par profil** (`ExposureFrontier.profile`) : plafonds lus dans `ModulationDefaults` (`FrontierConfig.profileCaps`, jamais en dur) ; exposition maximale dont le pire drawdown des blocs respecte le plafond = premier franchissement en montant la grille, interpolé linéairement en pire drawdown ; gain, Δ gain et Δ drawdown de chaque bloc interpolés avec le même poids, règle de niveau appliquée aux valeurs interpolées. Statuts : `INTERPOLE`, `SOUS_LE_MINIMUM`, `PLAFOND_NON_ATTEINT` (valeur du dernier point de grille, sans interpolation). Verdict d'actif : `AVANTAGE_PERSISTANT` si les 4 profils passent.
- **Lancement** : `run-optimizer-manual.bat exposure-frontier` (`-Doptimizer.manual=exposure-frontier`, base locale copie de prod ; `-Doptimizer.end=`) ; estimation avant run, arrêt au-delà de 10 min sauf `-Doptimizer.frontier.confirm=true` ; pool partagé entre les niveaux. Sorties `target/exposure-frontier.json|md`, `target/exposure-frontier-plan.txt` (JSON identique à l'octet hors durées).
- **Tests** (`ExposureFrontierTest`, sans base) : ×1 = chiffres de B' (preset, DCA égal, calibrage, bande, réalisé, latent), exposition croissante, base imposée, plafonds de `ModulationDefaults` et interpolation encadrée, règle de niveau, reproductibilité.
