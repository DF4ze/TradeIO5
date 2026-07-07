# Spec — Structure de marché, Canal de régression, Rainbow à mémoire

## 0. Objectif et méthode

Cette spec formalise une stratégie réelle (utilisée manuellement sur TradingView) pour l'intégrer à la chaîne `Indicator → Strategy → Opinion` de TradeIO5. Elle prolonge `docs/etude-indicateurs-strategies-opinions.md`, dont la feuille de route est aujourd'hui largement réalisée : `ADX`/`ATR`/`BOLLINGER`/`OBV` existent dans `IndicatorType`, le bug de conflit de `StrategyAggregator` est corrigé, `DefaultMarketOpinion` est en scope `LOCAL`, `TrendConfirmationStrategy` (EMA+ADX+RSI) est branchée, et `GlobalMarketOpinion`/`ExternalMarketOpinion` existent. Cette spec ajoute la brique "structure de marché" que l'étude avait volontairement reportée en dernier faute de cas d'usage concret — on en a maintenant un.

Trois briques à construire, dans l'ordre de dépendance :

1. **`SWING_STRUCTURE`** — détection de plus hauts/plus bas confirmés (filtre adaptatif ATR), régime BULL/BEAR/WARNING, niveaux S/R.
2. **`REGRESSION_CHANNEL`** — canal de régression linéaire ancré sur un retournement détecté par `SWING_STRUCTURE`.
3. **État de franchissement du Rainbow** (`RAINBOW_STATE`) — mémoire de sortie/attente/re-rentrée, à partir du `RainbowSmaIndicator` déjà existant.

Puis une Strategy qui combine les trois, branchée dans `DefaultMarketOpinion` (`LOCAL`) exactement comme `TrendConfirmationStrategy` aujourd'hui — pas de nouvel `OpinionScope` nécessaire.

Convention de lecture : chaque section distingue ce qui est **acquis** (code existant, vérifié) de ce qui est **proposé** (à valider avant codage) et liste les **points ouverts** en fin de section plutôt que de trancher à la place de l'utilisateur.

## 1. Indicateur `SWING_STRUCTURE`

### 1.1 Rôle

Remplacer le tagging visuel manuel ("plus bas que le dernier plus bas taggué, puis je vérifie si ça continue") par un algorithme déterministe, avec un filtre de bruit basé sur l'amplitude récente du marché (ATR) plutôt qu'un seuil fixe.

### 1.2 Paramètres

| Paramètre | Rôle | Défaut proposé |
|---|---|---|
| `atrPeriod` | période de l'ATR utilisé comme référence de volatilité | 14 |
| `atrMultiplier` | un pivot n'est confirmé que si le prix s'écarte du dernier extrême candidat d'au moins `atrMultiplier × ATR` | à calibrer (point ouvert 1.6) |

### 1.3 Dépendance

`SWING_STRUCTURE` déclare une dépendance formelle sur `ATR` via le mécanisme `DependentIndicator`/`IndicatorDependency` déjà utilisé par `RainbowSmaIndicator` (qui dépend de `SMA`) — pas de réimplémentation du calcul de True Range/ATR en interne.

### 1.4 Algorithme (résumé)

Parcours chronologique de la série (`context.marketDataset().getMarketDatas()`, triée par `timestamp`) :

- maintenir un "candidat bas courant" et un "candidat haut courant" (prix + index) ;
- à chaque bougie, mettre à jour le candidat si un nouvel extrême local est atteint ;
- confirmer le candidat bas comme **pivot low** dès que le prix remonte d'au moins `atrMultiplier × ATR(à cet instant)` au-dessus de ce candidat (symétrique pour un **pivot high**) ;
- à chaque nouveau pivot confirmé, comparer au pivot du même type précédent : plus haut/plus bas → classification HH/LH (highs) et HL/LL (lows) ;
- régime courant :
  - **BULL confirmé** : dernier pivot high = HH **et** dernier pivot low = HL ;
  - **BEAR confirmé** : dernier pivot high = LH **et** dernier pivot low = LL ;
  - **WARNING_BULL_BREAK** : on était en BEAR, un pivot vient de casser le schéma bear (ex: HL au lieu de LL) mais l'autre jambe (highs) n'a pas encore confirmé ;
  - **WARNING_BEAR_BREAK** : symétrique, on était en BULL et une jambe casse ;
  - **UNDEFINED** : historique insuffisant pour statuer (moins de 2 pivots de chaque type).

