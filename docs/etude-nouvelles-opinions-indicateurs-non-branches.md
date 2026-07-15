# Étude — Nouvelles Opinions à partir des indicateurs non branchés (2026-07-15)

> **Implémenté le 2026-07-15** ("implémente tout ça") avec les recommandations de ce document
> retenues telles quelles pour les 5 décisions §7 (STABLECOIN_MARKET_CAP en GLOBAL, MACRO en
> `symbol=null`, extension `values.previous` sur DXY/SP500/NASDAQ, ratio relatif au volume pour le
> seuil de liquidations, ETF_FLOW différé). Détail du branchement et des tests :
> `docs/etat-des-lieux-indicateurs-strategies-opinions.md` §8.

Demande de Clem : plus d'`Opinion`, en utilisant les indicateurs codés/testés mais sans consommateur
(`STABLECOIN_MARKET_CAP`, `ORDER_BOOK`, `ETF_FLOW`, `LIQUIDATIONS`, `DXY`/`SP500`/`NASDAQ` —
voir `docs/etat-des-lieux-indicateurs-strategies-opinions.md` §2-4), en réfléchissant aux
**meilleures combinaisons** plutôt qu'à un branchement mécanique 1 indicateur = 1 Opinion. C'est
exactement la décision de conception que le rapport du 09/07 laissait ouverte (§7, item 5) : à
trancher avant de coder. Ce document tranche, avec les alternatives écartées et pourquoi.

Principe directeur : regrouper par **nature du signal**, pas par lot d'implémentation. Quatre
familles se dégagent dans les 7 indicateurs concernés :

| Famille | Indicateurs | Nature |
|---|---|---|
| Macro TradFi (risk-on/risk-off) | DXY, SP500, NASDAQ | Cross-asset, sans symbole |
| Liquidité crypto-native | STABLECOIN_MARKET_CAP | Sans symbole |
| Microstructure marché | ORDER_BOOK, LIQUIDATIONS | Par symbole, court terme |
| Flux institutionnel fragile | ETF_FLOW | Par asset (BTC/ETH), best-effort |

## 1. Scinder `GLOBAL`/`MACRO` maintenant — le moment identifié par l'étude précédente est arrivé

`docs/etude-extension-risk-macro-external.md` §3.3 recommandait explicitement de ne pas séparer
`GLOBAL` et `MACRO` "tant qu'un deuxième indicateur macro-économique réel n'existe pas". DXY/SP500/
NASDAQ sont précisément ça — bloqués uniquement par une clé Twelve Data à créer (5 min, §1 état des
lieux). `OpinionScope.MACRO` existe déjà dans l'enum, inutilisé (confirmé par grep — seule mention
hors doc : un test `TreeAnalysisFacadeTest` qui vérifie qu'aucune `MarketOpinion` n'y est enregistrée).

Répartition proposée, qui respecte la distinction déjà écrite en commentaire dans l'enum
(`GLOBAL, // macro / marché global` vs `MACRO, // économie, liquidité, taux`) :

- **`GLOBAL`** reste "sentiment du marché crypto" : `FEAR_GREED` (déjà branché) + `STABLECOIN_MARKET_CAP`
  en complément (§3 ci-dessous). Les deux sont crypto-natifs, sans équivalent TradFi.
- **`MACRO`** (nouveau, activé) : `DXY` + `SP500` + `NASDAQ`, la toile de fond TradFi qui influence le
  risk appetite général, indépendamment du sentiment propre à la crypto (§2 ci-dessous).

