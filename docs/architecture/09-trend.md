# Trend unifié (`service/tree/trend`)

Vérifié le : 2026-10-04 (`service/tree/trend/**`, `TrendConfirmationStrategy`, `RegressiveTrendStrategy`, `LinearRegressionIndicator`).

## Rôle

Point unique de vérité pour l'état de tendance d'un actif : `TrendRegime` = `UP` / `DOWN` / `RANGE` (neutre). Architecture à 2 couches : calculateurs purs (sans Spring, sans `IndicatorResult`) + adaptateurs fins (`Indicator`/`Strategy`).

## Algorithme (`TrendAnalyzer`)

1. 3 régressions linéaires OLS des clôtures (`LinearRegressionCalculator`) sur 7 / 14 / 30 bougies.
2. Score continu ∈ [-1,1] par bougie (`RegressiveTrendScoreCalculator`) : `tanh(pente normalisée × 400)` par fenêtre, pondéré par r², poids égaux, facteur d'alignement désactivé.
3. Hystérésis (`RegressionHysteresisCalculator`) : entrée UP/DOWN si `|score| > 1/6`, retour à `RANGE` quand le score repasse de l'autre côté de 0.

Sortie `TrendState` : `regime`, `score`, `force` (= `|score|`), `confidence` (= `min(1, durée de l'état / 10)`, **provisoire, non calibrée, non utilisée** dans les scores), `bosJustOccurred`, `bosTimestamp`. Minimum 60 bougies (`MIN_CANDLES`). `analyzeTimeline` renvoie un état par bougie (bench, visualisation).

Constantes par défaut : définies une seule fois dans `RegressiveTrendStrategy.DEFAULT_*` (réglages issus d'un walk-forward BTC D1 2017-2026, non recalibrés par actif). Détail du protocole et des résultats : [`etudes/spec-composition-trend-unifie.md`](../etudes/spec-composition-trend-unifie.md) §10.

## Consommateurs

- `TrendConfirmationStrategy` (`DIRECTIONAL`, scope `LOCAL`, combinaison par défaut de `DefaultLocalOpinionParamsProvider`) : 3 indicateurs `LINEAR_REGRESSION` en entrée, le `type` du signal est dérivé du `regime` (jamais redérivé du score brut).
- `RegressiveTrendStrategy` : même score, sans hystérésis.
- Bench `RainbowAtrRegimeBench` (test) : régimes UP/DOWN/RANGE rejoués jour par jour.
- **Pas consommé par le Rainbow DCA ATR en production ni par le bench grandeur nature** (aucun jeu de paramètres n'est sélectionné par la Trend aujourd'hui), pas d'exposition MCP.

## Hors de l'axe Trend

`SWING_STRUCTURE` (`SwingStructureCalculator`, pivots HH/HL/LH/LL, régime BULL/BEAR/WARNING_*) et ADX ont été retirés du calcul de la Trend ; `SWING_STRUCTURE` reste un `IndicatorType` autonome.

## Limites connues

- Un seul calculateur (pas de Trend « globale » lente en plus de la « locale » : étude et spec V1 §1 l'avaient proposé, **non implémenté**).
- `RANGE` n'a pas de pendant dans le besoin Rainbow actuel (Bull/Bear uniquement) : mapping à définir ([`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §2).
