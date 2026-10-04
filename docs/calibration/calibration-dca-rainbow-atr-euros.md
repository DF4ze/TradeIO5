# Calibration — DCA Rainbow : bornes ATR, score €, coordinate ascent multi-passes et robustesse

> **Statut 2026-10-04** : premier bench ATR (mono-trend, `RainbowDcaAtrRobustBenchExportTest`), **supplanté** par le moteur pur `service/dca/atr` et le bench [`calibration-rainbow-atr-v3-regime.md`](calibration-rainbow-atr-v3-regime.md) (lui-même non retenu comme source de paramètres).

Suite de `calibration-dca-rainbow-bounds-multipliers.md` (bornes %, score % de PnL) — **ce
document-ci documente le lot ATR + score € + recherche multi-passes** (2026-09-28), dans le même
esprit : protocole + résultats, pas un journal de conception. Le prompt d'implémentation correspondant a été supprimé.

## Objectif

Deux limites du premier bench ATR (`RainbowDcaAtrScoreExportTest`, resultat valide par Clem sur le
visualiseur `dca-rainbow-atr-v2.html`) restaient non resolues :

1. **Optimum local** : son combine glouton en une seule passe ne re-teste jamais un parametre apres
   qu'un autre a change.
2. **Bull only** : aucune fenetre bear/sideways testee — rien ne garantissait qu'un candidat retenu
   sur 2 fenetres bull ne detruise pas de valeur hors tendance haussiere.

`RainbowDcaAtrRobustBenchExportTest` leve les deux : coordinate ascent multi-passes + 4 fenetres
(2 bull + bear + sideways), selection par robustesse plutot que par performance brute.

## Outil