Ce choix contredit légèrement le rapport du 09/07 qui suggérait `STABLECOIN_MARKET_CAP` comme
"candidat naturel pour `GlobalMarketOpinion`" — je le confirme malgré la définition de l'enum qui
range plutôt "liquidité" du côté `MACRO` : la capitalisation stablecoin est une mesure de liquidité
*crypto-native* (capital déjà entré dans l'écosystème, prêt à acheter), pas une mesure macro-économique
classique (taux, indices actions) — elle est structurellement plus proche de Fear & Greed (les deux
mesurent l'état du marché crypto lui-même) que de DXY (qui mesure un marché externe qui *influence*
la crypto). Point à valider avec Clem si le découpage inverse est préféré.

## 2. Nouvelle Opinion `MACRO` — `RiskAppetiteStrategy` (DXY + SP500 + NASDAQ)

### 2.1 Le problème à résoudre avant la formule : ces 3 indicateurs n'exposent pas de delta

Contrairement à `OPEN_INTEREST` (dont le contrat a été volontairement changé au Lot 2 pour exposer
`values={current, previous}`, précisément pour permettre un calcul de delta dans
`MovementQualificationStrategy`), `DxyIndicator`/`Sp500Indicator`/`NasdaqIndicator` n'exposent
aujourd'hui qu'une valeur brute (`value`, + `values.lastTradeTime` pour les deux derniers). Sans
delta, impossible de savoir si le dollar se renforce ou si le NASDAQ monte ou baisse — seulement leur
niveau absolu, inutilisable sans une bande de référence arbitraire (que rien ne justifie de calibrer
à la main).

**Proposition (même précédent que `OPEN_INTEREST`)** : étendre le contrat des 3 indicateurs pour
exposer un `previousClose`/`percentChange` journalier :
- `SP500`/`NASDAQ` : Twelve Data `/quote` retourne déjà nativement `previous_close` et
  `percent_change` dans sa réponse brute (confirmé par la doc Twelve Data) — `Sp500Indicator`/
  `NasdaqIndicator` ne les mappent simplement pas encore vers `IndicatorResult.values`. Changement
  mineur, pas de nouvel appel réseau.
- `DXY` : plus délicat car synthétique (formule sur 6 paires forex, pas de ticker direct). Twelve
  Data `/quote` expose aussi `previous_close` par paire — la même formule DXY peut être recalculée une
  seconde fois avec les 6 `previous_close` pour obtenir un `DXY_previous` cohérent. Coût : aucun appel
  réseau supplémentaire (déjà dans la réponse `/quote` de chaque paire), juste un second passage dans
  la formule déjà écrite.

### 2.2 Formule proposée

```
dxyChangePct    = (dxy.value - dxy.values.previous) / dxy.values.previous
sp500ChangePct  = (sp500.value - sp500.values.previous) / sp500.values.previous
nasdaqChangePct = (nasdaq.value - nasdaq.values.previous) / nasdaq.values.previous

normalize(pct, scale) = clamp(-1, 1, pct / scale)   // même esprit que adxFactor (interpolation bornée)

score = clamp(-1, 1,
      -0.3 * normalize(dxyChangePct, P_DXY_DAILY_SCALE)       // dollar fort = risk-off = négatif
    +  0.3 * normalize(sp500ChangePct, P_EQUITY_DAILY_SCALE)
    +  0.4 * normalize(nasdaqChangePct, P_EQUITY_DAILY_SCALE) // NASDAQ pondéré plus haut : beta
                                                                 // historiquement plus proche de la
                                                                 // crypto que SP500 (tech/risk assets)
)
```

`P_DXY_DAILY_SCALE` (défaut proposé 0.5%, le DXY bouge historiquement moins que les indices actions
en une journée), `P_EQUITY_DAILY_SCALE` (défaut proposé 1.0%) — seuils **à calibrer**, pas de donnée
empirique aujourd'hui pour les valider (même réserve que tous les seuils `P_*` des Strategy
existantes, explicitement documentés comme "point de départ à ajuster empiriquement" pour
`MovementQualificationStrategy`).

### 2.3 Gestion des cas invalides et de la fraîcheur

