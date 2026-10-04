# Roadmap d'implémentation — Indicateur de Trend unifié

Fait suite à [`docs/etudes/etude-indicateur-trend-unifie.md`](../etudes/etude-indicateur-trend-unifie.md) (lire avant de commencer). Rappel du périmètre : construire un point unique de vérité pour la Trend (Régime + Force + Confiance + BOS), réutilisable par le futur calculateur DCA Rainbow et par `TrendConfirmationStrategy`, sans dupliquer une 4e méthode de calcul.

**Statut au 2026-09-18** : les 4 étapes de cette roadmap sont livrées (`TrendConfirmationStrategy` consomme désormais `TrendAnalyzer`, cf. Étape 4 ci-dessous) — mais ce chantier n'est **pas entièrement clos** : l'axe Régime de `TrendAnalyzer` ne porte toujours **aucun repli/nuance RANGE** (points ouverts B/C de l'étude, §5). Tant que ce point n'est pas traité, `TrendAnalyzer`/`TrendConfirmationStrategy` ne distinguent qu'UP/DOWN confirmés ou WARNING/UNDEFINED — jamais un régime RANGE à proprement parler. Reste également non calibrée : la formule de Confiance (run-length/30, pénalité x0.5 sur WARNING_*, cf. Étape 3).

**Mise à jour 2026-09-24** : Étape 8 fait — la roadmap est désormais close. L'axe Régime porte un vrai état RANGE (`TrendRegime.RANGE`, neutre) depuis ce lot ; les paragraphes "Statut au 2026-09-18" et "Mise à jour 2026-09-21" ci-dessous décrivent l'état intermédiaire (`SWING_STRUCTURE`+ADX, pas de RANGE) qui a précédé la décision de Clem du 2026-09-24 (§7 ci-dessous) de tout reporter sur la régression à hystérésis — conservés pour l'historique du raisonnement, plus l'état actuel du code.

**Mise à jour 2026-09-21** : une réflexion dialectique (thèse/anti-thèse/jugement, cf. addendum §8 de [`etude-indicateur-trend-unifie.md`](../etudes/etude-indicateur-trend-unifie.md)) a tranché la question du "meilleur combo d'indicateurs" pour la Trend, posée par Clem suite à l'introduction de `RegressiveTrendStrategy` (3 fenêtres `LINEAR_REGRESSION`, posée le 2026-09-19, jusqu'ici hors périmètre de cette roadmap). Verdict : le combo `SWING_STRUCTURE` + `LINEAR_REGRESSION` + ADX est validé comme socle (pas de 4e brique directionnelle, pas de changement de granularité pour l'instant), mais deux points restent structurants et non tranchés — la fonction de composition régime/régression (hiérarchie envisagée : régime = gate directionnel, régression = signal de timing à l'intérieur du gate) et le rôle exact d'ADX (risque de double comptage avec le `r²` de la régression, à vérifier empiriquement). Deux gates bloquants sont ajoutés avant toute calibration ou tout assemblage : **Étape 5** (fiabilité de l'historique D1) et **Étape 6** (corrélation ADX/régression). `RegressiveTrendStrategy` rejoint donc formellement le périmètre de cette roadmap.

