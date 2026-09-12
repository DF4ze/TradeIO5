# Prompt d'implémentation — DCA intelligent Rainbow, V0 (backtest pur)

## Contexte

TradeIO5 a déjà un simulateur DCA à montant fixe (`DcaCalculatorService`, tool MCP `calculate_dca`,
documenté dans `docs/etudes/etude-dca-tool-mcp.md`). Objectif de ce lot : un second simulateur DCA,
indépendant, où le montant/l'action (achat x3/x2/x1/x0.5/pas d'achat/vente) dépend de la position du
prix dans un indicateur "Rainbow" (SMA + bandes en %, `RainbowSmaIndicator`, `IndicatorType.RAINBOW`
— déjà existant mais jamais consommé par une Strategy, ne pas le modifier).

**Avant de commencer**, lire dans l'ordre :
1. `docs/CODING_RULES.md`
2. `docs/etudes/etude-dca-tool-mcp.md` (tout, y compris la section 10 ajoutée le 2026-09-12)
3. Mémoire projet Cowork `tradeio5_dca_rainbow_decoupled_architecture_2026-09-12.md` (si accessible
   dans cette session) — historique complet des décisions qui ont mené à ce prompt.

## Périmètre V0 — strict

**Dans le scope** : un backtest historique BTC, règle Rainbow pure ci-dessous, comparaison avec le
DCA fixe existant.

**Hors scope, explicitement** — ne pas coder, ne pas anticiper :
- Pas de lecture de wallet réel (viendra en V1).
- Pas de curseur d'exposition stablecoin/crypto (V2).
- Pas de frein basé sur le risque Decision/Scenario (V3, pas backtestable sur l'historique de toute
  façon — `Decision.confidence` n'existe que depuis peu).
- Pas d'exécution réelle d'ordre (aucune méthode d'ordre n'existe dans `ProviderApiClient`/
  `BinanceApiClient` aujourd'hui — `ProviderApiService.buy/sell` est un stub `return true` ; chantier
  à part entière, pas dans ce lot).
- Pas de multi-actif, pas de table de config DB pour les paramètres d'indicateur (BTC seul, valeurs
  en dur dans la requête de backtest).
- Pas de nouveau tool MCP ni de nouvel endpoint REST (cf. section "Comment lancer le backtest").

## Règle exacte (fait foi, ne pas réinterpréter)

Simplification V0 assumée : **tout en résolution journalière (D1)**, y compris le trailing stop et
le franchissement "garde-fou" — pas de granularité intrabar/H1 pour les déclenchements dans ce lot.