C'est un état intermédiaire assumé, pas un défaut à corriger : un warning non confirmé par la jambe manquante doit rester lisible comme tel (cf. exemple réel du 19 et du 25 juin où seule une jambe cassait à chaque fois).

### 1.5 Sortie (`IndicatorResult`)

Contrainte réelle du modèle actuel (vérifiée dans `IndicatorResult.java`) : seulement `value: Double`, `min/max: Double`, `values: Map<String,Double>` — pas de type énuméré ni de date/Instant natif. Encodage proposé :

- `value` : code numérique du régime — `+1` BULL confirmé, `+0.5` WARNING_BULL_BREAK, `0` UNDEFINED, `-0.5` WARNING_BEAR_BREAK, `-1` BEAR confirmé (mappable directement par `MarketOpinionHelper.scoreToConfidenceAndSignalType` si besoin) ;
- `values` : `lastSwingHigh`, `lastSwingHighEpochDay`, `lastSwingLow`, `lastSwingLowEpochDay`, `previousSwingHigh`, `previousSwingLow` (pour audit/debug), plus un nombre **fixe** de niveaux S/R les plus proches du prix courant (`sr1`..`sr4`, ordonnés par proximité).

**Point ouvert 1.6** : `atrMultiplier` par défaut. Je propose de le calibrer *après* implémentation, en rejouant l'algorithme sur l'exemple réel donné (BTC, retournement du 17 oct 2025, cassures du 19 et 25 juin 2026) et en ajustant jusqu'à obtenir les mêmes dates de pivots que le tagging manuel — logique de test comme `RainbowSmaIndicatorTest`/`AdxIndicatorTest`, pas de valeur figée a priori.

**Point ouvert 1.7** : le nombre fixe de clés `sr1..sr4` est une limitation du modèle `IndicatorResult` actuel (map à clés fixes, pas de liste dynamique). Si le besoin de S/R grandit (plus de niveaux, historique complet), il faudra étendre `IndicatorResult` — hors scope de cette spec, à ouvrir séparément si besoin.

## 2. Indicateur `REGRESSION_CHANNEL`

### 2.1 Rôle

Canal de régression linéaire (droite + bandes), ancré non pas sur une fenêtre glissante de N bougies (comme tous les indicateurs actuels), mais sur la date du dernier retournement confirmé par `SWING_STRUCTURE` — pour reproduire l'ancrage "tiré depuis le 17 Oct 2025" que tu fais à la main sur TradingView.

### 2.2 Calcul

Sur les points (index, close) depuis l'ancrage jusqu'à la bougie courante :

