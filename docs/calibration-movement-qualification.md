# Calibration — MovementQualificationStrategy

Complément à `docs/prompt-calibration-movement-qualification.md` et au protocole de référence
`docs/calibration-rejection-zone.md` (même méthodologie : calibration visuelle/fréquence, test
statistique vs groupe de contrôle, sensibilité aux paramètres). Documente la validation empirique
de `MovementQualificationStrategy` (OI + Funding Rate + OBV, `StrategyType.CONFIDENCE_MODULATOR`
depuis le 2026-07-15) sur données réelles, avant tout branchement en production (scheduler).

## Outils

- `tools/calibration/fetch_coinalyze_history.py` — résolution dynamique du symbole Coinalyze
  (miroir de `CoinalyzeSymbolResolver.java`), fetch paginé `/open-interest-history` et
  `/funding-rate-history` (ce dernier appelé en HTTP direct, pas encore wrappé côté
  `CoinalyzeClient.java`).
- `tools/calibration/movement_qualification_calibration.py` — réimplémentation fidèle de
  `computeSignal()` (validée par un auto-test qui rejoue les 4 cas de
  `MovementQualificationStrategyTest.java`), classification par point H1, test statistique par cas
  vs groupe de contrôle, grille de sensibilité 81 combinaisons, diagnostic optionnel OBV
  rate-of-change (`--obv-roc-diagnostic`).
- `tools/calibration/fetch_real_klines.py` — inchangé, réutilisé tel quel pour l'OHLCV (BTCUSDT,
  ETHUSDT, H1).

```
python3 fetch_coinalyze_history.py --symbol BTCUSDT --start <date> --end <date> \
    --api-key <clé Coinalyze> --out-oi oi_btc.csv --out-funding funding_btc.csv

python3 movement_qualification_calibration.py \
    --klines-btc btc_klines.csv --oi-btc oi_btc.csv --funding-btc funding_btc.csv \
    --klines-eth eth_klines.csv --oi-eth oi_eth.csv --funding-eth funding_eth.csv \
    --obv-roc-diagnostic 3
```

## Exécution : machine réelle via ssh-gateway, pas le bac à sable Cowork

Même limitation que documentée dans `docs/calibration-rejection-zone.md` (trafic sortant du bac à
sable restreint, `api.binance.com`/`coinalyze.net` hors allowlist). Toutes les commandes de ce run
ont été exécutées via `execLocalCommand` (ssh-gateway) sur la machine Windows de Clem, avec un
accès réseau réel, sur autorisation explicite donnée pour la session.

## Point d'attention majeur : profondeur d'historique Coinalyze réellement disponible

Le prompt visait 12-18 mois d'historique. **En pratique, la clé Coinalyze utilisée (compte
gratuit, `tradeio.coinalyze.apiKey`) ne retourne des données que sur une fenêtre glissante d'environ
3 mois** — vérifié empiriquement par bissection sur `/open-interest-history` (BTCUSDT) :

| Date testée | Résultat |
|---|---|
| 2019-09-01, 2022-01-01, 2024-01-01, 2025-01-01, 2025-07-01, 2026-01-01, 2026-04-01, 2026-04-15 | Réponse vide (`[]`) |
| 2026-04-24 | 73 points retournés |
| 2026-05-01, 2026-06-01, 2026-07-10 | Données présentes |

La coupure se situe donc entre le 2026-04-19 et le 2026-04-24 (limite de rétention du plan
gratuit, pas une panne ponctuelle — testé à plusieurs dates avant et après, résultat stable). **Ce
n'est pas la même limitation que celle rencontrée sur `RejectionZoneIndicator`** (accès réseau
sortant absent du bac à sable, contournée en changeant de machine) : ici, même avec un accès réseau
réel et une clé API valide, la donnée n'existe simplement pas plus loin dans le passé sur ce plan.
Fenêtre finalement retenue : **2026-04-25 → 2026-07-16** (~83 jours), avec l'OHLCV fetché depuis le
2026-04-20 pour couvrir le warmup (OBV period=14, price lookback=10 bougies).

