# Calibration — EtfFlowConfidenceStrategy (BTC et ETH séparément)

Complément à `docs/prompt-calibration-etf-flow.md` et au protocole de référence
`docs/calibration-rejection-zone.md`/`docs/calibration-movement-qualification.md` (même
méthodologie : auto-test fidèle, test statistique vs groupe de contrôle, grille de sensibilité,
verdict explicite). Documente la validation empirique de `EtfFlowConfidenceStrategy` (flux ETF
institutionnel vs mouvement de prix, `StrategyType.CONFIDENCE_MODULATOR` depuis le 2026-07-16,
ad hoc uniquement) sur données réelles, avant tout branchement par défaut dans
`DefaultMarketOpinion`.

**Demande explicite de Clem respectée** : BTC et ETH calibrés et verdictés **séparément** du début
à la fin — aucune statistique, aucun test, aucune grille ne mélange les deux séries.

## Outils

- `tools/calibration/export_etf_flow_history.py` (nouveau) — export MySQL local
  (`etf_flow_snapshot`, colonnes `date`/`total_net_inflow`) vers un CSV par asset.
- `tools/calibration/etf_flow_calibration.py` (nouveau) — réimplémentation fidèle de
  `computeSignal()` (validée par un auto-test qui rejoue les 6 cas de
  `EtfFlowConfidenceStrategyTest.ComputeSignalTest`), classification par jour aligné, correction du
  bug d'unité (voir section dédiée), test statistique vs échantillon aléatoire de taille comparable,
  grille de sensibilité 27 combinaisons.
- `tools/calibration/etf_flow_diagnostic.py` (nouveau, diagnostic ad hoc, pas un livrable de
  calibration au même titre que les deux ci-dessus) — décompose la fréquence marginale de
  `markedPriceMove` et `significantFlow` séparément, utilisé pour investiguer la rareté du cas
  divergent avant la découverte du bug d'unité.
- `tools/calibration/fetch_real_klines.py` — inchangé, réutilisé tel quel pour l'OHLCV D1 (BTCUSDT,
  ETHUSDT).

```
python export_etf_flow_history.py --asset BTC --out etf_flow_btc.csv
python export_etf_flow_history.py --asset ETH --out etf_flow_eth.csv

python fetch_real_klines.py --symbol BTCUSDT --start 2024-01-01 --end 2026-07-17 --interval 1d --out btc_klines_d1.csv
python fetch_real_klines.py --symbol ETHUSDT --start 2024-07-01 --end 2026-07-17 --interval 1d --out eth_klines_d1.csv

python etf_flow_calibration.py --asset BTC --etf-flow etf_flow_btc.csv --klines btc_klines_d1.csv --horizons 3,7,14 --seed 42
python etf_flow_calibration.py --asset ETH --etf-flow etf_flow_eth.csv --klines eth_klines_d1.csv --horizons 3,7,14 --seed 42
```

## Exécution : machine réelle via ssh-gateway, pas le bac à sable Cowork

Même limitation que les runs précédents (`docs/calibration-rejection-zone.md`,
`docs/calibration-movement-qualification.md`) : trafic sortant du bac à sable Cowork restreint
(`api.binance.com` hors allowlist), et ici en plus **pas d'accès à MySQL local** depuis ce bac à
sable. Toutes les commandes de ce run — installation de `mysql-connector-python`, export MySQL,
fetch Binance, calibration — ont été exécutées via `execLocalCommand` (ssh-gateway) sur la machine
Windows de Clem, avec un accès réseau/DB réel, sur autorisation explicite donnée pour la session.

## Découverte majeure : bug de mélange d'unités dans `etf_flow_snapshot.total_net_inflow`

Avant toute analyse statistique, un premier run avec les seuils par défaut a produit un résultat
absurde : **1 seul jour "divergent" sur 629 pour BTC, 0 sur 497 pour ETH** — bien en dessous de tout
seuil exploitable. Un diagnostic marginal (`etf_flow_diagnostic.py`) a montré que `significantFlow`
(`|total| >= 50 000 000`) n'était vrai que sur 3.0% des jours BTC et 1.4% des jours ETH, avec une
**médiane de `|total|` de seulement 210 (BTC) et 56 (ETH)** — trois à quatre ordres de grandeur en
dessous du seuil de 50M attendu.

