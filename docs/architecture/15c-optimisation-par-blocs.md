---
statut: courant
sujet: Optimisation par blocs Rainbow (étape B) — descente de coordonnée Bull / Bear validée hors échantillon, plateau, garde-fou d'exposition, rotations, challenger exporté
vérifié_le: 2026-11-18
modifié_le: 2026-11-20
empreinte: 4268c7ead3
---
# Optimisation par blocs Rainbow (`service/optimizer/Block*`, `Tune*`)

Vérifié le : 2026-11-18 (`TuneConfig`, `TuneBlocks`, `TunePlateau`, `BlockTuner`, `BlockTuning`, `TuneReport`, `SensitivityEvaluator`, `SensitivityParam`, test `BlockTuningTest`, runner `OptimizerManualRunnerTest`). Socle : [`15b-carte-sensibilite-rainbow.md`](15b-carte-sensibilite-rainbow.md) (évaluateur, catalogue de réglages) et [`15-optimiseur.md`](15-optimiseur.md) ; position de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Étape B, protocole ») ; mesures : [`../etudes/etude-optimisation-par-blocs.md`](../archive/etudes/etude-optimisation-par-blocs.md).

Cherche, pour un actif, des réglages de VENTE Bull et Bear qui battent le preset en place et le DCA fixe **hors de la période où ils ont été réglés**. Hors ligne : rien n'est appliqué ni persisté (aucune migration), live inchangé. Un seul run, une seule configuration : jamais de relance de variantes pour « faire apparaître » un gain.

