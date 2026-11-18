---
statut: courant
sujet: Carte de sensibilité Rainbow (étape A) — balayage d'un réglage à la fois, Bull seul / Bear seul, PnL réel et lecture gel, rapport JSON + md
vérifié_le: 2026-11-18
modifié_le: 2026-11-18
empreinte: 3f1ec08f33
---
# Carte de sensibilité Rainbow (`service/optimizer/Sensitivity*`)

Vérifié le : 2026-11-18 (`SensitivityParam`, `SensitivityEvaluator`, `SensitivityMap`, `SensitivityReport`, test `SensitivityMapTest`, runner `OptimizerManualRunnerTest`). Socle : [`15-optimiseur.md`](15-optimiseur.md) ; position de Clem : [`../decisions/fil-rouge-dca-automatisation.md`](../decisions/fil-rouge-dca-automatisation.md) (« Pivot Rainbow d'abord ») ; mesures : [`../etudes/etude-sensibilite-rainbow.md`](../etudes/etude-sensibilite-rainbow.md).

Dit quels réglages Rainbow changent le PnL réel d'un actif, dans quel sens, séparément pour Bull et pour Bear. Hors ligne : rien n'est appliqué ni persisté (aucune migration), live inchangé, ni veto, ni walk-forward, ni combinaison.

## Principe
Pour chaque jeu (Bull puis Bear) et chaque réglage de l'interface Expert, les autres réglages restant ceux du preset en place (l'autre jeu intact) : une variante par valeur, mesurée deux fois. Chaque rapport compare trois lignes côte à côte : preset en place (comparateur principal), DCA fixe (même cash) et la variante.

## Réglages (`SensitivityParam.ALL`)
31 réglages, ceux de `X.RAINBOW_GROUPS` hors gelés (périodes SMA / ATR, `baseAmount`, fenêtre d'analyse, Trend) : bornes ATR (×5), modes de ré-entrée, stops suiveurs achat / vente, `cooldownDays`, `fixedDelayDays`, `sellFraction`, interrupteurs (`allowSellDuringCooldown`, `cooldownAfterSellOn`, `blockBuyAfterSellUntilDown2`, `athOn`, `moonOn`, `moonReserveRatchet`), modulation ATH (références de drawdown, facteurs), multiplicateurs de zones, Moon (réserve, stops).
- Numérique : `GRID` = 9 valeurs = 8 points réguliers de min à max + la valeur en place (dédoublonnée, entiers arrondis). Interrupteur / mode : une variante par option.
- Domaine = règles de `ExpertPresetValidator` (la seule source de validité, appelée avant tout rejeu : une variante invalide, ex. ordre des bornes ATR, `athBuyMax ≥ athBuyMin`, est ignorée, listée avec son motif et comptée). L'interface ne borne pas le haut des facteurs ATH (plafond de balayage 5), des multiplicateurs de zones (6) ni des bornes ATR (20).
- `RainbowAtrGlobals.Builder` accepte `multX2 / multX1 / multX0_5 / multTriggered` (jusque-là absents).

## Évaluateur léger (`SensitivityEvaluator`)
Pur, sans I/O, sans veto, sans fenêtres multiples ni pénalité de dispersion : `ReplayScorer` n'est donc pas utilisé (seul `requiredPoints` est réutilisé). Vue de test = 24 mois glissants finissant à J-1 (`endDate`) ; l'historique complet n'est rejoué qu'en lecture seule pour les 5 réglages les plus influents de chaque jeu.
- **Lecture principale « cycle »** : `LinkSignalReplay.Config.withSets` + `MultiLinkReplayEngine` (un lien, capital fixe sans apport, coûts simplifiés, modulation neutre, pas de plafond ATH). Les DEUX jeux vivent (Trend → Bull / Bear). PnL réel = valeur finale du portefeuille − capital : un gain de bull vendu ou repris par le bear suivant y est compté. Rapporté aussi : ventes encaissées (net de frais), valeur du portefeuille et position (latent) au dernier jour de la dernière phase Bull terminée par un Bear, drawdown, exposition moyenne, achats / ventes, DCA fixe de référence du moteur.
- **Lecture « gel »** (comme l'Expert, type `RAINBOW`) : `RainbowAtrReplay.run` sur le seul jeu testé, barres hors de son régime inactives (`RainbowAtrReplay.inactive` : aucune entrée, DCA fixe arrêté, prix de valorisation figé), régimes par `SimulationRegimes.resolve` (rendu public pour cela). Gain total = ventes + position − investi ; DCA fixe mis à budget égal (gain × investi / investi du DCA fixe), comme le front. L'automate Moon est préchauffé avec le seul jeu testé (comportement de l'Expert).
- Compte chaque rejeu (`replays()`).

## Orchestrateur et rapport (`SensitivityMap`, `SensitivityReport`)
`SensitivityMap.run(inputs, months[, params])` : comparateurs, puis par jeu et par réglage la courbe complète (valeur → lecture cycle + gel), résumé par réglage (meilleure / pire valeur, écart max − min, meilleure − preset en place, sens monter / baisser / « → option » / « en place », bat le preset ?, bat le DCA ?, meilleure valeur et écart en gel), classement décroissant de l'écart. Déterministe (aucun tirage).
`SensitivityReport` : JSON (`toJson`, `toJsonWithoutTimings` : mêmes entrées + même empreinte de bougies ⇒ identique à l'octet hors `timings`), `toMarkdown` (comparateurs, un tableau classé par jeu, courbes des 5 premiers, combinaisons ignorées, multiplicité : paramètres, valeurs testées, rejeux par lecture, combinaisons ignorées).

## Lancement
Runner manuel `mvn test -Dtest=OptimizerManualRunnerTest -Doptimizer.manual=sensitivity` (`run-optimizer-manual.bat sensitivity` ; base locale copie de prod, jamais dans la suite normale ; `-Doptimizer.end=AAAA-MM-JJ` fixe J-1 pour reproduire un run). Sorties `target/sensitivity-rainbow.json|md`. Coût mesuré BTC : ~35 ms par rejeu en moyenne, 31 s pour 881 rejeux.