**Conséquence directe à ne pas minimiser** : la profondeur réelle (~3 mois, un seul régime de
marché) est très inférieure aux ~2.5 ans multi-cycles utilisés pour calibrer `RejectionZoneIndicator`
sur données réelles. Les résultats ci-dessous valident (ou invalident) la formule sur cette fenêtre
précise, pas sur un échantillon de cycles de marché variés — une extension nécessiterait soit un
plan Coinalyze payant, soit une autre source d'historique OI/funding.

## Données utilisées

| Série | BTCUSDT | ETHUSDT |
|---|---|---|
| OHLCV H1 (Binance, `fetch_real_klines.py`) | 2089 bougies, 2026-04-20 → 2026-07-16 | 2089 bougies, 2026-04-20 → 2026-07-16 |
| Open Interest H1 (Coinalyze `/open-interest-history`) | 1969 points, 2026-04-25 → 2026-07-16 | 1969 points, 2026-04-25 → 2026-07-16 |
| Funding Rate H1 (Coinalyze `/funding-rate-history`) | 1969 points, 2026-04-25 → 2026-07-16 | 1969 points, 2026-04-25 → 2026-07-16 |
| Points évaluables (klines + OI[t] + OI[t-1h] + funding[t] alignés) | 1968 | 1968 |

Résolution symbole Coinalyze (dynamique, comme en production) : `BTCUSDT` → `BTCUSDT_PERP.A`,
`ETHUSDT` → `ETHUSDT_PERP.A`, exchange code `A` (Binance) — un seul marché correspondant par
symbole, pas d'ambiguïté à arbitrer.

**Forme de réponse confirmée contre un appel réel** (le doute documenté dans
`OpenInterestHistoryResponse.java`, "forme non vérifiée", est levé) : `/open-interest-history` et
`/funding-rate-history` renvoient bien `[{"symbol": ..., "history": [{"t","o","h","l","c"}, ...]}]`
— la convention "candle" déduite par analogie était correcte. Le funding rate `c` d'un point
d'historique est bien dans la même unité que la valeur ponctuelle retournée par `/funding-rate`
(`FundingRateResponse.Entry.value`, fraction brute, pas de pourcentage ni de conversion) — vérifié
en comparant l'ordre de grandeur des deux endpoints sur la même période.

Paramètre `obvPeriod` retenu : **14** (aligné sur `MovementQualificationParam.defaults(TF, 14.0)`
utilisé par `MarketOpinionParametersFactoryMovementQualificationTest.java` — la seule valeur qui
apparaît réellement dans le code, à défaut d'un scheduler déjà branché en prod qui fixerait un choix
définitif).

## 1. Fréquence des cas (calibration visuelle / question 3 de l'objectif)

| Cas | BTCUSDT | ETHUSDT |
|---|---|---|
| cascade | 0 / 1968 (0.00%) | 0 / 1968 (0.00%) |
| buildup | 0 / 1968 (0.00%) | 0 / 1968 (0.00%) |
| conviction | 236 / 1968 (11.99%) | 246 / 1968 (12.50%) |
| neutre | 1732 / 1968 (88.01%) | 1722 / 1968 (87.50%) |

**Constat immédiat et déterminant** : avec les seuils par défaut (`oiDeltaCascadeThreshold=-0.10`,
`oiDeltaBuildupThreshold=+0.10`), les cas *cascade* et *buildup* ne se sont produits **strictement
aucune fois** sur les deux actifs, sur toute la fenêtre de 83 jours. Un delta d'Open Interest de
±10% entre deux bougies H1 **consécutives** ne s'est simplement pas produit sur cette période — ni
sur BTC ni sur ETH. Seul le cas *conviction* est assez fréquent (~12%) pour être testé
statistiquement.

## 2. Test statistique par cas typé vs groupe de contrôle

Méthodologie (miroir de `RejectionZoneIndicator` : taux mesuré sur les points classés dans un cas
vs sur TOUS les autres points évaluables, même fonction de mesure) :

- **cascade** : taux de non-continuation (stagnation/retournement) du prix sur l'horizon — valide
  la prédiction "mouvement peu durable".