**Cause racine confirmée en lisant le code** (pas une supposition) : le backfill du 2026-07-17
(`EtfFlowBackfillService`, cf. [[tradeio5_etf_flow_cache_historisation_2026-07-16]]) combine deux
sources dans la **même colonne** `total_net_inflow`, sans les distinguer :

- **Farside** (`FarsideEtfFlowClient.fetchHistory`, historique profond depuis le lancement des ETF)
  exprime ses flux en **millions USD** (`"172.0"` = 172 M$) — `parseFlowNumber` ne fait **aucune**
  conversion, cf. code source.
- **SoSoValue** (`SosoValueEtfFlowClient.fetchHistory`, dernier mois seulement) renvoie
  `total_net_inflow` en **USD brut** (`-55066297.0` = -55,07 M$) — javadoc de classe, section
  explicite "Unité différente de Farside".

`EtfFlowConfidenceStrategy` attend du USD brut partout (ses seuils par défaut, 50M/150M, sont
calibrés dans cette unité). Le backfill exécute Farside **puis** SoSoValue (qui écrase les dates en
commun) — la table contient donc ~18 mois de valeurs en millions (Farside) suivis d'~1 mois de
valeurs en USD brut (SoSoValue), sans aucune colonne pour distinguer la source. Vérifié
empiriquement sur l'export CSV : la bascule est nette et datée au jour près.

| Date | Valeur brute en base | Unité réelle |
|---|---|---|
| 2026-06-16 | `10.2` | Farside, millions ($10.2M) |
| 2026-06-17 | `-82163664.86` | SoSoValue, USD brut (-$82.2M) |

**Conséquence** : comparer une valeur Farside (ex. `210`) au seuil `50 000 000` la classe
systématiquement "non significative", alors qu'en USD brut ($210M) elle dépasserait largement le
seuil de significativité (50M) et même le seuil de saturation (150M). Ce bug ne corrompt **pas**
l'exécution live de la strategy (qui compare toujours une valeur SoSoValue fraîche, en USD brut,
donc cohérente unité pour unité) — il ne corrompt que toute lecture de l'historique complet en base,
donc cette calibration telle quelle, et le futur "indicateur de tendance sur le flux ETF" anticipé
par le javadoc de `EtfFlowSnapshotRepository`.

**Correction appliquée pour cette calibration uniquement** (lecture seule, aucune écriture en
base) : `etf_flow_calibration.py::detect_and_fix_unit_break` détecte la date de bascule (premier
jour où `|total| >= 1 000 000`, seuil qui sépare proprement les deux régimes sur ces données — écart
net entre le dernier point "millions" (~733 au maximum) et le premier point "USD brut" (~14M au
minimum)) et reconvertit x1 000 000 toutes les lignes antérieures. Détectée au **2026-06-17** pour
les deux actifs (609/629 lignes BTC, 477/497 lignes ETH reconverties) — cohérent avec la fenêtre
"~1 mois" documentée pour SoSoValue. Tous les résultats ci-dessous utilisent cette correction ; les
résultats "bruts" (bug non corrigé) sont mentionnés uniquement pour illustrer son ampleur.

**Ce bug doit être corrigé séparément dans le code de production** (normaliser les unités au moment
du backfill/upsert, ou marquer la source par ligne) avant qu'un indicateur ou une strategy ne lise
un jour l'historique complet de cette table — hors scope de ce prompt (qui interdit explicitement de
toucher `EtfFlowConfidenceStrategy.java`, et le bug n'est de toute façon pas dans ce fichier mais
dans `EtfFlowBackfillService`/`FarsideEtfFlowClient`).

## Données utilisées

