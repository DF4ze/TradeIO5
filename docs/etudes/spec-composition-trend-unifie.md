# Spécification — Fonction de composition régime/régression du Trend unifié (+ protocole de walk-forward)

Statut : walk-forward exécuté le 2026-09-24 (résultats §10), **implémenté le 2026-09-24** (§11) — candidate retenue par Clem : régression à hystérésis (REG_H), à l'échelle swing. §1 à §9 ci-dessous documentent le raisonnement et la proposition de spécification (composition régime/régression) qui a précédé le walk-forward — non implémentée telle quelle, cf. §11 pour les écarts. Rédigée pour le lot [`prompt-implementation-trend-unifie-etape7-specification-composition-walkforward.md`](../prompts/prompt-implementation-trend-unifie-etape7-specification-composition-walkforward.md) ; contexte dans [`etude-indicateur-trend-unifie.md`](etude-indicateur-trend-unifie.md) §8 et §9.

Briques composées : `SWING_STRUCTURE` (régime, `SwingStructureCalculator`), `RegressiveTrendStrategy` (score de régression 3 fenêtres, noté `reg` ∈ [-1,1]), ADX (`adxFactor`, noté `force` ∈ [0,1]).

## 1. Matière empirique : ce qui change la donne

Chiffres de l'Étape 6 (BTC D1, 3 296 bougies, 2017-09-15 → 2026-09-23). Les stats de dynamique ci-dessous ont été recalculées le 2026-09-24 depuis les CSV de ce même run (même source, pas de nouveau calcul d'indicateur).

| Mesure | Valeur |
|---|---|
| Transitions du régime affiché (`BULL_CONFIRMED`/`BEAR_CONFIRMED`/`WARNING_*`) | 788, soit **un changement tous les 4,2 jours** (durée médiane : 2 j en `*_CONFIRMED`, 3 j en `WARNING_*`) |
| Part du temps passée en `WARNING_*` | **54,8 %** |
| Flips du « côté structurel » (dernier régime confirmé, cf. §2) | 293, soit **un tous les 11,2 j** (médiane 8 j) |
| Flips du signe de `reg` | 209, soit **un tous les 15,7 j** (médiane 9 j) |
| Runs `WARNING_*` qui se résolvent dans le sens de la cassure | 293 / 394 (**74 %**) |
| … quand le `reg` **moyen du run** va dans le sens de la cassure | 172 / 203 (85 %) contre 121 / 191 (63 %) sinon — *moyenne calculée sur tout le run, donc avec lookahead : indicatif seulement* |
| … quand `reg` au **1er jour** du `WARNING` va dans le sens de la cassure (`|reg|>0.2`) | 27 / 38 (71 %) contre 266 / 356 (75 %) → **aucune information à l'entrée** |
| Jours avec ADX < 15 / ADX > 25 | 5,4 % / 56,8 % (médiane 26,6) |
| Baseline `TrendConfirmationStrategy` non-`NEUTRAL` (`|score| > 1/6`) | **14 %** des jours (médiane de `|score|` : 0,033) |
| `RegressiveTrendStrategy` non-`NEUTRAL` | 47 % des jours |

Conséquences pour la spec :

