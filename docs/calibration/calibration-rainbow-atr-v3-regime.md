# Calibration — Rainbow DCA ATR v3 (ATH + To the moon) : meilleurs paramètres, global vs par régime de Trend

Vérifié le : 2026-10-04. Moteur : `service/dca/atr/RainbowAtrEngine` (port fidèle de `tools/pine/rainbow_dca_v4_atr_moon.pine`, source de vérité — cf. [`architecture/04-market-data.md`](../architecture/04-market-data.md)). Bench : `src/test/java/.../service/dca/atr/RainbowAtrRegimeBench.java`. Données : D1 Binance `tools/calibration/{btc,eth,paxg}usdt_klines_d1_full.json` (BTC/ETH 2017-08 → 2026-09, PAXG 2020-08 → 2026-10 ; script `tools/calibration/fetch_binance_klines_d1.py [SYMBOLES]`), bench **hors réseau/DB**. Un bench par actif (`<dir>/d1.csv`), pas de pooling.

## Protocole

- **Fenêtres** : 180 bougies, pas 30 (BTC/ETH 101 fenêtres, PAXG 65), démarrage à froid, base 1 par jour. ATH et To the moon calculés sur tout l'historique (comme le pine), la machine d'achat/vente est rejouée sur la fenêtre.
- **Score (objectif A)** : score de Clem (moyenne harmonique realized/potential, pénalisé proportionnellement si realized < 50 % du gain) en % du budget du DCA fixe de la fenêtre, **à capital déployé plafonné à ce budget** (sinon les facteurs ATH/multiplicateurs « achètent » de la performance en déployant 3-5× plus). Contrôle (objectif B) : excès de gain total vs DCA fixe, même unité.
- **Recherche** : coordinate ascent multi-passes, 30 départs aléatoires + presets pine, ~30 paramètres (17 « bornes/mécanisme » + 12 ATH/moon), grilles dans le bench.
- **Régime** : `TrendAnalyzer` de production (régression 7/14/30 + hystérésis, causal) : BTC UP 47,5 / DOWN 40,8 / RANGE 11,7 % ; ETH 45,1 / 41,7 / 13,2 % ; PAXG 46,3 / 32,2 / 21,5 %. Mode **RÉGIME** = 3 jeux complets piloté jour par jour (état de la machine conservé aux changements) ; **RÉGIME_LITE** = seuls `atrMultDown2`, `atrMultUp3`, `sellFraction`, `smaPeriod` varient par régime ; **GLOBAL** = un seul jeu. ATH/moon globaux dans tous les cas.
- **Validation** : walk-forward annuel (BTC/ETH 2021→2026 = 6 folds ; PAXG 2024→2026 = 3 folds, historique plus court) — optimisation sur les fenêtres entièrement antérieures à l'année, test sur celles entièrement dans l'année.

## Verdict révisé (2026-10-04) : un paramétrage distinct par trend (Bull / Bear) est nécessaire

**Un entraînement automatique (coordinate ascent multi-départs, walk-forward) a été réalisé sur BTC, ETH et PAXG ; ses jeux se sont révélés empiriquement mauvais.** Son verdict (« un jeu global par actif ») est invalidé par l'expérience manuelle de Clem. En optimisant à la main sous TradingView (pine v4) un jeu **Bull** et un jeu **Bear** par actif (BTC, ETH, PAXG Kraken), elle obtient des rendements nettement meilleurs que le jeu global du bench, dont les résultats étaient médiocres ou biaisés (jeux qui ne vendent jamais, ou qui n'achètent qu'au départ du test). Décision : paramétrage **par trend, Bull et Bear uniquement (pas de Sideways), par actif**. Les tableaux ci-dessous ne sont **pas** une référence de paramétrage.