`RainbowDcaAtrRobustBenchExportTest.java` (`src/test/java/.../service/dca/`) — bench manuel
`@Disabled` par defaut, meme convention que les autres benchs DCA (reseau + DB reels, pas de tool
MCP ni d'endpoint REST pour ce lot).

- 13 parametres optimises : `atrPeriod` + 5 `atrMult*` (bornes de zone indexees sur l'ATR plutot
  qu'un %fixe de la SMA) + 7 parametres "mecanisme" deja connus (`buyReentryMode`,
  `sellReentryMode`, `trailingStopPercent` (valeur unique appliquée aux deux côtés : `trailingStopBuyPercent` = `trailingStopSellPercent`), `cooldownDays`, `fixedDelayDays`, `sellFraction`,
  `smaPeriod`).
- 4 fenetres : `MARS25_OCT25`, `AOUT24_OCT25` (bull), `BEAR_2021_2022`, `SIDEWAYS` (dates reprises
  telles quelles de `RainbowDcaBacktestManualRunnerTest`).
- Une seule methode de test : `exportAtrRobustBenchDataset`.

## Comment lancer le bench

1. Dans `RainbowDcaAtrRobustBenchExportTest.java`, commenter l'annotation `@Disabled` sur la classe.
2. Lancer `test:tradeio-5` (build local), ou directement la methode depuis l'IDE.
3. Recommenter `@Disabled` apres coup : ce test reste hors de la suite par defaut (acces reseau
   reel, ~260 backtests par passe × jusqu'a 5 passes ≈ 1300 backtests au total — du meme ordre de
   grandeur que l'ancien bench bornes % (~1700), praticable en quelques minutes avec la
   parallelisation par `ExecutorService` deja en place).
4. Recuperer le resultat sous `target/rainbow-dca-visualizer/atr-score-calibration.json` (non
   versionne, regenere a chaque run, meme fichier que `RainbowDcaAtrScoreExportTest` — attention si
   un resultat precedent doit etre garde, le renommer/copier avant de relancer).
5. Le log console donne directement l'essentiel : `aggregateScore` baseline vs final, nombre de
   passes jusqu'a convergence, score par fenetre baseline vs candidat final.

## Methodologie

- **`aggregateScore` = moyenne ponderee du score € harmonique sur les 4 fenetres** (poids
  `WINDOW_WEIGHT` : 1.0 pour chaque fenetre bull, 0.5 pour bear/sideways) — remplace le score
  mono-fenetre comme critere de selection. Le score par fenetre individuelle (moyenne harmonique
  `realizedGain`/`potentialGain` en euros, cf. javadoc `score()`) reste calcule et exporte en
  detail pour l'audit.
  **Historique** : la 1re version (2026-09-28) prenait le `min` brut sur les 4 fenetres (meme
  logique que le `minDelta` de l'ancien bench bornes %). Premier run reel (2026-09-29) : resultat
  inexploitable — `BEAR_2021_2022` etait a chaque passe le pire cas de tres loin, donc le seul
  levier vu par le coordinate ascent ; il a sacrifie les 2 fenetres bull (score €/PnL% divises par
  ~40-150 entre la passe 1 et le candidat final, cf. Resultats ci-dessous) pour un bear qui restait
  tres negatif de toute facon. Remplace par la moyenne ponderee sur retour de Clem.
- **Coordinate ascent multi-passes** : passe 1 = OAT autour de la baseline V0 (ATR14, mults par
  defaut, `FIXED_DELAY`/`FIXED_DELAY`) sur les 4 fenetres, combine glouton par `aggregateScore`.
  Passe N≥2 repart du candidat de la passe N-1, refait un OAT complet autour de lui. Arret des que
  le candidat combine n'ameliore plus strictement l'`aggregateScore` du point de depart de la passe
  (garde-fou anti-regression, cf. javadoc classe — evite qu'une interaction entre parametres
  degrade le pire cas malgre des gains individuellement positifs), ou apres 5 passes max.
- **Export JSON** : `windows.<label>` garde le format `baseline`/`variations`/`topByScore` de
  l'ancien bench (audit de la PASSE 1 uniquement, sensibilite autour du point de depart) +
  `combinedCandidate` = candidat final du multi-passes evalue sur cette fenetre specifiquement.
  Nœud racine `multiPassSearch.passes[]` (historique complet, une entree par passe) et
  `multiPassSearch.finalCandidate` (parametres, `aggregateScore`, score par fenetre, nombre de
  passes jusqu'a convergence).

## Points de vigilance (repris du prompt d'implementation)

- **Poids `WINDOW_WEIGHT`** (1.0/1.0/0.5/0.5) choisis pour rester lisibles, pas calibres finement —
  a ajuster si le candidat retenu reste trop tire vers un cote (relire `scoreByWindow` par passe
  dans le JSON, ou le dashboard d'analyse, avant de re-régler).
- **5 passes max** et critere d'arret : proposition pragmatique. Si la convergence observee est
  plus lente en pratique, augmenter `MAX_PASSES` ; si elle est immediate, le reduire n'apporte rien.
- **Visualiseur JS non mis a jour** : `dca-rainbow-atr-v2.html` n'a les bougies D1 que depuis
  juillet 2024 — le JSON a 4 fenetres n'y est pas exploitable pour bear 2021-2022/sideways
  2022-2023 sans reembarquer ces bougies (lot separe si besoin).

## Resultats

**Run du 2026-09-29 (critere `min` brut, rejete)** — 5 passes, converge sur
`atrPeriod=7, atrMultDown2=2, atrMultDown1=3, atrMultUp1=0.5, atrMultUp2=1, atrMultUp3=2,
buyReentryMode=FIXED_DELAY, sellReentryMode=TRAILING_STOP, trailingStopPercent=3, cooldownDays=12,
fixedDelayDays=15, sellFraction=0.75, smaPeriod=26`. `aggregateScore` reste negatif (-1087, contre
-7058 en passe 1) : `BEAR_2021_2022` passe de -14885€ a -1087€ (net progres, PnL% -39.5%→-7.8%,
delta vs DCA fixe -1.4%→+30.3%), mais au prix des 2 fenetres bull sacrifiees — `MARS25_OCT25` 542€→
13€, `AOUT24_OCT25` 1972€→13€ (PnL% 21.7%→12.1%, sous-performe desormais le DCA fixe de -10%).
Non retenu : ameliore le bear en detruisant le bull, cf. Methodologie ci-dessus pour le changement
de critere qui en decoule.

_A completer par Clem apres le run avec le nouveau critere (moyenne ponderee) : parametres du
`finalCandidate`, nombre de passes, score par fenetre baseline vs final — avec la meme honnetete
que `calibration-rejection-zone.md` : un reglage qui ne tient que sur les fenetres bull n'est pas
un reglage robuste._