1. **La prémisse du §8 (« gate lent / timing rapide ») est inversée dans les faits.** Depuis la réécriture du 2026-09-19 (pivot confirmé à 1 bougie, sans filtre d'amplitude), le régime affiché est la brique la *plus* nerveuse : il bascule 3 à 4 fois plus souvent que le signe de la régression. Un gate construit sur le régime affiché rendrait le composite aussi nerveux que lui.
2. **`WARNING_*` n'est pas un état de transition rare** : c'est l'état majoritaire. Le traiter comme « on attend la confirmation » revient à neutraliser la Trend plus d'un jour sur deux.
3. **Confiance = run-length/30 est structurellement proche de zéro** : avec des runs médians de 2 à 3 jours, la Confiance dépasse rarement 0,1 (0,05 en `WARNING_*`). C'est pour ça que la baseline actuelle sort `NEUTRAL` 86 % du temps (seuil ±1/6 de `MarketOpinionHelper`). À redéfinir, quelle que soit la candidate retenue.
4. **ADX ne peut pas servir de gate binaire au seuil actuel** : ADX ≥ 15 est vrai 94,6 % du temps, donc le gate ne filtre rien. Monter le seuil ajouterait un paramètre sans justification empirique. Et comme la corrélation ADX / `reg` est faible (Pearson 0,15), ADX apporte une information réellement distincte au niveau du score agrégé.

## 2. Primitives communes

Toutes dérivables de l'enum `SwingStructureRegime` existant, sans modifier `SwingStructureCalculator` :

```
structDir(regime) =            // "côté structurel" = sens du dernier régime pleinement confirmé
  +1  si BULL_CONFIRMED ou WARNING_BEAR_BREAK   // on était bull, une jambe casse
  -1  si BEAR_CONFIRMED ou WARNING_BULL_BREAK
   0  si UNDEFINED

breakDir(regime) = WARNING_* ? -structDir : 0   // sens de la cassure en cours
align            = structDir × reg               // ∈ [-1,1], > 0 = la régression confirme le côté structurel
g(x)             = max(0, x)                     // la régression ne donne de la confiance que si elle confirme le côté structurel
force            = adxFactor(ADX, 15, 25)        // inchangé (AdxFactorCalculator)
```

Paramètres introduits : `θ` (seuil de `reg` pour « la régression confirme la cassure ») et `κ` (pénalité `WARNING_*`, figée à 0,5 comme aujourd'hui, pas calibrée). Donc **un seul paramètre libre** (`θ`) au-dessus de ceux de `RegressiveTrendStrategy`.

## 3. Candidates

### C1 — Gate strict sur le régime affiché (le §8 appliqué à la lettre, sert de contrôle)

```
UNDEFINED        : confidence = 0 ; composite = 0
*_CONFIRMED      : dir = structDir ; confidence = g(align)
WARNING_*        : si breakDir × reg ≥ θ -> reversalCandidate = true ; dir = breakDir ; confidence = κ × |reg|
                   sinon                -> dir = 0 (on attend la confirmation) ; confidence = 0
composite        = dir × force × confidence
```

- Pour : fidèle à l'orientation actée, facile à expliquer.
- Contre : hérite de la nervosité du régime affiché (bascule tous les 4,2 j) et reste neutre la majeure partie du temps en `WARNING_*` (55 % des jours). Donne aussi une direction à la cassure dès `θ`, ce qui revient presque à renverser le régime précédent avant qu'il soit confirmé.
- À garder dans le walk-forward comme **contrôle**, pas comme favori.

### C2 — Gate structurel avec hystérésis + régression en timing (**recommandée**)

```
UNDEFINED        : confidence = 0 ; composite = 0              // repli prudent, cf. §5
dir              = structDir                                   // ne change qu'à une confirmation opposée (~11 j)
*_CONFIRMED      : confidence = g(align)
WARNING_*        : si breakDir × reg ≥ θ -> reversalCandidate = true ; confidence = 0
                                            // on cesse de soutenir l'ancien sens, sans l'inverser
                   sinon                -> confidence = κ × g(align)
composite        = dir × force × confidence
```

- **Hiérarchie du §8 respectée** : la structure donne le sens et la régression ne l'inverse jamais. Au pire, elle ramène le composite à 0 et lève `reversalCandidate`.
- **Correctif de la prémisse** : le gate porte sur le côté structurel (déjà suivi en interne par le calculateur via `lastConfirmedBull`) et non sur le régime affiché. Un `WARNING_*` ne fait donc pas basculer le gate, il ne fait que moduler la Confiance. Nervosité du gate : environ 11 j au lieu de 4,2 j.
- **Règle explicite pour `WARNING_*`** (demandée au §3 du prompt) : un `WARNING_*` « écoute » la régression dès que `breakDir × reg ≥ θ`. Il neutralise alors et signale un candidat-retournement, mais ne passe jamais RANGE (garde-fou du §8). Le 1er jour du `WARNING` n'apporte pas d'information (§1), c'est donc la *persistance* de `reg` dans le sens de la cassure qui compte. Variante à tester : exiger la condition sur 2 bougies consécutives (0 paramètre de plus si l'on fige N=2).
- Contre : un vrai retournement ne fait jamais basculer le composite dans le nouveau sens avant la confirmation structurelle (on passe par 0 puis on attend). C'est voulu (« ne renverse jamais »), mais c'est un retard assumé, à mesurer (§4.4).

### C3 — Régression pilote, structure en veto (challenger)

```
UNDEFINED        : composite = reg × κ ; confidence = κ           // la régression seule, pénalisée
*_CONFIRMED      : si sign(reg) == structDir -> composite = reg ; confidence = 1
                   sinon                      -> composite = 0   ; confidence = 0   // veto : jamais contre un régime confirmé
WARNING_*        : composite = reg × κ ; reversalCandidate = (breakDir × reg ≥ θ)
// force N'EST PAS multipliée dans le composite : reg intègre déjà r² (redondance r²(30)/ADX = 0,60)
```

- Pour : signal le moins nerveux (signe de `reg` : ~15,7 j), taux de sortie non-neutre élevé, réactif sur les retournements.
- Contre : **renverse l'orientation du §8**. La direction vient de la régression, la structure n'est qu'un veto. Plus lagué sur les débuts de tendance (lag SMA-like de la fenêtre 30 j, pondérée à 0,5).
- Intérêt principal : dire si la structure apporte quelque chose *au-delà* d'un simple veto. Si C3 ≥ C2 au walk-forward, la hiérarchie du §8 est à revoir.

## 4. Rôle final d'ADX (tranché, avec justification)

**Facteur continu conservé (axe Force), pas de gate binaire.**

- Pas de gate : au seuil actuel (15), il est ouvert 94,6 % du temps. Tout autre seuil serait un paramètre de plus à calibrer, sans signal empirique qui le justifie.
- Pas de double comptage au niveau agrégé (ADX / `reg` : 0,15). La redondance réelle (r²(30) : 0,60) est *interne* au score de régression. La traiter là-bas (le poids long est calibré dans le walk-forward) plutôt qu'en amputant ADX.
- En C3 seulement, `force` n'est pas multipliée dans le composite (elle reste exposée dans `TrendState`), puisque `reg` y porte seul la magnitude.
- **Ablation obligatoire** dans le walk-forward : chaque candidate est aussi évaluée avec `force ≡ 1`. Si l'IC ne baisse pas, ADX sort du composite (mais reste exposé).

## 5. `UNDEFINED`

Jamais observé sur BTC D1 2017-2026 (il n'existe qu'au démarrage d'une série). Défaut prudent, non extrapolé des données : **composite 0, confidence 0, `force` toujours exposée** (en C1/C2). ADX ne devient pas « maître » de la direction, puisqu'il n'en porte pas. C3 est l'exception : la régression y garde la main, pénalisée par κ. Le repli RANGE via ADX (point C de l'étude) reste lié à la décision RANGE (point B), hors de ce lot.

## 6. Contrat de sortie (`TrendState`)

| Champ | Aujourd'hui | Proposé |
|---|---|---|
| `regime` | `SwingStructureRegime` | inchangé (toujours sans RANGE, points B/C ouverts) |
| `force` | `adxFactor` | inchangé |
| `confidence` | run-length/30, ×0,5 en `WARNING_*` | **redéfinie selon la candidate** (C2 : `g(align)`, `κ·g(align)` ou 0). Le run-length/30 est retiré, pour la raison donnée au §1.3 |
| `bosJustOccurred` / `bosTimestamp` | présents | inchangés |
| `reversalCandidate` | — | **ajouté** : BOS anticipé, de la même famille que BOS, pas un 5e axe |
| `regressionScore` | — | **ajouté, brut** : pour un consommateur qui veut le signal continu sans le gate (futur Rainbow, par ex.) |

Le composite reste calculé **côté consommateur**, avec la formule actuelle de `TrendConfirmationStrategy` (`dir × force × confidence`, avec `dir` = `structDir` en C2). Pour C2, cela revient à remplacer `SwingStructureRegimeScore.toScore(regime)` par `structDir` et à changer la Confiance. `TrendState` ne gagne ni score composite ni axe orphelin.

## 7. Protocole de walk-forward — esquisse, à finaliser après le point avec Clem

- **Données** : BTC D1 resamplé depuis H1 (`Bucket`, cf. Étape 5), 2017-08-17 → date du run. Indicateurs calculés de façon causale sur l'historique complet (`computeTimeline`), puis découpés. ADX recalculé sur historique croissant, pas sur la fenêtre de 250 du runner de l'Étape 6.
- **Découpage** : ancré (train expanding depuis 2017-09), **7 folds de test annuels 2020 → 2026 YTD**. Pourquoi ancré : la calibration porte sur 1 à 5 paramètres, et le glissant sacrifierait inutilement 2017-2019.
- **Purge / embargo** : les features sont causales et ne fuient donc pas. La fuite vient des labels (rendement à horizon H). On purge les H derniers jours du train, et on ajoute un embargo de 30 j (plus longue fenêtre `LINEAR_REGRESSION`) après chaque test avant réutilisation.
- **Métrique principale** : IC de Spearman entre le composite et le log-rendement futur à H = 10 j (5 et 20 j en contrôle). Calculé par fold sur des rendements **démoyennés de la dérive du train**, pour neutraliser le biais haussier BTC. Critère de sélection : IC médian des folds + nombre de folds à IC > 0.
- **Métriques secondaires** : hit-rate *équilibré* (moyenne des précisions haussière/baissière) sur les jours non-`NEUTRAL` ; couverture (% non-`NEUTRAL`) ; taux de flip du signal non-neutre ; précision/rappel de `reversalCandidate` (confirmation structurelle dans le sens de la cassure sous N jours, et signe du rendement à H).
- **Baselines** : `TrendConfirmationStrategy` actuelle (obligatoire), `reg` seul, `sign(+1)` constant (buy & hold directionnel).
- **Ordre de calibration** : `RegressiveTrendStrategy` seule d'abord (`slopeScaleFactor` ∈ {25, 50, 100, 200}, 4 à 5 jeux de poids sur le simplexe, alignement on/off), puis θ ∈ {0.1, 0.2, 0.3} pour la composition. Grille volontairement petite (environ 40 + 3 combinaisons).
- **Scénario de non-régression « range étendu + breakout rapide »** : un cas synthétique (60 j de range ±5 %, puis 10 j de breakout +20 %), plus deux épisodes réels à valider sur données : range été 2020 → breakout d'octobre 2020, et range mars → octobre 2024 → breakout de novembre 2024. Critères : composite majoritairement neutre pendant le range, aucune étiquette RANGE sur `WARNING_*` + `reversalCandidate`, `reversalCandidate` levé avant la confirmation structurelle, composite aligné au plus N j après le breakout (N à fixer ensemble).

## 8. Questions ouvertes à trancher avant tout codage

1. **Nervosité de `SWING_STRUCTURE`** : un changement de régime tous les 4,2 j, est-ce acceptable pour une brique « régime de fond » ? C2 contourne le problème via le côté structurel, mais faut-il aussi rouvrir un filtre d'amplitude sur les pivots, sans revenir à l'`atrMultiplier` que tu avais rejeté pour son délai ?
2. **Candidate favorite** : C2 (hiérarchie du §8 corrigée) ? C1 en contrôle, C3 en challenger ?
3. **Métrique de succès prioritaire** : IC de Spearman démoyenné, ou hit-rate équilibré ? Et quel horizon H ?
4. **Contrat `TrendState`** : le figer avant la calibration (ajout de `reversalCandidate` + `regressionScore`, redéfinition de la Confiance), ou après ?
5. **Branchement** : `TrendAnalyzer` appelle `LinearRegressionCalculator` en direct, ou `TrendConfirmationStrategy` passe de 2 à 5 indicateurs d'entrée ?
6. **Confiance run-length/30** : on la retire (proposé), ou on la garde en facteur multiplicatif avec une fenêtre bien plus courte ?
7. **Baseline de prod** : `TrendConfirmationStrategy` sort `NEUTRAL` 86 % du temps. Est-ce connu/voulu, ou faut-il le corriger avant d'attendre le résultat du walk-forward ?

## 9. Écarts pris par rapport au prompt

- Stats de dynamique du régime (§1) recalculées depuis les CSV de l'Étape 6 dans `target/`, que le prompt disait de ne pas relire. Cela reste une lecture de la même source, pas de nouveau calcul d'indicateur. Sans ces chiffres, la prémisse du §8 n'aurait pas pu être challengée.
- Pas d'horizon de rendement forward mesuré : les closes ne figurent pas dans ces CSV, donc aucune performance de candidate n'est affirmée ici.
- Section walk-forward rédigée en esquisse, à finaliser après le point, conformément au séquencement demandé par Clem.

## 10. Résultats du walk-forward (2026-09-24)

### 10.1 Décisions prises avec Clem avant exécution

- C1/C2/C3 évaluées telles que spécifiées, **plus des variantes lissées** (demande de Clem : un signal qui ne change pas trop souvent, en gardant les tests initiaux) : C2L (côté structurel qui ne bascule qu'après 3 jours consécutifs, candidat-retournement exigé 2 jours de suite, EMA 5 du composite), REG_L5/REG_L10 (EMA 5/10 du score de régression), C3L5.
- Métrique laissée au choix de Claude (voir 10.2). Contrat `TrendState` figé **après** les tests.
- Correctif appliqué avant exécution : `g(x) = (1+x)/2` remplacé par `max(0, x)`. La première forme donnait une confiance de 0,5 quand la régression est neutre, donc C1/C2 imposaient une direction presque tous les jours (couverture mesurée : 65 à 87 %), ce qui n'est pas le rôle d'un gate.

### 10.2 Outillage et métrique

- `TrendCompositionFeatureExportManualRunnerTest` (`@Disabled`, DB dev en lecture seule) exporte les briques de production brutes par bougie D1 dans `target/trend-composition-walkforward/features-btc-d1.csv`. ADX est désormais calculé sur l'historique complet croissant : l'écart de méthode de l'Étape 6 est corrigé, avec un écart mesuré sans effet (max 3,5e-5).
- `tools/calibration/trend_composition_walkforward.py` compose les candidates, calibre et évalue sans recompiler (mode itératif). Contre-vérifications : réplique Python de `RegressiveTrendStrategy` à 1e-10 près, réplique Python de `SwingStructureCalculator` à 0 désaccord sur 3 325 bougies (elle sert au scénario synthétique).
- Découpage : 7 années de test (2020 → 2026), train ancré sur tout le passé avec une purge de 10 j. Calibration en deux temps : d'abord la régression seule (grille de 60 points : `slopeScaleFactor` ∈ {25..800}, 5 jeux de poids, alignement on/off), puis θ ∈ {0.1, 0.2, 0.3}.
- **Métrique principale : l'accord équilibré avec la tendance réelle.** La vérité terrain est tracée après coup par un ZigZag à 15 % sur les clôtures ; elle n'est jamais une entrée des candidates. Le score va de −1 à +1 : (jours dans le bon sens − jours à contresens) / jours, moyenné séparément sur les jambes haussières et baissières pour neutraliser le biais haussier de BTC ; un jour neutre compte 0. Pourquoi ce choix plutôt que l'IC rendement futur prévu au §7 : le Trend est un descripteur de l'état en cours (consommateur visé : paramétrage UP/DOWN/RANGE du Rainbow), pas un prédicteur. L'IC à 10 j est conservé en contrôle.
- **Biais connu de cette métrique** : elle récompense la couverture (neutre = 0). La calibration pousse donc `slopeScaleFactor` en butée de grille (800, `tanh` saturée, soit `signe(pente) × r²`). À rediscuter si l'on veut un vrai état RANGE.

### 10.3 Résultats (médiane des 7 années, mode calibré)

| Candidate | Accord | Pire année | Couverture | Changements/an |
|---|---|---|---|---|
| Régression seule | **0,39** | 0,31 | 77 % | 58 |
| Régression lissée EMA 5 (REG_L5) | 0,33 | 0,17 | 73 % | 35 |
| C3 (veto structure) | 0,27 | 0,23 | 56 % | 63 |
| C1 sans ADX | 0,25 | 0,20 | 49 % | 65 |
| C3 lissée (C3L5) | 0,24 | 0,18 | 56 % | 41 |
| C2 sans ADX | 0,22 | 0,13 | 40 % | 52 |
| C1 | 0,21 | 0,16 | 40 % | 51 |
| C2 lissée (C2L) | 0,19 | 0,00 | 30 % | 23 |
| C2 | 0,18 | 0,06 | 32 % | 43 |
| Baseline actuelle (`TrendConfirmationStrategy`) | 0,06 | 0,03 | 14 % | 16 |

Chiffres exacts et détail par année : `target/trend-composition-walkforward/rapport-walkforward.md` et `resultats-folds.csv` (non versionnés, régénérables).

Lecture :

1. **La régression seule domine, et c'est stable** : première chaque année, sans exception (accord de 0,29 à 0,50 selon l'année).
2. **Gater par la structure dégrade toujours le résultat.** Plus la structure a d'autorité, plus l'accord baisse (C3 veto > C1 > C2 > C2L). En régime confirmé contraire, c'est la régression qui avait raison le plus souvent. **La hiérarchie « régime = gate » du §8 n'est pas confirmée par les données.**
3. **ADX n'apporte rien** : chaque variante sans ADX fait au moins aussi bien que sa version avec ADX.
4. **Aucune candidate ne prédit le rendement à 10 j** (IC médian entre −0,09 et +0,07, instable selon l'année). Attendu pour un descripteur de tendance.
5. **Lissage** : l'EMA 5 divise les changements par ~1,7 (58 → 35/an) pour −0,06 d'accord. C2L est la plus calme (23/an), mais au prix d'un retard de 19 à 21 j après les breakouts et d'une pire année à 0,00.
6. **Scénario « range étendu + breakout »** (synthétique + été 2020 + mars-oct. 2024) : la régression (brute ou lissée) capte le breakout en 0 à 3 j, C2L en 21 à 23 j. En revanche, **aucune candidate n'isole proprement un range** : la régression y oscille (45 changements pendant le range 2024 en brut, 26 en lissé), la structure aussi. Les points B/C (RANGE) restent entiers.

### 10.4 Comparaison aux indicateurs de tendance classiques (2026-09-24, demande de Clem)

Ajoutés au script avec leurs réglages standards du marché, sans calibration : Supertrend (ATR 10, ×3), croisements EMA 20/50 et EMA 50/200, signe du MACD (12/26), prix au-dessus ou en dessous de la SMA 200. On y ajoute aussi une variante **REG_H**, la régression avec **hystérésis** (entrée à ±1/6, sortie à 0). Une seconde vérité terrain sert de contrôle : ZigZag 30 % (« cycle »). La première, à 15 %, est l'échelle « swing ».

| Indicateur | Accord swing (15 %) | Accord cycle (30 %) | Changements/an |
|---|---|---|---|
| Régression seule | **0,39** | 0,22 | 58 |
| Régression hystérésis (REG_H) | 0,35 | 0,24 | 39 |
| Régression lissée (REG_L5) | 0,33 | 0,25 | 35 |
| Supertrend 10/3 | 0,28 | 0,34 (pire année 0,14) | **9** |
| MACD 12/26 | 0,20 | 0,32 | 11 |
| EMA 20/50 | 0,05 | **0,47** | 6 |
| Prix / SMA 200 | 0,00 | 0,29 | 8 |
| EMA 50/200 | 0,00 | 0,06 | 2 |

Lecture : **la meilleure brique dépend de l'échelle de tendance visée.** À l'échelle swing (quelques semaines), la régression domine tous les classiques. À l'échelle cycle, les classiques lents reprennent l'avantage (EMA 20/50, Supertrend, MACD). L'accord cycle est fragile : peu de jambes par année, et EMA 20/50 est négatif en 2020 et 2022. Supertrend est le meilleur compromis entre les deux échelles et le plus stable (9 changements/an, jamais négatif au cycle). Pour mémoire, il avait été écarté comme brique *directionnelle prédictive*, pas comme descripteur de tendance. Aucun classique n'est jamais neutre, donc aucun ne détecte les ranges.

Question ouverte qui en découle, à trancher avant le contrat `TrendState` : **quelle échelle de tendance le consommateur principal (paramétrage UP/DOWN/RANGE du Rainbow DCA) doit-il suivre, swing ou cycle ?**

### 10.5 Visualisation

Page interactive (prix BTC coloré par la tendance de chaque candidate, ruban de tendance réelle, ruban `SWING_STRUCTURE`, score) publiée comme artefact claude.ai « Trend BTC unifié ». Données générées par le script Python (`timeline-trend.json`).

### 10.6 Questions ouvertes pour la suite

1. ~~Faut-il abandonner la hiérarchie du §8 et faire de la régression le cœur de l'axe Régime/Force, `SWING_STRUCTURE` ne gardant que BOS et les niveaux S/R ?~~ **Tranché (2026-09-24) : oui, radicalement** — `SWING_STRUCTURE` et ADX sont retirés de `TrendAnalyzer`, pas seulement démis de leur rôle de gate (cf. §11).
2. ~~Quel compromis réactivité / stabilité...~~ **Tranché : hystérésis sur l'état discret** (entrée ±1/6, sortie 0), pas une EMA — cf. §11.
3. ~~ADX : le sortir du composite...~~ **Tranché : oui, retiré entièrement** (plus exposé dans `TrendState.force`, qui devient `|score|`).
4. RANGE : reste sans détection dédiée — l'état RANGE du contrat implémenté (§11) est simplement "hors hystérésis" (score entre les deux seuils), pas une classification distincte calibrée sur une vérité terrain à 3 états. Point non résolu par ce lot, cf. limite documentée en §11.

## 11. Implémenté (Étape 8, 2026-09-24)

Décision de Clem après visualisation des résultats du walk-forward (§10) : la Trend est portée par la candidate **REG_H** (régression à hystérésis, §10.4), à l'échelle swing. Écarts par rapport à la proposition de spécification ci-dessus (§1 à §8), qui restait une proposition non validée :

- **Pas de composition régime/régression** : `SWING_STRUCTURE` et ADX sont retirés de `TrendAnalyzer`/`TrendConfirmationStrategy`, pas seulement démis de leur rôle de gate. Les candidates C1/C2/C3 (§3), la hiérarchie gate/timing du §8 de l'étude et les primitives `structDir`/`breakDir`/`align` (§2) ne sont donc pas implémentées — invalidées par les résultats du walk-forward (§10.3 : tout gate par la structure dégrade l'accord, ADX n'apporte rien).
- **Hystérésis sur l'état discret**, plutôt que les variantes lissées EMA testées en §10.1/10.3 (C2L, REG_L5/L10, C3L5) : entrée ±1/6 (réutilise `MarketOpinionHelper.BARRIER`), sortie à 0. 39 changements/an mesurés en walk-forward (§10.4) contre 58/an pour la régression brute.
- **Réglages de `RegressiveTrendScoreCalculator`** (mutualisés avec `RegressiveTrendStrategy`) : `slopeScaleFactor=400` (le walk-forward de calibration de la régression seule, en amont de ce tableau, a mesuré un plateau de sensibilité entre 200 et l'infini — 400 pris au milieu de ce plateau plutôt qu'en butée de grille, cf. §10.2 "biais connu de cette métrique" qui pousse la grille vers la saturation), poids égaux 1/3 entre les 3 fenêtres, alignement désactivé par défaut (systématiquement battu avec alignement actif dans les variantes testées).
- **Contrat `TrendState` implémenté**, plus simple que la proposition du §6 : `regime` (`TrendRegime` UP/DOWN/RANGE — RANGE remplace `UNDEFINED`/repli, et n'est pas différencié haussier/baissier/volatil, points B/C de l'étude toujours ouverts, cf. point 4 ci-dessus), `score` (∈[-1,1]), `force = |score|`, `confidence = min(1, runLength/10)` (provisoire, non calibrée — le run-length/30 originel du §6 a changé de dénominateur mais reste de la même famille non calibrée), `bosJustOccurred`, `bosTimestamp`. Pas de `reversalCandidate` ni de `regressionScore` séparé (le score exposé EST déjà le score de régression brut, plus besoin de le dupliquer).
- **Limite connue et acceptée, non corrigée dans ce lot** : aucune candidate testée (§10.3, §10.4) n'isole proprement un range étendu — la régression (brute, lissée ou à hystérésis) continue d'osciller pendant un range prolongé. Le scénario de non-régression obligatoire de l'implémentation documente cette instabilité (32 changements de régime sur 90 bougies de range synthétique) plutôt que de la masquer. Détection de range dédiée : hors périmètre, à rouvrir si besoin.
- Code : [`architecture/09-trend.md`](../architecture/09-trend.md).