1. régression linéaire (moindres carrés) → droite `ŷ(x) = a + b·x` ;
2. écart-type des résidus `close - ŷ` sur la même fenêtre → `σ` ;
3. bornes : `upper = ŷ_now + k·σ`, `lower = ŷ_now - k·σ` (`k` = `deviationMultiplier`, défaut TradingView = 2.0, laissé réglable) ;
4. score normalisé, orienté pour rester cohérent avec la convention du reste du projet (proche du bas = signal d'achat = score positif) :

```
score = clamp( -(close - ŷ_now) / (k · σ), -1, 1 )
```

0 au centre, tendant vers +1 en touchant la bande basse (achat), vers -1 en touchant la bande haute (vente) — exactement la pondération progressive que tu décrivais.

### 2.3 Deux instances (macro / micro)

Pas un nouveau paramètre sur le même calcul : **deux jeux de paramètres `SWING_STRUCTURE` différents** (un `atrMultiplier` large pour ne capter que les retournements majeurs, un plus fin pour les retournements mineurs), chacun alimentant sa propre instance de `REGRESSION_CHANNEL` — même pattern que les deux `EMA` (rapide/lente) de `TrendConfirmationStrategy`, distinguées par leurs paramètres plutôt que par le type.

### 2.4 Dépendance

`REGRESSION_CHANNEL` déclare une dépendance formelle sur `SWING_STRUCTURE` (index/epoch day du dernier retournement confirmé comme point d'ancrage), via le même mécanisme `IndicatorDependency`.

### 2.5 Sortie

`value` = score normalisé [-1,1] ; `values` = `center`, `upper`, `lower`, `sigma`.

**Point ouvert 2.6** : confirmer que "macro" et "micro" doivent être deux détections de structure indépendantes (deux `atrMultiplier`) plutôt qu'une seule détection avec deux niveaux de lecture — la formulation actuelle ("une large qui détecte BULLMARKET/BEARMARKET, une plus fine pour la précision") est compatible avec les deux lectures, mais l'implémentation diffère.

## 3. État de franchissement du Rainbow (`RAINBOW_STATE`)

### 3.1 Rôle

Le `RainbowSmaIndicator` existant (`SMA` + 5 bandes en %, cf. conversation précédente) reste inchangé pour la lecture ponctuelle (`get_indicator` continue de fonctionner à l'identique, rétrocompatible). Le besoin ici est différent : mémoriser si le prix est **sorti** du rainbow (clôture au-delà de la bande extrême) et n'émettre un signal qu'au moment où il **re-rentre** — logique à mémoire, pas un simple test de seuil sur la dernière bougie.

Proposition : un nouveau `IndicatorType` dédié (`RAINBOW_STATE`) plutôt qu'un paramètre booléen sur `RainbowSmaIndicator` — cohérent avec le principe "un indicateur = un calcul" déjà respecté partout ailleurs dans le projet (`AdxIndicator`, `AtrIndicator`, etc. sont chacun des calculs uniques).

### 3.2 Algorithme

Dépend de `RAINBOW` (mêmes paramètres `period`/`percup1..3`/`percdown1..2`) via `IndicatorDependency`, mais recalculé à **chaque bougie de l'historique fourni** (pas seulement la dernière) :

- état initial `IN_RANGE` ;
- passage à `WAITING_BUY` dès qu'une clôture passe sous `percdown2` (dernière bande basse) alors qu'on n'y était pas déjà ;
- passage à `WAITING_SELL` symétrique côté haut ;
- retour à `IN_RANGE` (avec flag `justReentered = 1` sur cette seule bougie) dès qu'une clôture repasse à l'intérieur des bandes depuis un état `WAITING_*`.

### 3.3 Sortie

`value` : code d'état (`-1` WAITING_BUY, `0` IN_RANGE, `+1` WAITING_SELL) ; `values` : `justReenteredBuy` (0/1), `justReenteredSell` (0/1), `barsInWaitingState` (ancienneté de l'attente, utile pour pondérer la confiance).

**Point ouvert 3.4** : à valider — nouveau `IndicatorType` séparé (recommandé) vs extension paramétrée de `RainbowSmaIndicator` existant.

## 4. Strategy combinée

### 4.1 Entrées

Quatre `IndicatorSpec` (comme les 4 entrées de `TrendConfirmationStrategy`) : `SWING_STRUCTURE` macro, `REGRESSION_CHANNEL` macro, `REGRESSION_CHANNEL` micro, `RAINBOW_STATE`. `StrategyType.ENTRY` (le stop -10% reste hors Strategy — `StrategyType.RISK` volontairement non implémenté, sizing/stop à traiter en couche Decision, cf. javadoc de l'enum).

### 4.2 Logique de score (fidèle à l'exemple donné)

1. **Le signal actionnable ne vient que de `RAINBOW_STATE`** : score de base = 0 tant qu'on est `IN_RANGE` ou en attente (`WAITING_*` sans `justReentered`) ; score fort (signe selon le sens) uniquement sur `justReenteredBuy`/`justReenteredSell`.
2. **Pondération par le régime macro** (`SWING_STRUCTURE` large) : en régime BEAR confirmé, un signal de vente est pris avec une confiance pleine, un signal d'achat est atténué (ou nécessite une confirmation supplémentaire) — asymétrie explicitement voulue ("en bear on vend facilement, en bull on achète facilement").
3. **Pondération par la position dans le canal macro** (`REGRESSION_CHANNEL` large) : le score normalisé (déjà dans [-1,1]) sert de multiplicateur de confiance — proche du centre, un signal Rainbow est atténué ("de toute façon on est en plein milieu de la tendance régressive : pondération 0... attente", exactement ton exemple du 29 janvier).
4. **Confirmation en deux temps côté sortie** (exemple de la vente) : un premier signal (prix au-dessus du milieu du canal micro et au-dessus du canal macro) augmente la confiance sans déclencher ; le franchissement de la bande haute suivante du Rainbow (`percup2`) renforce encore ; la re-rentrée dans le Rainbow reste le déclencheur final.

**Point ouvert 4.3** : nom de la Strategy — tu as dit ne plus vouloir "Rainbow SMA" comme nom vu qu'elle combine plusieurs indicateurs. Propositions : `StructuralReversalStrategy`, `RegimeReversalStrategy`, `TrendChannelReentryStrategy` — à valider ou à remplacer par ton propre nom.

## 5. Opinion et exposition MCP

Pas de nouvel `OpinionScope` : cette Strategy s'agrège dans `DefaultMarketOpinion` (`LOCAL`) via `StrategyAggregator`, au même titre que `TrendConfirmationStrategy` — deux `StrategySignal` `LOCAL` agrégés ensemble.

Côté MCP (`TreeAnalysisMcpTools`), l'architecture actuelle est déjà générique (résolution par `IndicatorType`/`StrategyType` via les registries) : ajouter les 3 nouveaux `IndicatorType` suffit à les rendre appelables via `get_indicator`, et la nouvelle Strategy sera automatiquement utilisable via `evaluate_strategy`/`get_opinion` dès qu'elle est un `@Component` implémentant `Strategy` — seules les chaînes de description des `@Tool` (littéraux listant les types) sont à mettre à jour pour rester informatives pour l'appelant LLM.

## 6. Récapitulatif des points ouverts

| # | Décision à prendre | Où |
|---|---|---|
| 1.6 | Valeur par défaut de `atrMultiplier`, à calibrer sur l'historique réel | `SWING_STRUCTURE` |
| 1.7 | Nombre fixe de niveaux S/R (`sr1..sr4`) vs extension du modèle `IndicatorResult` | `SWING_STRUCTURE` |
| 2.6 | Une détection de structure partagée ou deux détections indépendantes (macro/micro) | `REGRESSION_CHANNEL` |
| 3.4 | Nouveau `IndicatorType` dédié vs extension de `RainbowSmaIndicator` | `RAINBOW_STATE` |
| 4.3 | Nom final de la Strategy combinée | Strategy |

## 7. Ordre d'implémentation suggéré

1. `SWING_STRUCTURE` seul, avec tests unitaires rejouant l'exemple réel (BTC oct 2025 → juin 2026) pour calibrer `atrMultiplier` (point 1.6).
2. `RAINBOW_STATE`, testable indépendamment (machine à état simple, pas de dépendance à `SWING_STRUCTURE`).
3. `REGRESSION_CHANNEL`, une fois `SWING_STRUCTURE` calibré (dépend de son ancrage).
4. Strategy combinée + branchement dans `DefaultMarketOpinion`.
5. Mise à jour des descriptions `@Tool` dans `TreeAnalysisMcpTools`.