| Série | BTC | ETH |
|---|---|---|
| ETF_FLOW quotidien (`etf_flow_snapshot`, export MySQL) | 629 jours, 2024-01-11 → 2026-07-16 | 497 jours, 2024-07-23 → 2026-07-16 |
| OHLCV D1 (Binance, `fetch_real_klines.py`) | 929 bougies, 2024-01-01 → 2026-07-17 | 747 bougies, 2024-07-01 → 2026-07-17 |
| Points évaluables (ETF_FLOW publié + klines D1 alignés, lookback=1j) | 629 | 497 |

Nombre de lignes exportées conforme à ce qui était documenté dans le prompt (629 BTC / 497 ETH,
cf. [[tradeio5_etf_flow_cache_historisation_2026-07-16]]) — pas d'écart à signaler.

## 1. Fréquence des cas (question 3 de l'objectif)

| Cas | BTC | ETH |
|---|---|---|
| divergent | 55 / 629 (8.74%) | 39 / 497 (7.85%) |
| coherent | 166 / 629 (26.39%) | 107 / 497 (21.53%) |
| no_signal | 408 / 629 (64.86%) | 351 / 497 (70.62%) |

Une fois le bug d'unité corrigé, la fréquence du cas divergent (~8% des jours pour les deux actifs)
est largement suffisante pour un test statistique — bien au-dessus du seuil de 20-30 occurrences
signalé comme plancher dans le prompt (n=55 BTC, n=39 ETH aux seuils par défaut). La réserve du
prompt sur "ETF_FLOW quotidien, échantillon possiblement trop faible côté ETH" ne s'est donc **pas**
matérialisée ici — la vraie difficulté rencontrée sur ce run a été le bug d'unité, pas la fréquence
de publication quotidienne.

## 2. Test statistique (divergent vs échantillon aléatoire comparable)