- **buildup** : taux de retournement à la baisse sur l'horizon — valide "risque de retournement
  violent à venir".
- **conviction** : taux de continuation à la hausse sur l'horizon — valide "mouvement de qualité".

### Cascade et buildup : **non testables**

Conséquence directe de la fréquence nulle ci-dessus : aucun test statistique n'a pu être calculé
pour ces deux cas, sur les deux actifs, à aucun des 3 horizons (6h/12h/24h). Ce n'est pas "pas
d'edge démontré" comme pour `RejectionZoneIndicator` — c'est **littéralement aucune donnée pour se
prononcer**, un problème différent et plus fondamental.

### Conviction spot

| Actif | Horizon | Cas (n) | Contrôle (n) | Écart | p-value (test z, 2 proportions) |
|---|---|---|---|---|---|
| BTCUSDT | 6h | 55.5% (236) | 50.5% (1726) | +5.0 pts | 0.1459 |
| BTCUSDT | 12h | 58.9% (236) | 51.1% (1720) | **+7.8 pts** | **0.0246** |
| BTCUSDT | 24h | 57.6% (236) | 50.0% (1708) | **+7.6 pts** | **0.0280** |
| ETHUSDT | 6h | 48.8% (246) | 51.0% (1716) | -2.3 pts | 0.5057 |
| ETHUSDT | 12h | 50.8% (246) | 51.2% (1710) | -0.4 pts | 0.9031 |
| ETHUSDT | 24h | 50.0% (246) | 49.6% (1698) | +0.4 pts | 0.9038 |

**Sur BTC**, le cas conviction montre un écart positif et statistiquement significatif au seuil
usuel de 5% aux horizons 12h et 24h (+7.6 à +7.8 points, p<0.03). **Sur ETH**, aucun écart : le taux
de continuation des points classés "conviction" est indiscernable du hasard (48.8% à 51.2%, p entre
0.50 et 0.90) à tous les horizons. **Le signal ne se réplique pas d'un actif à l'autre** — même
symptôme d'inconsistance que celui documenté pour `RejectionZoneIndicator` (edge présent sur un run,
absent/négatif sur un autre découpage des mêmes données).

## 3. Sensibilité aux paramètres (grille 3^4 = 81 combinaisons)

Grille : `oiDeltaCascadeThreshold ∈ {-0.05,-0.10,-0.15}`, `oiDeltaBuildupThreshold ∈
{0.05,0.10,0.15}`, `fundingBuildupSignalThreshold ∈ {0.4,0.6,0.8}`, `priceMoveThreshold ∈
{0.01,0.02,0.03}` (horizon de test fixé à 12h, seuils funding low/high/neutralBand inchangés).

| Cas | BTCUSDT | ETHUSDT |
|---|---|---|
| cascade | 0/81 combinaisons testables | 0/81 combinaisons testables |
| buildup | 0/81 combinaisons testables | 0/81 combinaisons testables |
| conviction | 81/81 testables, taux **constant** à 58.9% (écart-type 0.0%) | 81/81 testables, taux **constant** à 50.8% (écart-type 0.0%) |

**Deux constats, aucun des deux favorable** :

1. **Cascade et buildup ne se déclenchent toujours pas même en relâchant le seuil d'OI delta
   jusqu'à ±5%** (3× plus permissif que le défaut ±10%) sur les deux actifs, sur les 81
   combinaisons. Un delta d'OI de ±5% entre deux bougies H1 consécutives ne s'est simplement pas
   produit sur cette fenêtre de 83 jours (BTC comme ETH). Deux lectures possibles : (a) l'Open
   Interest agrégé par Coinalyze (~25 exchanges) est trop lissé pour bouger de plusieurs points de
   pourcent d'une heure à l'autre, même lors des mouvements de prix marqués observés sur la
   période, ou (b) cette fenêtre de 83 jours n'a simplement pas connu d'évènement de cascade de
   liquidations assez violent pour être visible même sur 5% — plausible sur une fenêtre courte et
   un seul régime de marché. Dans les deux cas, **le delta OI entre 2 bougies H1 consécutives (la
   fenêtre exacte utilisée par `OpenInterestIndicator`/`intervalHours=1` par défaut) semble être la
   mauvaise granularité pour détecter une cascade** — une fenêtre plus large (delta sur 4h, 12h,
   24h plutôt que 1h) capturerait probablement des variations d'OI bien plus importantes.