Paramètres de départ (réglables en paramètres d'appel, pas en dur dans le code) :
- SMA period = 20 (jours)
- Bornes % autour de la SMA : `percdown2 = -7`, `percdown1 = -3.5`, `percup1 = +3.5`, `percup2 = +7`,
  `percup3 = +12.5`
- Trailing stop achat = 5%
- Fraction vendue = 1/4 de la position
- Cooldown après vente = 3 jours (achat DCA désactivé pendant 3 jours après toute vente)
- Cadence DCA = 1 jour (paramétrable)
- Montant de base = paramètre de la requête (pas de valeur métier imposée)

Zones (prix vs SMA du jour) → multiplicateur, zones intermédiaires = application directe, pas
d'armement :
| Zone | Multiplicateur |
|---|---|
| percdown1 → SMA | x1 |
| SMA → percup1 | x0.5 |
| percup1 → percup2 | pas d'achat |

Zones extrêmes = machine à état (ARMÉ → DÉCLENCHÉ), avec cooldown :

**Achat renforcé (zone sous percdown2)**
- `activation` : clôture du jour < percdown2 → état ARMÉ_ACHAT (si pas déjà armé).
- `déclenchement` (alors qu'ARMÉ_ACHAT) : clôture > percdown2 **OU** clôture > (plus bas atteint
  depuis l'armement) × 1.05 (trailing stop 5%).
- Au déclenchement : achat renforcé (multiplicateur x3 sur le montant de base), retour à l'état
  NONE, le multiplicateur de la zone courante reprend ensuite normalement au tick suivant.
- Tant qu'ARMÉ_ACHAT et pas déclenché : **pas d'achat ce jour-là** (le DCA normal est en pause,
  c'est tout l'intérêt de l'armement — ne pas acheter bêtement à chaque tick alors qu'on est en
  train d'évaluer si c'est un creux ou une chute qui continue).

**Vente (zone au-dessus de percup3)**
- `activation` : clôture du jour > percup3 → état ARMÉ_VENTE (si pas déjà armé).
- `déclenchement` (alors qu'ARMÉ_VENTE) : clôture < percup3 **OU** clôture < percup2.
- Au déclenchement : vente de 1/4 de la position détenue (simulée dans le backtest), retour à
  l'état NONE, démarre le cooldown de 3 jours (achat DCA désactivé, vente reste possible).
- Tant qu'ARMÉ_VENTE et pas déclenché : pas de vente ce jour-là, mais les achats normaux des zones
  intermédiaires restent actifs si le prix oscille en dessous entre-temps (seul un déclenchement
  effectif de vente ouvre le cooldown).

**Cooldown** : un compteur de jours restants, décrémenté chaque jour ; tant qu'il est > 0, aucun
achat (renforcé ou normal) n'a lieu, quelle que soit la zone ; la vente reste possible.

## Accès aux données — pattern à réutiliser, ne rien réinventer

1. Fetch H1 BTC sur `[dateDébut − 20 jours, dateFin]` via le client déjà injecté dans
   `DcaCalculatorService` (`@Qualifier("cachingBinanceMarketDataApiClient")`,
   `CachingMarketDataApiClient` — décorateur cache DB + fetch réseau uniquement sur les trous,
   cf. `docs/etudes/etude-cache-db-candles-h1.md`). Même pagination par blocs que
   `DcaCalculatorService` (`CHUNK_HOURS`).
2. Agrégation H1 → D1 via `Bucket` (`service/market/dataset/Bucket.java`) : `new Bucket(TimeFrame.H1,
   maxSize)`, `.append(...)` sur toute la série H1 triée, puis `.view(TimeFrame.D1, now)` pour les
   vraies clôtures journalières (open=premier H1, close=dernier H1, high=max, low=min). **Ne pas**
   passer par `MarketDatasetEngine`/`MarketDataProviderRegistry` (sélection/fallback multi-provider,
   conçu pour l'usage live, trop de machinerie pour ce besoin one-shot — cf.
   `etude-dca-tool-mcp.md` §10). Précédent direct à suivre : `tools/calibration/BucketResample.java`
   (même usage de `Bucket`, hors contexte Spring).
3. SMA20 causale sur les clôtures D1 (jamais de regard en avant — chaque jour n'utilise que les 20
   jours précédents inclus).

## Classes à créer

Package `service/dca/` (à côté de `DcaCalculatorService`) :
- `RainbowZone` : enum (`X3`, `X2`, `X1`, `X0_5`, `NO_BUY`, `SELL`) + méthode statique de
  classification `of(BigDecimal price, BigDecimal sma, bornes...)`.
- `RainbowDcaBacktestRequest` (DTO) : symbol, startDate, endDate, smaPeriod (int), percDown2/1,
  percUp1/2/3 (BigDecimal, en %), trailingStopPercent, sellFraction, cooldownDays, cadence
  (TimeFrame), baseAmount (BigDecimal).
- `RainbowDcaOccurrence` (DTO) : date, close, sma, zone, état (NONE/ARMÉ_ACHAT/ARMÉ_VENTE), action
  effectuée ce jour (NONE/BUY/SELL), montant/quantité, multiplicateur appliqué.
- `RainbowDcaBacktestResult` (DTO) : totalInvested, totalQuantity, avgPrice (pondéré), currentPrice,
  currentValue, pnl, pnlPercent, nombre d'achats renforcés/ventes déclenchés, liste des
  `RainbowDcaOccurrence`, + un second bloc avec les mêmes métriques pour un DCA fixe classique sur
  la même période (réutiliser `DcaCalculatorService.calculate(...)` comme référence de
  comparaison).
- `RainbowDcaBacktestService` : orchestration complète (fetch → agrégation → SMA → machine à état →
  agrégation résultat + comparaison).

## Comment lancer le backtest — pas de tool MCP, pas d'endpoint REST dans ce lot

Décision explicite (coût d'itération) : un tool MCP forcerait un appel LLM par essai de paramètres,
un endpoint REST demanderait de garder l'appli démarrée. À la place :

- `RainbowDcaBacktestServiceTest` (unitaire, données synthétiques construites à la main, pas de
  réseau) : couvre la classification de zone aux bornes exactes, les transitions
  armé/déclenché (achat ET vente), le calcul du trailing stop, le cooldown (achat bloqué, vente
  non-bloquée), la reprise du multiplicateur normal après déclenchement. Fait partie de la suite
  standard.
- Un test/runner séparé, **pas dans la suite par défaut** (ex: suffixe `ManualIT`, ou annoté pour
  être exclu du run standard — vérifier la convention déjà utilisée dans le projet pour ce genre de
  test, ex. `MarketDatasetEngineSpringTest`), `@SpringBootTest` (contexte réel, accès DB+réseau) :
  définit une **liste** de jeux de paramètres (pas un seul), lance `RainbowDcaBacktestService` pour
  chacun sur BTC réel, logue un tableau comparatif (paramètres → PnL/prix moyen vs DCA fixe). Un
  seul run Maven donne plusieurs résultats ; la liste de combos doit être facile à éditer à la main
  (tableau de records ou équivalent, pas un format à reparser).

## Règles de code à respecter

- `docs/CODING_RULES.md` : ne pas coder ce dont on n'a pas besoin (pas de généralisation anticipée
  au-delà de ce prompt) ; tester le plus simple d'abord.
- Style déjà établi dans le projet (conventions Clem) : `isEmpty()`/`getFirst()`/`getLast()` plutôt
  que `size()`/`get(0)` sur les collections ; `assertTrue()`/`assertFalse()` direct plutôt que
  `assertEquals(bool, ...)` en test ; pas de variable locale à usage unique quand un retour direct
  suffit ; toute constante métier dupliquée entre classes doit être mutualisée.
- `BigDecimal` partout pour les montants/prix/quantités (cohérent avec le reste du modèle, précision
  30/échelle 10 comme `Transaction.quantity`/`Transaction.price`).

## Vérification

Build + suite complète via l'opération `test:tradeio-5` (ssh-gateway, machine Windows réelle — le
sandbox Cowork n'a ni Maven ni réseau, cf. `docs/CODING_RULES.md`/mémoire projet
`tradeio5_sandbox_build_limits`). 0 régression attendue sur la suite existante. Puis lancer le
test/runner manuel une fois pour obtenir de vrais chiffres BTC et les rapporter.

## Livrable attendu

Les classes ci-dessus, les deux tests, confirmation de la suite verte, et le tableau de résultats du
run manuel sur BTC (au moins la combinaison de paramètres par défaut donnée plus haut).