Méthodologie (demande explicite de Clem, cf. prompt) : jours classés "divergent" (treatment) vs un
échantillon **aléatoire** de jours de la même série, de taille comparable (`rng.sample`, seed=42) —
pas la population complète des autres jours. Mesure : taux de non-continuation (stagnation ou
retournement du prix sur l'horizon) — valide la prédiction de la strategy ("mouvement non soutenu
institutionnellement" = moins durable).

### BTC (n=55 jours divergents)

| Horizon | Divergent | Contrôle (n=55) | Écart | p-value |
|---|---|---|---|---|
| 3j | 47.3% | 56.4% | -9.1 pts | 0.3400 |
| 7j | 41.8% | 54.5% | -12.7 pts | 0.1816 |
| 14j | 36.4% | 56.4% | **-20.0 pts** | **0.0354** |

### ETH (n=39 jours divergents)

| Horizon | Divergent | Contrôle (n=39) | Écart | p-value |
|---|---|---|---|---|
| 3j | 46.2% | 41.0% | +5.1 pts | 0.6479 |
| 7j | 51.3% | 53.8% | -2.6 pts | 0.8206 |
| 14j | 43.6% | 53.8% | -10.3 pts | 0.3649 |

**Constat déterminant, et dans les deux cas défavorable à la strategy** :

- **ETH** : aucun horizon significatif (p entre 0.36 et 0.82) — le taux de non-continuation des
  jours divergents est indiscernable du hasard.
- **BTC** : un seul horizon sur trois franchit p<0.05 (14j, p=0.035), pas 3j ni 7j — déjà fragile en
  soi (un edge réel devrait se voir sur des horizons voisins, pas apparaître isolément au plus
  long). Mais surtout, **le sens de l'écart contredit l'hypothèse de la strategy** : un taux de
  non-continuation plus BAS chez les jours "divergents" (36.4%) que chez le contrôle (56.4%)
  signifie que les mouvements classés "non soutenus par le flux ETF" continuent en réalité **plus**
  souvent dans leur sens initial que des jours quelconques — l'exact opposé de "mouvement de prix
  non soutenu institutionnellement = moins durable, atténuer la confidence".

## 3. Sensibilité aux paramètres (grille 3³ = 27 combinaisons, horizon=7j)

Grille : `flowSignificanceThresholdUsd ∈ {25M,50M,100M}`, `magnitudeScaleFactor ∈ {2.0,3.0,4.0}`,
`priceMoveThreshold ∈ {0.01,0.02,0.03}`. Rappel : `magnitudeScaleFactor` ne module que la magnitude
du score, jamais la classification divergent/non-divergent — les résultats du test statistique sont
donc rigoureusement identiques pour ses 3 valeurs (documenté dans le script, pas un défaut de la
grille).

| Actif | Combinaisons testables | Taux non-continuation (min/moy/max) | Cellules p<0.05 |
|---|---|---|---|
| BTC | 27/27 | 41.8% / 47.3% / 51.3% (écart-type 3.5%) | 2/27 (7.4%) |
| ETH | 27/27 | 35.3% / 46.8% / 52.6% (écart-type 6.2%) | 1/27 (3.7%) |

**Ni pour BTC ni pour ETH la proportion de cellules significatives ne dépasse ce qu'on attendrait
par pur hasard** (à seuil α=5%, ~5% des 27 cellules, soit ~1.35, franchiraient p<0.05 même en
l'absence de tout edge réel — simple conséquence de tests multiples). BTC (2/27) est même sans
signe cohérent avec un edge stable : les deux cellules significatives (`25M/2.0/0.02` et
`50M/3.0/0.03`) sont toutes deux négatives (même sens "anormal" que noté en §2), mais entourées de
25 cellules non significatives dans les deux sens. ETH (1/27, `100M/3.0/0.02`, n=24 — proche du
plancher de 20-30 occurrences) n'a aucune cellule voisine qui la corrobore. **Aucune des deux
grilles ne montre la stabilité qu'on attendrait d'un edge réel** (cf. critère du prompt : "un
effondrement vers ~50% ou une forte instabilité selon le seuil signale une formule fragile" — les
taux observés oscillent précisément autour de 47-50% sur la quasi-totalité des combinaisons, sans
tendance nette).

## Verdicts (BTC et ETH, indépendants)

### BTC : **pas d'edge robuste, ne pas brancher par défaut**

Le seul résultat statistiquement significatif (horizon 14j, p=0.035) est isolé — non corroboré aux
horizons 3j/7j voisins — et surtout **de signe opposé à l'hypothèse de la strategy** (les jours
"divergents" continuent davantage, pas moins, que le contrôle). Sur la grille de sensibilité,
seulement 2/27 combinaisons (7.4%) franchissent p<0.05, une proportion cohérente avec du bruit de
test multiple plutôt qu'un signal réel, et sans direction cohérente.

### ETH : **pas d'edge robuste, ne pas brancher par défaut**

Aucun horizon significatif aux seuils par défaut (p entre 0.36 et 0.82). Sur la grille, 1/27
combinaison (3.7%) franchit p<0.05, avec un n=24 proche du plancher de significativité fixé par le
prompt — pas de base pour conclure à un edge, même localisé.

### Lecture croisée BTC/ETH

Les deux verdicts convergent (aucun edge robuste), ce qui est en soi une information : contrairement
à `MovementQualificationStrategy` (edge significatif sur BTC, absent sur ETH — signal non
réplicable), ici même le faible signal apparent sur BTC ne va pas dans le sens attendu, ce qui
écarte l'hypothèse d'un vrai edge "présent sur BTC seulement, masqué sur ETH par un historique plus
court" — les deux séries racontent la même histoire : la divergence flux ETF / prix, telle que
définie par les seuils par défaut, ne prédit pas de retournement/stagnation.

**Recommandation** : ne pas brancher `EtfFlowConfidenceStrategy` par défaut dans
`DefaultMarketOpinion` en l'état. Le statut `CONFIDENCE_MODULATOR` limite le risque (jamais de
signal directionnel, atténuation plafonnée), mais un modulateur qui atténue sans edge démontré reste
un bruit ajouté à l'Opinion plutôt qu'une information.

### Pistes concrètes si le sujet est repris (aucune implémentée ici, hors scope de ce lot)