2. **La grille ne teste structurellement pas la sensibilité du cas conviction** : sa condition de
   classification (`oiDelta >= 0 AND |fundingSignal| <= fundingNeutralBand AND
   volumeConfirmation > 0`) ne dépend d'AUCUN des 4 paramètres variés ici
   (`oiDeltaCascadeThreshold`, `oiDeltaBuildupThreshold`, `fundingBuildupSignalThreshold`,
   `priceMoveThreshold` n'apparaissent nulle part dans ce test booléen — `oiDeltaBuildupThreshold`
   n'intervient que dans le calcul de la *magnitude* du score, pas dans la classification). Le taux
   constant (écart-type exactement 0.0%) n'est donc pas un signe de robustesse du cas conviction —
   c'est un artefact de la grille demandée par le prompt, qui porte sur les 4 seuils "les plus
   structurants" pour cascade/buildup mais pas pour conviction. Une vraie grille de sensibilité pour
   conviction devrait varier `fundingLowThreshold`/`fundingHighThreshold`/`fundingNeutralBand`, non
   fait ici (hors scope du prompt tel que rédigé) — **à noter explicitement comme limite de ce
   run**, pas à présenter comme "conviction est stable".

## 4. Diagnostic complémentaire : OBV rate-of-change au lieu du signe brut

Piste suggérée par le prompt (section "Limites déjà identifiées") : si `conviction` est le cas le
plus fragile (confirmé ci-dessus — inconsistant BTC/ETH), tester si la limite "OBV ne porte que son
signe instantané, pas une pente" en est la cause. Diagnostic : remplace
`volumeConfirmation = Math.signum(obv)` par `Math.signum(obv[t] - obv[t-3])` (delta d'OBV sur 3
bougies) — n'affecte que le cas conviction (seul consommateur de `volumeConfirmation`), ne touche
pas `compute_signal` lui-même.

| Actif | Fréquence conviction (OBV RoC) | Horizon | Cas | Contrôle | Écart | p-value |
|---|---|---|---|---|---|---|
| BTCUSDT | 9.60% (189/1968), vs 11.99% signe brut | 6h | 50.3% | 51.2% | -0.9 pts | 0.8157 |
| | | 12h | 57.1% | 51.5% | +5.6 pts | 0.1400 |
| | | 24h | 54.5% | 50.5% | +4.0 pts | 0.3013 |
| ETHUSDT | 10.37% (204/1968), vs 12.50% signe brut | 6h | 46.6% | 51.3% | -4.7 pts | 0.2054 |
| | | 12h | 51.5% | 51.1% | +0.3 pts | 0.9291 |
| | | 24h | 49.0% | 49.7% | -0.7 pts | 0.8514 |

**Le diagnostic n'améliore rien** : sur BTC, l'écart à 12h passe de +7.8 pts (p=0.025, significatif)
à +5.6 pts (p=0.14, non significatif) — le signal s'affaiblit avec l'OBV rate-of-change plutôt que
de se renforcer. Sur ETH, toujours aucun edge (p entre 0.21 et 0.93). **L'hypothèse "le signe brut
d'OBV est le facteur limitant" est infirmée** : substituer une mesure de pente ne fait pas
apparaître un edge caché, ni sur BTC ni sur ETH — cohérent avec la conclusion générale de ce run
(le problème n'est pas un détail de calcul d'OBV, mais l'absence de signal robuste et réplicable
entre actifs sur la fenêtre disponible).

## Verdict

**Ne pas brancher `MovementQualificationStrategy` en production tel quel** (ni scheduler, ni
Opinion par défaut) :

- **Cascade et buildup sont non testables sur les données réellement disponibles** — strictement
  zéro occurrence sur 83 jours × 2 actifs × 81 combinaisons de seuils (même relâchés à ±5%). Ce
  n'est pas un verdict "pas d'edge" comme pour `RejectionZoneIndicator`, c'est une impossibilité de
  se prononcer : soit l'événement qu'ils cherchent à détecter (cascade de liquidations, sur-effet-
  de-levier en construction) est trop rare pour cette fenêtre de 3 mois, soit la fenêtre de calcul
  du delta d'OI (2 bougies H1 consécutives, 1h) est structurellement trop courte pour capter ce
  qu'elle cherche à capter — indice fort en faveur de la seconde lecture : un delta d'OI de ±5% en
  1h ne s'est produit à aucun des 1968 points testés, sur 2 actifs différents, ce qui suggère une
  granularité mal choisie plutôt qu'une pure coïncidence temporelle.
- **Conviction spot montre un signal faible et non réplicable** : significatif sur BTC (+7.6 à +7.8
  pts, p<0.03) à 12h/24h, mais absent sur ETH (écarts de -2.3 à +0.4 pts, p>0.50) à tous les
  horizons. Le diagnostic OBV rate-of-change (piste identifiée a priori comme le point faible le
  plus probable) n'améliore ni ne stabilise ce résultat — si quoi que ce soit, il l'affaiblit sur
  BTC. Un edge qui n'existe que sur un des deux actifs testés, sur une seule fenêtre temporelle
  courte, ne constitue pas une base suffisante pour brancher un modulateur de confidence en
  production.
- **Le statut `CONFIDENCE_MODULATOR` (plancher d'atténuation à 0.5) réduit le risque d'un mauvais
  signal mais ne le supprime pas** : brancher aujourd'hui reviendrait, pour cascade/buildup, à un
  code mort (jamais déclenché sur des conditions de marché normales/modérément volatiles vues sur
  cette fenêtre — donc probablement inoffensif, mais aussi totalement inutile en l'état) et pour
  conviction, à un modulateur qui a autant de chances d'atténuer la confidence dans le mauvais sens
  que dans le bon (résultat proche du hasard sur ETH).

### Pistes concrètes si le sujet est repris (aucune implémentée ici, hors scope de ce lot)

1. **Revoir la fenêtre du delta d'OI** avant de retester cascade/buildup : au lieu de 2 bougies H1
   consécutives (`intervalHours=1` par défaut dans `OpenInterestIndicator`), tester un delta sur
   4h/12h/24h — hypothèse principale pour expliquer la fréquence nulle observée ici, pas encore
   vérifiée empiriquement.
2. **Obtenir un historique OI/funding plus long** (plan Coinalyze payant, ou source alternative)
   pour sortir de la fenêtre de 3 mois/un seul régime de marché actuellement disponible sur le plan
   gratuit — condition nécessaire pour que cascade/buildup aient une chance statistique de se
   déclencher au moins quelques fois, et pour que le résultat sur conviction soit trans-régime
   plutôt que spécifique aux 83 derniers jours.
3. **Compléter la grille de sensibilité** avec `fundingLowThreshold`/`fundingHighThreshold`/
   `fundingNeutralBand` pour réellement tester la robustesse du cas conviction (non couvert par la
   grille de ce prompt, qui ciblait cascade/buildup).
4. Ne retenter un branchement en production qu'après ces 3 points, avec un verdict positif sur au
   moins 2 actifs et une fenêtre multi-régime — même standard que celui appliqué (et non atteint)
   par `RejectionZoneIndicator`.

## Reproductibilité

Fichiers CSV bruts (`btc_klines.csv`, `eth_klines.csv`, `oi_btc.csv`, `oi_eth.csv`,
`funding_btc.csv`, `funding_eth.csv`, tous dans `tools/calibration/`) régénérables via les 2 scripts
ci-dessus, non versionnés (même convention que les runs précédents de
`docs/calibration-rejection-zone.md`) — la fenêtre 2026-04-25→2026-07-16 sera obsolète dès que la
fenêtre de rétention Coinalyze aura glissé ; relancer `fetch_coinalyze_history.py` sans `--start`
figé à l'avance pour retrouver la fenêtre disponible au moment de la relecture de ce document.