**Pourquoi le bench automatique n'est pas fiable** (constats de Clem) : ~30 paramètres couplés ; influences non monotones (augmenter un paramètre dégrade le rendement puis, au-delà d'un seuil, donne un jeu meilleur que tout ce qui avait été testé) ; des départs « farfelus » convergent parfois vers d'excellents jeux ; l'optimisation par coordinate ascent reste piégée dans des optima locaux ; le score laisse passer des jeux dégénérés. Suite : [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) (§1 paramétrage automatique d'un actif, §2 branchement de la Trend).

### Résultats du bench automatique (informatifs — verdict invalidé)

Hors-échantillon (moyenne des folds), score A / excès vs DCA fixe (% budget) :

| Mode | BTC | ETH | PAXG |
|---|---|---|---|
| GLOBAL | −1,07 / +4,82 | −0,53 / +8,84 | +0,73 / −0,85 |
| RÉGIME (3 jeux complets) | −2,13 / +2,26 | −3,58 / +2,31 | −0,31 / −3,43 |
| RÉGIME_LITE (4 paramètres) | −2,70 / −0,74 | −2,92 / +3,87 | −0,34 / −3,85 |
| pine FINAL / CUSTOM | −4,80 / −2,24 ; +3,07 / +2,46 | −2,75 / −0,56 ; +4,92 / +4,66 | −0,83 / −0,07 ; −1,92 / −3,89 |

RÉGIME − GLOBAL (score A) : BTC −1,06 ± 1,71 (3/6 folds), ETH −3,05 ± 1,72 (1/6), PAXG −1,03 ± 0,71 (0/3). Objectif B (excès) : BTC +2,88 ± 1,73, ETH −6,93 ± 5,71, PAXG +0,70 ± 4,53 — signe incohérent entre actifs. Le score d'entraînement progresse avec 3 jeux mais ne se transfère pas : sur-ajustement (3× plus de paramètres, RANGE 12-21 % des jours) ; les jeux par régime in-sample diffèrent fortement du global sans gain hors-échantillon (ex. ETH DOWN SMA 26/ATR 10 vs UP SMA 36/ATR 21 ; PAXG UP SMA 75/up3 4 vs DOWN SMA 14/up3 1,5). Conclusion du bench (invalidée, cf. ci-dessus). Le pine (`tools/pine/rainbow_dca_v4_atr_moon.pine`) expose un sélecteur `Preset` nommé (Perso Generic, « <ACTIF> Bench global », « <ACTIF> Perso Bear/Bull ») : le choix du jeu Bull/Bear y est **manuel** ; son pilotage automatique par la Trend n'est pas branché.

> Les sections ci-dessous décrivent les jeux **globaux** du bench ; ce sont les presets par défaut du bench grandeur nature (`Bench global`, [`08`](../architecture/08-rainbow-bench-grandeur-nature.md)), plus la cible du paramétrage.

## Jeu global recommandé BTC (centre des plateaux + consensus des folds)

`sma=50 atr=21 down2=5 down1=0.5 up1=2 up2=3 up3=3.5 | buy=TRAILING_STOP(3 %) sell=FIXED_DELAY fixedDelay=15 sellFraction=0.25 | cooldown=1 allowSellDuringCooldown=false cooldownAfterSellOn=true blockBuyAfterSellUntilDown2=true | athOn refBuy=30 % refSell=15 % buyMin=0.25 buyMax=4 sellMax=1 sellMin=1`

Sur toutes les fenêtres 2018-2026 (en partie in-sample) : excès moyen **+4,3** vs DCA fixe (pire année −8,2 en 2019), contre −0,1 (pine FINAL) et −4,3 (pine CUSTOM). Hors-échantillon la procédure donne +4,8/+5,3 (A/B) vs +3,1/+2,5 pour les presets pine : avantage réel mais modeste, dans le bruit (6 années, fenêtres chevauchantes). Années baissières (2018, 2022, 2026) : score négatif quel que soit le jeu (le potentiel est négatif) — le DCA ATR amortit mais ne fait pas de miracle.

## Optimum global par actif (plein échantillon, 30 départs) et transfert

| | BTC | ETH | PAXG |
|---|---|---|---|
| bornes | sma 50 atr 28, down2 8, up 2/4/4 | sma 36 atr 21, down2 4, up 2/3/3,5 | sma 36 atr 28, down2 6, up 2/2/3 |
| mécanique | buy trailing 3 %, sell FIXED_DELAY 20 j, sellFraction 0,25, cooldown 0 | buy/sell FIXED_DELAY 3 j, sellFraction 0,20, cooldown 7 (`cooldownAfterSellOn` : off → −3,2) | buy FIXED_DELAY 10 j, sell trailing 2 %, sellFraction 0,20, cooldown 1 (neutre) |
| ATH | refBuy 45, refSell 5, buyMax/Min 5/0 | refBuy 60 (plateau 15-80), sellMax 3 | refBuy 15 (↓ monotone : 60 → 1,65 vs 2,34), buyMin 1 |
| moon | off | on 50 % (on/off : +0,9) | on 75 % (on/off : +0,4) |
| score A in-sample | 6,2 | 7,8 | 2,3 |

Communs aux 3 actifs : `athOn=true` (off → score ÷ 3-9), `blockBuyAfterSellUntilDown2=true` (off → −3,9 / −5,3 / −1,4), `sellFraction` 0,2-0,25 (≥ 0,5 s'effondre), `atrMultUp3` 3-3,5 (≥ 5 s'effondre), `atrMultDown2` ≥ 4, `smaPeriod` 36-50, `up1=2`. PAXG (or) : gain très inférieur (score ~2 vs 6-8) et excès OOS ≈ −1 vs DCA fixe → le DCA ATR n'apporte pas de valeur mesurée sur l'or sur 3 folds ; modulation ATH moins profonde (drawdowns faibles). Le jeu BTC recommandé ci-dessous appliqué tel quel : ETH/PAXG non validé hors BTC (sur PAXG ≈ DCA fixe : −0,13 d'excès moyen 2021-2026, pire année −4,2) — recalibrer par actif.

## Ce qui compte — sensibilités BTC (un paramètre à la fois, score A plein échantillon : 6,4 ; ETH/PAXG cf. rapports)

- **Modulation ATH à l'achat = levier n°1** : `athOn=false` → 0,7. Acheter peu près de l'ATH et beaucoup à ≥ 20-45 % dessous (`refBuy` 15-45 plateau ; rapport `buyMax/buyMin` ≥ 4-5 ; `buyMax` est en butée de grille — le plafond de capital rend le niveau absolu non identifiable, seul le rapport compte). Modulation ATH **à la vente neutre/négative** (`sellMax=sellMin=1` retenu).
- **`blockBuyAfterSellUntilDown2=true`** : +3,9 (cohérent dans 6/6 folds).
- **Vente** : `sellReentryMode=FIXED_DELAY`, `fixedDelayDays` pic net à 15 (10 → 5,4 ; 20 → 4,4), `sellFraction` 0,20-0,25 (0,1 → 1,3 ; 0,5 → 0,8 ; 1,0 → −3,6).
- **Bornes** : `smaPeriod` 36-75 (50 optimal ; ≤ 14 s'effondre), `atrMultDown2` 4-8, `atrMultUp3` 3-4 (≥ 5 : score 2,0 puis 0,3), `atrPeriod` peu sensible, `up1`/`up2` peu sensibles. Les folds anciens préfèrent SMA courte/bornes étroites, les récents SMA 50/bornes larges : le plateau est le seul point robuste.
- **Cooldown** : 0-1 jour ; ≥ 3 jours dégrade (12 j : −2,7). `allowSellDuringCooldown`/`cooldownAfterSellOn` sans effet à cooldown 0-1. `trailingStop*` sans effet (modes FIXED_DELAY/IMMEDIATE dominants).
- **To the moon** : neutre à légèrement négatif sur ces fenêtres (on/off : ±0,5 ; réserve 25 % → −0,3, 75 % → −2,0). C'est une assurance contre les tops paraboliques que des fenêtres de 6 mois ne valorisent pas — à garder ou non selon l'appétence au risque, pas un gain mesuré. Aucune recommandation chiffrée sur `moonReservePct`/`moonReserveRatchet`.
- **Capital déployé** : le jeu recommandé déploie en moyenne ~280 % du budget DCA fixe (112 % à 478 % selon l'année, maximum en bear) — à dimensionner (`baseAmount`) en conséquence.

## Limites

3 actifs, historique 9 ans (BTC/ETH) et 6 ans (PAXG, 3 folds), fenêtres chevauchantes, BTC/ETH très corrélés : les écarts-types sont du même ordre que les effets. Résultats sensibles à la définition du score (harmonique realized/potential : un `potential` négatif plafonne le score). Bornes ATR monotones imposées (`down2 ≥ down1 ≥ 0`, `0 ≤ up1 ≤ up2 ≤ up3`).

Les rapports générés par le bench (tableaux par fold, sensibilités) ont été supprimés : le bench ayant été invalidé empiriquement, seul ce document en conserve la synthèse.

## Rejouer

```
python tools/calibration/fetch_binance_klines_d1.py PAXGUSDT                       # données (réseau Binance)
python tools/pine/rainbow_atr_pine_port.py export-csv tools/calibration/<sym>usdt_klines_d1_full.json target/rainbow-atr/<actif>   # d1.csv (dossier existant)
javac -d target/rainbow-atr/classes -cp target/classes src/main/java/.../service/dca/atr/*.java src/test/java/.../service/dca/atr/RainbowAtrRegimeBench.java
java -Xmx2g -cp target/rainbow-atr/classes:target/classes fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrRegimeBench target/rainbow-atr/<actif> 30   # -> bench-report.md (~1 min/actif)
```

Parité moteur/pine : `tools/pine/rainbow_atr_pine_port.py parity <dir>` puis `RainbowAtrParityMain <dir>` (60 cas, 0 écart) ; garde-fous permanents dans `RainbowAtrEngineTest` (golden Python) et `RainbowAtrIndicatorTest`.