**Architecture actée le 2026-09-18 (Q1/Q2/Q3 de l'étude)** : pattern à deux couches, appliqué de façon identique aux deux indicateurs de ce chantier —
- une couche **calculateur** (service Java pur, logique testable sans dépendre du modèle `IndicatorResult`), qui retourne un **DTO/record riche** (enum, pas d'encodage numérique) ;
- une couche **adaptateur** (`Indicator` au sens `IndicatorEngine`), fine, qui appelle le calculateur et encode son résultat en `IndicatorResult` pour rester dans le moule `get_indicator`/MCP existant.

Pas d'exposition MCP pour le calculateur de Trend unifié lui-même dans cette roadmap (Q3) — seul `SWING_STRUCTURE` est un vrai `IndicatorType` branché dès cette V0 (Q1), le calculateur de Trend restera un service Java interne, consommé directement par `TrendConfirmationStrategy` refactorée (étape 4). Son exposition MCP est un chantier futur à rouvrir séparément.

Convention reprise des roadmaps précédentes (`prompt-implementation-decision-palier3-roadmap.md`) : chaque étape ci-dessous fera l'objet d'un prompt d'implémentation détaillé séparé au moment de l'attaquer — ce document fixe le séquencement et le contrat de chaque étape, pas le détail ligne à ligne.

## Étape 1 — `SWING_STRUCTURE` (indicateur complet) — ✅ fait

- `SwingStructureCalculator` (service pur, `service/tree/trend/`) : algorithme de la spec `spec-structure-regression-rainbow.md` §1.4 (détection de pivots confirmés dès la bougie de retournement, sans seuil ni paramètre depuis la réécriture du 2026-09-19 ; classification HH/HL/LH/LL via `PivotClassification`, régime BULL/BEAR/WARNING_BULL_BREAK/WARNING_BEAR_BREAK/UNDEFINED via `SwingStructureRegime`). Retourne `SwingStructureState` (DTO riche : régime en enum, pivots `SwingPivot` dates/prix, pas de code numérique à ce niveau).
- `SwingStructureIndicator` (`IndicatorType.SWING_STRUCTURE`, `service/tree/indicator/impl/`) : adaptateur fin qui appelle le calculateur et encode selon la spec §1.5 (`value` = code numérique -1/-0.5/0/+0.5/+1, `values` = `lastSwingHigh(EpochDay)`, `lastSwingLow(EpochDay)`, `previousSwingHigh/Low`, `sr1..sr4`). Dépendance formelle sur `ATR` via `IndicatorDependency` (pattern `RainbowSmaIndicator`→`SMA`) : sert de garde-fou de validité, pas de source numérique. Point d'écart documenté vs. la spec : `AtrIndicator` n'expose que la dernière valeur ATR, alors que le calculateur a besoin d'une série par bougie — `SwingStructureIndicator` recalcule donc en interne une série ATR (Wilder, même formule que `AtrIndicator`/`AdxIndicator`) plutôt que de réimplémenter un calcul ATR différent ; duplication assumée, non extraite dans ce lot.
- Tests : unitaires synthétiques (`SwingStructureCalculatorTest`, cas `WARNING_*`/`UNDEFINED`/dual-confirmation/classification/`nearestSrLevels`), intégration via `IndicatorEngine` (`SwingStructureIndicatorTest`), et sanity check sur données BTC réelles (`SwingStructureCalculatorRealBtcSanityTest`, lit `tools/calibration/btc_klines_d1.csv`, assertions structurelles uniquement — pas de date/prix de pivot en dur).

## Étape 2 — Harnais de calibration empirique (BTC) — ✅ close, sans objet (2026-09-24)

- Motif : **sans objet, `SwingStructureCalculator` réécrit le 2026-09-19 sans paramètre de calibration** (plus d'`atrMultiplier`/ATR : un pivot est confirmé dès la bougie de retournement, cf. javadoc de la classe). Il n'y a plus de valeur par défaut à figer sur cette brique.
- La classification RANGE fine et la règle de repli `UNDEFINED`/instable (points B/C de l'étude), qui étaient rattachées à cette étape, restent **ouvertes** : elles sont reprises par la spécification de composition (cf. [`docs/etudes/spec-composition-trend-unifie.md`](../etudes/spec-composition-trend-unifie.md)) et la formalisation de la règle RANGE (étapes suivantes anticipées ci-dessous).

## Étape 3 — `TrendAnalyzer` (calculateur de Trend unifié) — Force/Confiance/BOS faits, Régime (repli RANGE) en attente de la règle RANGE (points B/C, cf. spec de composition)

Statut au 2026-09-18 (implémentation, pas encore discuté avec Clem — à considérer comme une proposition, pas un acquis) : `TrendState`/`AdxFactorCalculator`/`TrendAnalyzer` livrés, extraction d'`adxFactor` faite dans `TrendConfirmationStrategy` sans régression (`TrendConfirmationStrategyTest` inchangé, 4/4 vert), 629 tests (baseline 612 + 17 nouveaux), 2 échecs préexistants sans rapport (`DxyIndicatorCacheSharingTest`, cache TwelveData/DXY). La formule de Confiance (run-length/30, pénalité x0.5 sur `WARNING_*`) est provisoire, non calibrée, à valider avec Clem (cf. `prompt-implementation-trend-unifie-etape3.md` sec. 0/4.3). L'axe Régime ne porte aucun repli/nuance RANGE — reporté à la formalisation de la règle RANGE (points B/C de l'étude).

- Service Java (pas de nouvel `IndicatorType` à ce stade, cf. Q3) qui assemble :
  - **Régime** : direction via `SwingStructureCalculator` (BULL/BEAR), repli/nuance RANGE selon la classification à trancher (points B/C de l'étude) ;
  - **Force** : `adxFactor`, extrait de `TrendConfirmationStrategy` vers une méthode/classe partagée (pas dupliqué) ;
  - **Confiance** : run-length du régime confirmé + pénalité sur état `WARNING_*` de `SwingStructureCalculator` ;
  - **BOS** : dérivé de la transition de régime de `SwingStructureCalculator` (pas un calcul séparé).
- Sortie : DTO `TrendState` (nom à confirmer), enum `Régime` riche (pas d'encodage numérique), champs Force/Confiance en `[0,1]`, flag/horodatage BOS.
- Tests unitaires en combinant des fixtures ADX + `SwingStructureCalculator` connues (pas de nouveau fetch réseau, réutilise les fixtures des étapes 1/2).

## Étape 4 — Refactor `TrendConfirmationStrategy` — ✅ fait (2026-09-18)

- `TrendConfirmationStrategy` consomme désormais `TrendAnalyzer` (Régime/Force/Confiance) au lieu de dupliquer son propre calcul EMA/ADX/RSI. Contrat d'entrée : 2 indicateurs (`SWING_STRUCTURE` + `ADX`), le garde-fou RSI est retiré (pas remplacé — la Confiance de `TrendAnalyzer` joue un rôle de dampening analogue).
- Deux extractions préalables partagées avec `SwingStructureIndicator` : `AtrSeriesCalculator` (série ATR par bougie) et `SwingStructureRegimeScore` (encodage numérique du régime) — comportement strictement identique, `SwingStructureIndicatorTest` reste vert.
- **Changement de comportement assumé** (pas glissé silencieusement) : la composante directionnelle du score passe d'un biais EMA (croisement de moyennes mobiles) à un régime de structure de marché (`SWING_STRUCTURE`, pivots confirmés) — signaux non équivalents, aucun backtest avant/après demandé pour ce lot.
- **Changement de TimeFrame en production** : `TrendConfirmationStrategy` tourne désormais en `TimeFrame.D1` (`DefaultLocalOpinionParamsProvider`), plus `H1` — `SWING_STRUCTURE` n'a été conçu/calibré que sur D1 (cf. étude sec. 2.4/4). Change aussi la cadence de réévaluation implicite de cette Strategy dans le cycle `DecisionOrchestrator`. À discuter avec Clem si un retour à une cadence plus fréquente est souhaité.
- Seuils ADX (`15.0`/`25.0`) inchangés.
- `TrendConfirmationStrategyTest` entièrement réécrit pour le nouveau contrat (bougies D1 hand-crafted, le générateur MEMORY monotone ne produit jamais de régime `SWING_STRUCTURE` confirmé) ; `DefaultMarketOpinionTest_UT` (tests de chaîne complète haussier/baissier) également adapté pour la même raison.

## Étape 5 — Vérification de la profondeur/fiabilité de l'historique D1 (gate infra) — ✅ fait (2026-09-21), verdict GO

- Objectif : mesurer la profondeur réellement persistée en base pour BTCUSDT D1/H1, et vérifier que le mécanisme de cache-aside (`CachingMarketDataApiClient`) comble correctement les trous sur une fenêtre pluriannuelle (plusieurs cycles de marché) — pas seulement sur les fenêtres courtes déjà exercées en production.
- **Gate bloquant** : toute calibration walk-forward (calibration de `RegressiveTrendStrategy`, Étape 6 ci-dessous dans sa partie régime-vs-régression) en dépend. Ne pas lancer de calibration tant que ce lot n'a pas rendu un verdict GO/NO-GO documenté.
- Prompt détaillé : [`docs/prompts/prompt-implementation-trend-unifie-etape5-historique-d1.md`](prompt-implementation-trend-unifie-etape5-historique-d1.md).
- **Résultat** : runner manuel `HistoricalDataDepthManualRunnerTest` (`service/connector/apiclient/marketdata/`, `@Disabled`, même patron que les autres bancs) — exécuté en réel contre la vraie base de dev (pas H2 : écart assumé et documenté dans sa javadoc, le sujet même du gate étant l'état réellement persisté). Backfill H1 BTCUSDT via le cache-aside existant (aucune ligne de production modifiée), rejoué jusqu'à convergence (56 itérations, ~83s — écart méthodologique documenté : Binance plafonne chaque réponse à ~1000 bougies quel que soit le `since`/`until` demandé, un appel unique ne suffit pas à combler un trou pluriannuel). Base réelle passée de 24 786 à 79 608 bougies H1 (2017-08-17 → aujourd'hui, vérifié par requête SQL directe) ; 133h manquantes résiduelles (0,17%), confirmées définitives côté provider (Binance renvoie 0 bougie sur ces plages précises — pas un bug du mécanisme) ; fenêtre cible de calibration (2020-01-01+) : 58 900 bougies, 33h manquantes (0,06%, 16 micro-trous de 1 à 5h). D1 dérivé par resampling H1→D1 (`Bucket`, même patron que `RainbowDcaBacktestService`) : 3 323 jours, 2017-08-17 → 2026-09-21 (~9,1 ans).
- **Finding architectural signalé, non corrigé dans ce lot** (scope explicite du prompt) : aucun `MarketDataApiClient` réel de ce projet (Binance, Kraken) ne supporte `TimeFrame.D1` nativement (`NATIVE_INTERVALS` = H1 seul) — `cachingBinanceMarketDataApiClient.getCandles(symbol, D1, ...)` échoue systématiquement (`IllegalArgumentException`), confirmé en run. Le D1 n'est et ne peut être obtenu que par resampling H1→D1 via `Bucket`, jamais persisté directement en cache — à garder en tête pour tout futur consommateur qui tenterait de lire du D1 directement depuis le cache-aside.
- **Verdict GO validé par Clem le 2026-09-21** : historique H1/D1 fiable sur la fenêtre visée (trous résiduels négligeables et définitivement côté provider, pas rattrapables par un rerun) — la calibration walk-forward (Étape 6, `RegressiveTrendStrategy`) peut démarrer.

## Étape 6 — Analyse de corrélation empirique ADX vs régression (r²) — ✅ fait (2026-09-24)

- Objectif : trancher si ADX apporte une information marginale par rapport au `r²` déjà utilisé par `RegressiveTrendStrategy` (risque de double comptage identifié par la réflexion dialectique du 2026-09-21, cf. addendum étude §8), et caractériser les cas de divergence de signe entre le régime `SWING_STRUCTURE` et `RegressiveTrendStrategy` (matière pour la future fonction de composition).
- Dépend de l'Étape 5 pour la partie "régime vs régression" (a besoin d'un historique D1 fiable) ; la partie "ADX vs r²" est indépendante de `SWING_STRUCTURE` et peut être menée dès que l'Étape 5 est GO.
- Prompt détaillé : [`docs/prompts/prompt-implementation-trend-unifie-etape6-correlation-adx-regression.md`](prompt-implementation-trend-unifie-etape6-correlation-adx-regression.md).
- **Résultat** : runner manuel `TrendComboCorrelationManualRunnerTest` (`@Disabled`, DB dev réelle en lecture seule), BTC D1 2017-09-15 → 2026-09-23 (3 296 bougies). (a) ADX vs r² : 0,01 (7j) / 0,22 (14j) / **0,60 (30j)** / 0,15 vs `regressiveTrendScore` agrégé → pas de double comptage au niveau du score final, redondance localisée sur la fenêtre longue. (b) 30,4 % de désaccord de signe régime/régression, concentré sur `WARNING_*` (37-42 %) vs régimes confirmés (16-23 %, désaccords faibles en magnitude). Exploité par la spec de composition ([`spec-composition-trend-unifie.md`](../etudes/spec-composition-trend-unifie.md)).

## Étape 7 — Spécification de la composition + walk-forward — exécutée (2026-09-24), en discussion

- Prompt : [`prompt-implementation-trend-unifie-etape7-specification-composition-walkforward.md`](prompt-implementation-trend-unifie-etape7-specification-composition-walkforward.md). Spec et résultats : [`docs/etudes/spec-composition-trend-unifie.md`](../etudes/spec-composition-trend-unifie.md) (§10).
- Outillage : `TrendCompositionFeatureExportManualRunnerTest` (`@Disabled`, export des briques de production D1) + `tools/calibration/trend_composition_walkforward.py` (candidates, calibration, métriques, données de visualisation), relançable à volonté.
- Résultat principal : face à la tendance réelle (ZigZag 15 % ex-post), `RegressiveTrendStrategy` seule (ou lissée EMA 5) domine chaque année ; tout gate par `SWING_STRUCTURE` dégrade l'accord, ADX n'apporte rien, aucune candidate ne détecte les ranges. La hiérarchie « régime = gate » de l'étude §8 n'est pas confirmée.
- **Décisions de Clem (2026-09-24, après visualisation)** : la Trend est portée par la régression à hystérésis (REG_H), à l'échelle des mouvements de quelques semaines ; `SWING_STRUCTURE` et ADX sont retirés de `TrendAnalyzer` ; état neutre = RANGE ; `TrendConfirmationStrategy` bascule dans le même lot. Contrat `TrendState` figé par l'Étape 8.

## Étape 8 — Trend par régression à hystérésis — ✅ fait (2026-09-24)

- Prompt : [`prompt-implementation-trend-unifie-etape8-regression-hysteresis.md`](prompt-implementation-trend-unifie-etape8-regression-hysteresis.md). Réglages figés : fenêtres 7/14/30, `slopeScaleFactor` 400, poids égaux, sans alignement, hystérésis entrée ±1/6 / sortie 0.
- `SWING_STRUCTURE` et ADX retirés de `TrendAnalyzer`/`TrendConfirmationStrategy` : la Trend est désormais portée entièrement par la régression multi-fenêtres à hystérésis. Formule de score mutualisée dans `RegressiveTrendScoreCalculator` (consommée par `RegressiveTrendStrategy` et `TrendAnalyzer`), machine à états dans `RegressionHysteresisCalculator`. Nouveau contrat `TrendState` : `regime` (`TrendRegime` UP/DOWN/RANGE, RANGE = état neutre — la nuance RANGE fine des points B/C de l'étude n'est plus un point ouvert, elle est devenue l'état par défaut), `score`, `force = |score|`, `confidence = min(1, runLength/10)` (provisoire, non calibrée), `bosJustOccurred`, `bosTimestamp`.
- `TrendConfirmationStrategy` : contrat d'entrée passe de `SWING_STRUCTURE`+`ADX` à 3x `LINEAR_REGRESSION` (périodes 7/14/30, D1), mêmes constantes par défaut que `RegressiveTrendStrategy` (mutualisées, un seul endroit). `getRequiredCandles` impose désormais `TrendAnalyzer.MIN_CANDLES` (60 = fenêtre longue 30 + warmup hystérésis 30).
- `AdxFactorCalculator` (et son test) supprimés : plus aucun consommateur après ce lot. `SwingStructureRegimeScore` conservé (toujours utilisé par `SwingStructureIndicator`, hors périmètre de ce lot).
- Scénario de non-régression obligatoire (range étendu 90j ±5% + breakout rapide +2%/j sur 10j) : régime UP atteint 2 bougies après le début du breakout (≤ 3 attendu par le prompt). Range étendu toujours instable (32 changements de régime sur 90 bougies) — limite connue et acceptée, cf. §10.3 de la spec de composition (aucune candidate testée n'isole proprement un range).
- Suite complète (665 tests) verte, hors les 2 échecs préexistants sans rapport (`DxyIndicatorCacheSharingTest`, cache TwelveData/DXY).

## Étapes suivantes anticipées (pas encore promptées, dépendent des résultats des Étapes 5/6)

- Calibration de `RegressiveTrendStrategy` (poids court/moyen/long, `slopeScaleFactor`, mapping d'alignement) en walk-forward, séparément des autres briques (ne jamais calibrer un composite sur des sous-composants non calibrés).
- Spécification précise de la fonction de composition régime/régression (hiérarchie gate/timing envisagée en §8 de l'étude) et du rôle final d'ADX, à partir des résultats de l'Étape 6.
- Formalisation de la règle RANGE avec le garde-fou identifié le 2026-09-21 (`WARNING_*` + `regressionScore` fort/croissant = candidat-retournement, jamais RANGE).
- Assemblage final du score composite, avec un scénario "range étendu + breakout rapide" comme test de non-régression obligatoire avant toute mise en prod.
- Chacune de ces étapes fera l'objet d'un prompt d'implémentation détaillé séparé au moment de l'attaquer, pas rédigé à l'avance — même méthode que le reste de ce document.

## Hors roadmap (chantiers futurs à rouvrir séparément, pas dans ce lot)

- Exposition MCP du `TrendAnalyzer` (nouveau `IndicatorType`/tool, cf. Q3).
- Consommation par le calculateur DCA Rainbow (sélection du paramétrage UP/DOWN/RANGE + modulation Force/Confiance) — dépend de ce chantier mais est un chantier de branchement à part, pas une étape de cette roadmap.
- `REGRESSION_CHANNEL` (en tant que brique de production, distincte de `LINEAR_REGRESSION`) / `RAINBOW_STATE` / `StructuralReversalStrategy` (spec d'origine, périmètre inchangé).
- Instance "micro" de `SWING_STRUCTURE`.
- Supertrend comme stop-loss suiveur pour la couche Decision/order-sizing (`StrategyType.RISK`, non implémenté) — idée validée par Clem le 2026-09-21, explicitement hors de ce chantier Trend (cf. mémoire projet).

## Prochaine étape

Roadmap Trend unifié close (Étapes 1 à 8 toutes ✅, Étape 2 sans objet). Prochain chantier, hors roadmap (cf. section ci-dessus) : branchement du calculateur DCA Rainbow sur `TrendAnalyzer` (sélection du paramétrage UP/DOWN/RANGE + modulation Force/Confiance), à promptée séparément.