- Cohérent avec la philosophie déjà en place (`StrategyAggregator` : "un seul signal invalide remet
  `totalScore` à 0", `DxyIndicator` : "pas de valeur par défaut arbitraire") : si un seul des 3
  indicateurs est `invalid()`, toute la Strategy retourne invalide plutôt qu'une moyenne sur 2/3.
- `values.lastTradeTime` (SP500/NASDAQ) sert de garde-fou fraîcheur : si le marché actions est fermé
  (nuit US, week-end) et que la dernière transaction date de plus de `P_STALE_QUOTE_HOURS` (défaut
  18h), la confidence est atténuée (facteur multiplicatif, pas un `invalid()` brutal) — même logique
  que `computeSentimentShiftDampening` de `GlobalMarketOpinion` (décroissance continue, jamais un 0
  brutal). Un `computeStalenessDampening(lastTradeTime, now, staleThresholdHours)` réutilisable dans
  `MarketOpinionHelper` couvrirait ce besoin.

### 2.4 Scope et branchement

`MacroMarketOpinion implements MarketOpinion` (comme `GlobalMarketOpinion`, pas `AbstractMarketOpinion`
— une seule Strategy composite, pas d'agrégation multi-Strategy nécessaire), `getScope() ==
OpinionScope.MACRO`, `symbol = null` — `MarketContext` tolère déjà un symbole absent (confirmé,
`etude-extension-risk-macro-external.md` §3.1), c'est le même patron que `GlobalMarketOpinion`.

**Décision à trancher explicitement (le rapport du 09/07 la pointait déjà)** : avec `symbol = null`,
le `ScenarioEvent` MACRO ne rentrera **jamais** dans l'arbitrage par unanimité du `DecisionEngine`
(`onScenarioEvent` court-circuite si `event.getSymbol().isEmpty()`, confirmé dans le code actuel) —
MACRO resterait un "signal d'ambiance" consultable via `get_opinion`, jamais un veto/confirmation sur
une Decision LOCAL. Je recommande de démarrer ainsi (même statut que GLOBAL aujourd'hui) : c'est le
chemin le moins risqué, et le jour où la conviction "MACRO doit pouvoir bloquer un LOCAL bullish" est
validée sur des cas réels, il suffira de faire tourner l'opinion par symbole (le mécanisme
`ScenarioKey(owner, type, symbol, scope)` supporte déjà la coexistence de scopes différents sur un
même symbole sans écrasement silencieux — ce point précis a été corrigé depuis l'étude qui l'avait
soulevé).

## 3. `GlobalMarketOpinion` enrichie — `STABLECOIN_MARKET_CAP`

Contrairement à DXY/SP500/NASDAQ, `StablecoinMarketCapIndicator` expose déjà `values={total,
totalPrevDay, totalPrevWeek, totalPrevMonth}` — aucun changement de contrat nécessaire, c'est
directement exploitable (le rapport le confirme : "meilleur rapport effort/valeur de toute la
liste").

```
stablecoinWeeklyGrowthPct = (total - totalPrevWeek) / totalPrevWeek
stablecoinScore = clamp(-1, 1, stablecoinWeeklyGrowthPct / P_STABLECOIN_WEEKLY_SCALE)  // défaut 3%

finalScore = 0.6 * fearGreedScore + 0.4 * stablecoinScore
```

Poids 60/40 en faveur de Fear & Greed : c'est le signal déjà en place et dont la règle de
dampening a été affinée (`computeSentimentShiftDampening`), le stablecoin score est neuf et doit
faire ses preuves avant de peser autant. Le dampening existant continue à s'appliquer uniquement à la
composante Fear & Greed (pas de raison de l'étendre au stablecoin score sans données empiriques
justifiant un comportement similaire — une chute brutale de capitalisation stablecoin est un
signal *rare et déjà fort en soi* — événement de rédemption massive — pas un signal contrarian à
atténuer comme Fear & Greed en zone extrême).

Reste dans `GlobalMarketOpinion` (pas de migration vers `AbstractMarketOpinion`) : cohérent avec la
conclusion déjà actée (`etude-extension-risk-macro-external.md` §4.2) — un signal externe sans
symbole/bougies ne bénéficie pas du patron `Strategy`/`StrategyAggregator`, la lecture + combinaison
directe reste le patron le plus simple.

## 4. Nouvelle Strategy `LOCAL` — `OrderFlowStrategy` (ORDER_BOOK + LIQUIDATIONS)

Ce sont les deux seuls indicateurs non branchés qui sont **par symbole** — logique de les combiner
entre eux plutôt qu'avec les signaux globaux/macro ci-dessus, et logique de les brancher dans
`DefaultMarketOpinion` (scope `LOCAL`) aux côtés de `TrendConfirmationStrategy` et
`MovementQualificationStrategy` (concaténation de `StrategyKey`, patron déjà documenté dans
`MarketOpinionParametersFactory`).

### 4.1 Ce qu'ils mesurent et pourquoi les combiner

- `ORDER_BOOK` (`values.imbalance` ∈ [-1,1]) : positionnement **actuel**, instantané, carnet Binance.
- `LIQUIDATIONS` (`values={long, short, total}`, somme sur `windowHours`, défaut 24h) : flux **forcé
  récent**, Coinalyze.

Seuls, chacun est bruité (`ORDER_BOOK` — snapshot sans mémoire ; `LIQUIDATIONS` — événement agrégé sans
granularité fine). Combinés, ils répondent à une question que ni `TrendConfirmationStrategy` (EMA/ADX/
RSI, tendance) ni `MovementQualificationStrategy` (OI/Funding/OBV, positionnement dérivés) ne couvrent :
*le flux forcé récent est-il en train de se reconstruire dans le même sens, ou le carnet montre-t-il
un épuisement ?*

### 4.2 Logique (même patron à 3 cas explicites que `MovementQualificationStrategy`)

```
priceDirection = sign(closeNow - closeLookback)   // réutilise P_PRICE_LOOKBACK_CANDLES, même pattern
liquidationSkew = (long - short) / total           // >0 : plutôt des longs liquidés récemment
bookAligned = sign(imbalance) == priceDirection    // carnet dans le sens du mouvement récent
```

1. **Flush confirmé** (continuation) : cascade significative dans le sens du prix (`priceDirection<0`
   et `long > short` avec `|liquidationSkew| ≥ P_LIQUIDATION_SKEW_THRESHOLD`, ou symétrique haussier)
   **et** carnet aligné (`bookAligned = true`) → score dans le sens de `priceDirection`, confidence
   pleine. Interprétation : le marché a nettoyé les positions faibles dans le sens du mouvement, et le
   carnet montre que de nouveaux ordres se reconstituent dans le même sens — continuation plus
   crédible qu'un simple signal de tendance seul.
2. **Épuisement / vigilance retournement** : même cascade que le cas 1, **mais** carnet qui montre un
   déséquilibre **opposé** au mouvement (`bookAligned = false`) → score atténué vers 0, jamais
   inversé franchement (pas de conviction directionnelle inverse, seulement un doute sur la
   continuation), confidence réduite. `reason` explicite pour tracer ce cas distinctement (comme
   `MovementQualificationStrategy` le fait pour ses 3 cas).
3. **Neutre** : `total` liquidations sous `P_LIQUIDATION_TOTAL_MIN` (pas assez de flux pour être
   significatif) ou `|imbalance| < P_ORDER_BOOK_IMBALANCE_THRESHOLD` (carnet équilibré) → score 0.

`P_LIQUIDATION_TOTAL_MIN` : seuil en valeur absolue ($) qui varie énormément selon le symbole (BTC vs
un altcoin) — **point ouvert**, à traiter soit en relatif (ratio au volume de la fenêtre, déjà
disponible dans `MarketDataset`), soit en configuration par symbole. Je recommande le ratio au volume
(plus robuste, pas de config par symbole à maintenir) mais c'est un choix à valider avant codage.

### 4.3 `getType()` et la dette déjà connue

Même réserve déjà écrite noir sur blanc pour `MovementQualificationStrategy` : ce type de signal (flux
récent + carnet) est conceptuellement un **modulateur de confiance** sur un signal directionnel
existant (Trend), pas un générateur de conviction indépendant — mais le mécanisme de modulation dédié
n'existe toujours pas dans le pipeline (`StrategyType.RISK` reste non implémenté,
`AbstractStrategy`/`StrategyAggregator` ne distinguent pas "signal" de "modulateur"). Je propose de
suivre exactement le même compromis assumé que `MovementQualificationStrategy` : déclarer `ENTRY`,
documenter la limite en javadoc, et traiter la vraie séparation signal/modulateur comme un chantier
transverse qui bénéficierait aux **deux** Strategy en même temps plutôt que de le résoudre en
bricolant une solution ad hoc pour une seule.

Nécessite la clé Coinalyze (déjà fournie et fonctionnelle depuis le 09/07, §1 état des lieux) — aucun
blocage.

## 5. `ETF_FLOW` — pourquoi je ne le branche pas dans une Strategy maintenant

Objectif de Clem : "meilleures combinaisons", pas juste maximiser le nombre d'Opinions — je prends
ça au mot ici. `EtfFlowIndicator` a une limite documentée dans son propre code (scraping HTML Farside
non versionné) et le rapport du 09/07 est explicite : "à traiter comme best-effort, **jamais** comme
un signal sur lequel une décision automatique s'appuie sans supervision." Construire une Strategy
`ENTRY` classique dessus contredirait directement cette réserve déjà actée avec Clem — ce serait le
même genre d'erreur que brancher `REJECTION_ZONE` "tel quel" sans calibration réelle (§4 état des
lieux, explicitement refusé).

Deux options pour plus tard, ni l'une ni l'autre à coder dans ce lot :
- **(a)** Attendre un vrai mécanisme de modulateur de confiance (§4.3 ci-dessus, dette partagée avec
  `MovementQualificationStrategy`/`OrderFlowStrategy`) et n'utiliser `ETF_FLOW` que comme un facteur
  qui **plafonne** la confidence d'un signal déjà existant (jamais qui la crée), restreint aux
  symboles BTC*/ETH* via `Strategy.accepts(...)`.
- **(b)** Le combiner avec `STABLECOIN_MARKET_CAP` (même famille "flux de capital") dans une
  `InstitutionalDemandStrategy` dédiée BTC/ETH — mais alors `STABLECOIN_MARKET_CAP` serait consommé
  deux fois (une fois en `GLOBAL` agrégé, une fois en `LOCAL` filtré BTC/ETH), ce qui est
  techniquement possible (rien n'empêche un indicateur d'alimenter plusieurs Strategy) mais ajoute de
  la complexité pour un gain incertain tant qu'`ETF_FLOW` lui-même n'est pas jugé assez fiable pour
  être plus qu'un modulateur.

Recommandation : ne pas coder `ETF_FLOW` dans ce lot, le garder accessible en ad hoc via
`get_indicator` comme aujourd'hui, et rouvrir la question quand le mécanisme modulateur existera.

## 6. Récapitulatif

| Indicateur | Destination proposée | Scope | Prérequis |
|---|---|---|---|
| DXY, SP500, NASDAQ | `RiskAppetiteStrategy` → `MacroMarketOpinion` (nouvelle) | `MACRO` (activé) | Clé Twelve Data + extension contrat `values.previous` sur les 3 indicateurs |
| STABLECOIN_MARKET_CAP | Enrichissement direct de `GlobalMarketOpinion` | `GLOBAL` (existant) | Aucun — déjà exploitable tel quel |
| ORDER_BOOK, LIQUIDATIONS | `OrderFlowStrategy` (nouvelle) → concaténée dans `DefaultMarketOpinion` | `LOCAL` (existant) | Clé Coinalyze (déjà fournie) |
| ETF_FLOW | Différé — pas de Strategy dans ce lot | — | Mécanisme de modulateur de confiance (dette partagée avec `MovementQualificationStrategy`) |

Résultat : **1 nouvelle Opinion** (`MacroMarketOpinion`, scope `MACRO` activé pour la première fois),
**1 Opinion existante enrichie** (`GlobalMarketOpinion`), **1 nouvelle Strategy locale** branchée dans
l'Opinion `LOCAL` existante, et **1 indicateur volontairement laissé de côté** avec justification —
plutôt que 5 branchements mécaniques dont au moins un (ETF_FLOW seul) aurait été fragile.

## 7. Décisions à valider avec Clem avant codage

1. Répartition `STABLECOIN_MARKET_CAP` → `GLOBAL` plutôt que `MACRO` (§1) — la définition de l'enum
   pencherait plutôt pour `MACRO` ("liquidité"), j'ai choisi `GLOBAL` par proximité crypto-native avec
   Fear & Greed. À confirmer.
2. `MacroMarketOpinion` avec `symbol = null` (signal d'ambiance, hors arbitrage `DecisionEngine`) dans
   un premier temps, plutôt que par-symbole dès le départ (§2.4).
3. Extension du contrat `DxyIndicator`/`Sp500Indicator`/`NasdaqIndicator` pour exposer
   `values.previous` (§2.1) — même type de changement que celui déjà fait pour `OPEN_INTEREST`.
4. Seuil `P_LIQUIDATION_TOTAL_MIN` en relatif (ratio au volume) plutôt qu'en absolu par symbole
   (§4.2) — point le plus ouvert de toute la proposition.
5. `ETF_FLOW` différé (§5) — confirmer que ce n'est pas la priorité malgré l'objectif "plus
   d'Opinions".

## 8. Ordre d'implémentation suggéré si validé

1. `STABLECOIN_MARKET_CAP` dans `GlobalMarketOpinion` (§3) — zéro prérequis, le plus rapide.
2. Créer les comptes Twelve Data (déjà dans la liste §1 état des lieux) + étendre le contrat des 3
   indicateurs TradFi (§2.1) + `RiskAppetiteStrategy`/`MacroMarketOpinion` (§2).
3. `OrderFlowStrategy` (§4) — Coinalyze déjà débloqué, seul le choix de seuil relatif/absolu (décision
   4 ci-dessus) doit être tranché avant de coder.
