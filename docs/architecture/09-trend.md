# Trend unifié (`service/tree/trend`)

Vérifié le : 2026-10-08 (`service/tree/trend/**`, `TrendConfirmationStrategy`, `RegressiveTrendStrategy`, `LinearRegressionIndicator`).

## Rôle

Point unique de vérité pour l'état de tendance d'un actif : `TrendRegime` = `UP` / `DOWN` / `RANGE` (neutre). Architecture à 2 couches : calculateurs purs (sans Spring, sans `IndicatorResult`) + adaptateurs fins (`Indicator`/`Strategy`).

## Algorithme (`TrendAnalyzer`)

1. 3 régressions linéaires OLS des clôtures (`LinearRegressionCalculator`) sur 7 / 14 / 30 bougies.
2. Score continu ∈ [-1,1] par bougie (`RegressiveTrendScoreCalculator`) : `tanh(pente normalisée × 400)` par fenêtre, pondéré par r², poids égaux, facteur d'alignement désactivé.
3. Hystérésis (`RegressionHysteresisCalculator`) : entrée UP/DOWN si `|score| > 1/6`, retour à `RANGE` quand le score repasse de l'autre côté de 0.

Sortie `TrendState` : `regime`, `score`, `force` (= `|score|`), `confidence` (= `min(1, durée de l'état / 10)`, **provisoire, non calibrée, non utilisée** dans les scores), `bosJustOccurred`, `bosTimestamp`. Minimum 60 bougies (`MIN_CANDLES`). `analyzeTimeline` renvoie un état par bougie (bench, visualisation).

Constantes par défaut : définies une seule fois dans `RegressiveTrendStrategy.DEFAULT_*` (réglages issus d'un walk-forward BTC D1 2017-2026, non recalibrés par actif). Détail du protocole et des résultats : [`etudes/spec-composition-trend-unifie.md`](../etudes/spec-composition-trend-unifie.md) §10.

## Trend Mix (`TrendMixCalculator`)

Calculateur pur, portage Java de la méthode « Mix » de `tools/pine/rainbow_trend_dca_v1.pine` (source de vérité ; réglages par défaut = `Params.defaults()`). SMA et ATR sont fournies par l'appelant (mêmes séries que le Rainbow : `RainbowAtrDataset#sma/atr`).

- **Régression** : 3 fenêtres (14 / 30 / 60), échelle de pente 400, poids égaux, score borné [-1,1] ; hystérésis ENTER 0,30 / EXIT 0,10 en chaîne `else if` (pas de sortie + entrée sur la même bougie, contrairement à `RegressionHysteresisCalculator`), puis confirmation de 10 jours consécutifs.
- **Prix vs SMA ± k × ATR** : SMA 100, ATR 5, k 1,25 ; DOWN sur la mèche basse sous SMA − k×ATR, UP sur la clôture au-dessus de SMA + k×ATR ; état conservé entre deux franchissements.
- **Composition** : la régression UP/DOWN donne le régime ; en RANGE c'est le régime de la SMA. Le 1er des deux à changer de côté fait basculer (RANGE n'est pas un switch : la dernière direction décisive de la régression est mémorisée). Régime `null` avant `Params.warmup()` bougies (le sélecteur reste alors sur Bear).

Sortie `Result` : `regime`, `regression`, `sma`, `source` (régression / SMA) par bougie. Utilisé par le rejeu comparatif (mode `TREND_MIX`) et par `RainbowTrendLiveService`.

## Consommateurs

- `TrendConfirmationStrategy` (`DIRECTIONAL`, scope `LOCAL`, combinaison par défaut de `DefaultLocalOpinionParamsProvider`) : 3 indicateurs `LINEAR_REGRESSION` en entrée, le `type` du signal est dérivé du `regime` (jamais redérivé du score brut).
- `RegressiveTrendStrategy` : même score, sans hystérésis.
- Bench `RainbowAtrRegimeBench` (test) : régimes UP/DOWN/RANGE rejoués jour par jour.
- `RainbowSetSelector` + `RainbowAtrReplayMain` (rejeu comparatif au pine, cf. [`04-market-data.md`](04-market-data.md)) : Trend → jeu Bull/Bear, Trend Mix + variantes de Trend, `RANGE` = garder le jeu précédent. Rejeu seulement : toujours rien en production. `tools/pine/trend_bull_bear_v3.pine` : banc d'essai TradingView de la Trend (score multi-fenêtres + hystérésis + confirmation, prix vs SMA ± k×ATR (bande adaptée à la volatilité de l'actif), 2 régressions, Mix régression + SMA/ATR ; fond Bull/Bear, 2 barres de régime régression/SMA, stats de switchs). `tools/pine/rainbow_trend_dca_v1.pine` : méga-indicateur TradingView = Trend (Mix) + Rainbow v4 ; la Trend choisit à chaque bougie le jeu Bear/Bull (auto par actif : BTC Perso Bear/Bull, ETH Perso Bear/Bull Tuned, PAXG Perso Bear/Bull ; menus de surcharge), bornes/tuning/ATH/moon du jeu actif par bougie, état de la machine conservé au changement de jeu. Réf. des réglages Trend utilisés : Mix, 14/30/60, échelle 400, ENTER 0,3, EXIT 0,1, confirm 10, SMA 100, ATR 5, mèche basse activée / haute désactivée, k 1,25.
- **Trend Mix consommée par les presets `TREND_MIX` du bench grandeur nature** (dry-run quotidien, [`08`](08-rainbow-bench-grandeur-nature.md)) ; aucune exécution réelle. Pas d'exposition MCP.

## Hors de l'axe Trend

`SWING_STRUCTURE` (`SwingStructureCalculator`, pivots HH/HL/LH/LL, régime BULL/BEAR/WARNING_*) et ADX ont été retirés du calcul de la Trend ; `SWING_STRUCTURE` reste un `IndicatorType` autonome.

## Limites connues

- Un seul calculateur (pas de Trend « globale » lente en plus de la « locale » : étude et spec V1 §1 l'avaient proposé, **non implémenté**).
- `RANGE` n'a pas de pendant dans le besoin Rainbow actuel (Bull/Bear uniquement) : mapping à trancher par comparaison des 3 variantes du rejeu (`RangeMapping`) ([`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §2).