1. **Corriger le bug de mélange d'unités dans `etf_flow_snapshot`** (hors périmètre de ce prompt,
   mais prérequis pour toute future lecture fiable de l'historique complet) — soit en normalisant à
   l'écriture (`EtfFlowBackfillService`/`FarsideEtfFlowClient` multiplient les valeurs Farside par
   1 000 000 avant upsert), soit en ajoutant une colonne source pour permettre une correction a
   posteriori.
2. **Reproduire ce run dans 6-12 mois** avec plus d'historique SoSoValue natif (USD brut sans
   ambiguïté) une fois le cache-aside quotidien ([[tradeio5_etf_flow_cache_historisation_2026-07-16]])
   aura accumulé assez de jours pour ne plus dépendre du tout de Farside/millions.
3. **Tester une définition différente de "divergence"** avant d'abandonner l'idée : le signe observé
   sur BTC (divergence → continuation, pas retournement) suggère que si un signal existe, il serait
   plutôt de sens inverse à celui codé aujourd'hui — hypothèse à vérifier explicitement sur un futur
   run plutôt que supposée ici (corrélation observée sur un seul horizon, pas assez forte pour
   inverser la formule sans nouvelle validation).

## Addendum : seuil de significativité calibré par percentile plutôt que fixe (exploration)

Suite à une remarque de Clem : un seuil fixe en dollars (25M/50M/100M, §3) n'a pas le même sens
relatif pour BTC (médiane du flux quotidien ~198M$) que pour ETH (médiane ~50M$) — le seuil par
défaut de 50M$ filtre presque rien sur BTC (~85% des jours au-dessus) mais coupe la série ETH en
deux à peu près également. `etf_flow_percentiles.py` (nouveau) calcule la distribution empirique de
`|total_net_inflow|` par actif ; `etf_flow_calibration.py --flow-threshold-percentile <pct>`
substitue le seuil fixe par le percentile réel de cet actif.

Testé au p67 ("haut" tercile) : seuil BTC=300,5M$, ETH=80,8M$ (au lieu de 50M$ pour les deux).
Résultat sur le test principal (toujours aucun horizon significatif, n=12 BTC et n=25 ETH — sous ou
à la limite du plancher de 20-30 occurrences) et sur une grille p50/p67/p75/p90 (agrégée sur 9
combinaisons scale×price_move, horizon=7j) :

| Percentile | BTC écart moyen vs contrôle | BTC n divergent moyen | ETH écart moyen vs contrôle | ETH n divergent moyen |
|---|---|---|---|---|
| p50 | -3.9 pts | 35 | -5.0 pts | 41 |
| p67 | +9.9 pts | 14 | -9.6 pts | 26 |
| p75 | +28.0 pts | 9 | -16.9 pts | 25 |
| p90 | +32.2 pts | 3 | -23.0 pts | 14 |

**Ne change pas le verdict, mais révèle un compromis méthodologique** : en montant le seuil de
significativité, BTC dérive vers le sens attendu par l'hypothèse (écart de plus en plus positif)
tandis qu'ETH dérive dans le sens opposé (écart de plus en plus négatif) — deux actifs qui
divergent, et dont l'échantillon "divergent" s'effondre en parallèle (jusqu'à n=1-9 à p90, bien trop
petit pour distinguer un vrai signal du bruit). Le calibrage par percentile résout proprement le
problème de comparabilité BTC/ETH pointé par Clem, mais ne produit pas, sur cette fenêtre de
données, un seuil qui ferait émerger un edge robuste — la conclusion du verdict principal reste
inchangée.

## Reproductibilité

Fichiers CSV bruts (`etf_flow_btc.csv`, `etf_flow_eth.csv`, `btc_klines_d1.csv`,
`eth_klines_d1.csv`, tous dans `tools/calibration/`) régénérables via les commandes ci-dessus, non
versionnés (même convention que les runs précédents). Le point de bascule d'unité (2026-06-17 sur ce
run) glissera avec le temps au fur et à mesure que SoSoValue natif couvre une fenêtre plus large —
`detect_and_fix_unit_break` le redétecte automatiquement à chaque exécution, pas besoin de le
recalculer à la main.