## Blocs et rotations
- `TuneBlocks.build(lastEnd, 24, 4)` : 4 blocs de 24 mois contigus et non chevauchants (début = lendemain de la fin du précédent), le plus récent finissant à `lastEnd` (J-1). `SensitivityEvaluator(in, months, from)` impose le premier jour de la vue.
- `BlockTuning.plan` rejoue le preset en place sur chaque bloc : un bloc sans assez de bougies de préchauffage (`cycle` nul) est écarté et signalé (`BlockInfo.valid = false`). Moins de 3 blocs valides : arrêt ; moins de 4 rotations : verdict `NON_EVALUABLE`.
- 4 rotations : réglage sur 3 blocs, test sur le 4ᵉ, jamais l'inverse (le tuner ne reçoit que les évaluateurs de réglage, le bloc de test n'est rejoué qu'après la descente). Puis un challenger unique réglé sur tous les blocs valides, affiché comme non testé.
- Mesure par bloc (`SensitivityEvaluator.cycle`, identique à l'étape A) : PnL réel du cycle, les deux jeux actifs, en % du capital ; rapportés aussi réalisé, latent, drawdown, exposition moyenne, achats / ventes. Comparateurs côte à côte : preset en place, DCA fixe.

## Descente de coordonnée (`BlockTuner`)
Ordre : Bull puis Bear, `maxPasses` passes (3), arrêt dès qu'une passe ne retient rien. Réglages balayés (`BULL_KEYS`, `BEAR_KEYS`) : vente seulement ; `OUT_OF_SCOPE` (`multX2`, `multTriggered`, `athBuyMin`, `athBuyMax` : taille d'achat = exposition = L2) et `SILENT` (`atrMultDown1 / Up1 / Up2`, `multX1`, `multX0_5`, `fixedDelayDays`, Moon Bear) ne sont jamais touchés.
Par réglage :
1. 9 valeurs sur la plage de l'étape A (`SensitivityParam.values`, valeur en place incluse) ; interrupteurs : les deux options.
2. Meilleur contre le plafond de balayage que NOUS avons choisi (`SensitivityParam.sweepCapped` : facteurs ATH, bornes ATR, multiplicateurs ; l'Expert ne les borne pas) : borne haute doublée (`withMax`) jusqu'à `widenSteps` / `widenCeiling`, puis conclusion. Un bord qui reste (min, max, domaine ou plafond non levé) est signalé dans la décision.
3. Affinage : `refinePoints` valeurs entre les deux voisins du meilleur.
4. Valeur retenue = plateau (`TunePlateau`) : plage contiguë autour du meilleur dont le score est ≥ 90 % du meilleur (seuil = meilleur − 10 % de |meilleur|), moyenne de ses valeurs (arrondie pour un entier), jamais le pic. Re-mesurée avant d'être retenue.
5. Conservation : gain moyen sur les blocs de réglage ≥ `minGainPoints` (0,25 pt de capital) **et** progression sur la majorité de ces blocs **et** EXPOSURE_GUARD respecté sur chacun. Sinon le réglage reste celui du preset en place (motif journalisé).
Score d'un candidat = PnL % moyen sur les blocs de réglage ; un bloc rejeté par le garde-fou ou non rejouable compte comme le réglage courant.

**EXPOSURE_GUARD** : exposition moyenne du candidat > `exposureGuardFactor` (1,25) × celle du preset en place sur le même bloc ⇒ rejeté pour ce bloc (`BlockTuner.rejects`). Les gains venus d'une exposition plus haute relèvent du L2.
Variante hors domaine (`ExpertPresetValidator`) : ignorée et comptée. Pool : un `ExecutorService` fourni (dédié) ; les rejeux sont consommés dans l'ordre de soumission, donc le résultat ne dépend pas du pool.

## Verdict et rapport (`BlockTuning`, `TuneReport`)
`ADOPTABLE` seulement si, sur le bloc de test, le challenger bat le preset en place **et** le DCA fixe dans ≥ 3 rotations sur 4, avec des médianes de Δ (vs preset, vs DCA) > 0 et l'exposition dans la borne sur tous les blocs de test ; sinon `NUL`. Le rapport porte : blocs, par rotation (réglages modifiés avant → après, bloc de test, descente complète), agrégat, réglages qui s'inversent (`Inversion`), challenger (réglages, mesure dans l'échantillon, historique complet en lecture seule), multiplicité (réglages, rotations, passes, valeurs testées, rejeux, ignorées, rejets du garde-fou), estimation de coût, notes d'écarts. Mêmes entrées + même empreinte de bougies ⇒ JSON identique à l'octet hors `timings`.
Export du challenger au format de l'Expert (`ExpertDtos.RainbowPresetRequest` : nom, provider, fenêtre d'analyse, `tuning`, `globals`) : un fichier par jeu (Bull = Rainbow UP, Bear = Rainbow DOWN), validé par `ExpertPresetValidator` en test.

## Configuration (`TuneConfig`)
Tout seuil de décision est ici : `blockMonths` 24, `blockCount` 4, `maxPasses` 3, `minGainPoints` 0,25, `plateauRatio` 0,9, `exposureGuardFactor` 1,25, `refinePoints` 9, `widenCeiling` 100, `maxWidenSteps` 3, `replayMs` 25, `budgetMinutes` 10.

## Lancement
`mvn test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=tune` (`run-optimizer-manual.bat tune` ; base locale copie de prod, jamais dans la suite normale ; `-Doptimizer.end=AAAA-MM-JJ` fixe J-1 ; `-Doptimizer.pool=N` taille du pool ; `-Doptimizer.tune.mingain=x` seuil de gain). Avant tout réglage, le runner estime les rejeux au pire × `replayMs` (`target/tune-plan.txt`) et **s'arrête** au-delà de `budgetMinutes` sauf `-Doptimizer.tune.confirm=true`. Sorties : `target/tune-blocks.json|md`, `target/tune-challenger-bull.json`, `target/tune-challenger-bear.json`. Coût mesuré BTC : estimation pire cas 23 686 rejeux (9,9 min en série), réel 12 549 rejeux en 46 s (pool de 4).

## Tests (`BlockTuningTest`, sans base)
Blocs non chevauchants ; plateau choisi et non le pic (plateau asymétrique, trou, meilleur négatif) ; garde-fou (seuil, rejet dans la descente avec facteur 0) ; jeu inchangé = preset en place à l'identique (aucun réglage balayé, ou seuil de gain inatteignable) ; bloc de test jamais rejoué pendant le réglage ni listé parmi les blocs de réglage ; reproductibilité (série, série, pool) et export valide ; estimation ; verdict (ADOPTABLE, NUL, `NON_EVALUABLE`) et inversions sur rotations synthétiques.
