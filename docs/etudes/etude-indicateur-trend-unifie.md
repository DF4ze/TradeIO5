# Étude — Indicateur de Trend unifié (Régime + Force + Confiance + BOS)

> **Mise à jour 2026-10-04** : le besoin Rainbow est désormais **2 jeux (Bull / Bear)**, plus UP/DOWN/RANGE ; le raccordement de la Trend au Rainbow reste à faire ([`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §2). Implémentation actuelle : [`architecture/09-trend.md`](../architecture/09-trend.md).

## 0. Origine et objectif

Le futur calculateur d'intensité DCA Rainbow (cf. [`tradeio5_dca_rainbow_decoupled_architecture_2026-09-12.md`](../../) / mémoire projet) a **3 paramétrages différents selon la Trend** (UP / DOWN / RANGE). Il faut donc une brique qui détermine la Trend actuelle — et c'est le déclencheur de cette étude, mais l'objectif est plus large : construire **un point unique de vérité pour la Trend**, réutilisable par n'importe quel consommateur présent ou futur, plutôt que de laisser chaque brique recalculer sa propre notion de tendance à sa façon.

## 1. Constat sur l'existant — pourquoi ne pas juste ajouter une 4e méthode

Avant cette étude, la notion de "trend" existait déjà à 3 endroits différents dans le code, sans lien entre eux :

- **`TrendType`** (`model/enumerate/market/TrendType.java`) — enum FLAT/UPTREND/DOWNTREND/VOLATILE. **Code mort** : utilisé uniquement comme sélecteur de scénario pour `InMemoryMarketDataProvider` (données de test), aucun calcul réel derrière.
- **`RegimeCalculator`** (`service/calibration/RegimeCalculator.java`) — classification ADX (Wilder) en TREND/RANGE/NEUTRAL, seuils 25/20. **Offline only** : seul appelant `ConsolidationZoneService` pour `zone_view_v2.html` (dashboard de calibration), jamais exposé en live.
- **`TrendConfirmationStrategy`** (`service/tree/strategy/impl/TrendConfirmationStrategy.java`) — Strategy `DIRECTIONAL` branchée en live (EMA rapide/lente pour le biais directionnel + ADX comme facteur d'atténuation `adxFactor` + garde-fou RSI). C'est la brique la plus proche conceptuellement de ce qu'on veut construire, mais elle est encapsulée dans le pipeline Opinion/Scenario/Decision (signal ENTER/EXIT tactique), pas réutilisable telle quelle par un consommateur externe comme le futur calculateur DCA (qui vit hors de ce pipeline, cf. architecture Rainbow actée le 2026-09-12).

**Décision** : ne pas ajouter une 4e méthode de calcul de trend. Construire un composant unique, et migrer/refactorer `TrendConfirmationStrategy` pour qu'elle consomme ce composant plutôt que de dupliquer sa propre logique ADX/EMA.

### 1.1 Ce qui reste hors périmètre (domaines différents, pas de la Trend de prix)

`MovementQualificationStrategy` (OI/Funding/OBV) et `OrderFlowStrategy` (Order Book/Liquidations) sont des Strategy `CONFIDENCE_MODULATOR` qui qualifient la fiabilité d'un mouvement déjà voté, via la microstructure dérivés/effet de levier — pas via la structure de prix. Elles ne sont pas concernées par ce chantier et restent indépendantes.

## 2. Sortie visée — riche dès la V0, simplifiée à la consommation

Décision de Clem (2026-09-18) : le calculateur de Trend reste **riche** dès la première version, même si le Rainbow DCA V0 n'en consommera qu'une version simplifiée à 3 buckets. La réduction UP/DOWN/RANGE se fait dans une **couche de mapping côté Rainbow**, pas en bridant le calculateur lui-même — pour que d'autres consommateurs futurs (dont une version ultérieure du Rainbow qui prendra en compte la modulation Force/Confiance, explicitement prévue comme évolution) puissent exploiter toute la richesse sans reconception.

Contrat de sortie envisagé (à affiner en spec d'implémentation) :

| Axe | Rôle | Brique porteuse |
|---|---|---|
| **Régime** | UP / DOWN / RANGE — et pour RANGE, nuance haussier/baissier/volatil envisagée (cf. §4) | Direction via `SWING_STRUCTURE` (BULL/BEAR), repli RANGE via régime ADX (`RegimeCalculator`) |
| **Force** | Intensité de la tendance en cours | `adxFactor` (déjà défini dans `TrendConfirmationStrategy`, à extraire) |
| **Confiance** | Fraîcheur/robustesse du régime — un régime qui vient de changer doit être moins fiable qu'un régime confirmé depuis longtemps | Run-length du régime confirmé + état `WARNING_*` de `SWING_STRUCTURE` (les deux sont portés par le même indicateur, cf. §3) |
| **BOS (Break of Structure)** | Signale qu'un changement de régime vient d'avoir lieu | Pas une sortie séparée à recoder : c'est l'événement de transition de régime de `SWING_STRUCTURE` (le passage vers/depuis `WARNING_*`) |

Point clé de conception : **BOS et Confiance ne sont pas deux mécanismes distincts** — les deux sont portés par le même indicateur `SWING_STRUCTURE`, ce qui évite une brique de plus à maintenir.

## 3. Récupération de `SWING_STRUCTURE` (`docs/etudes/spec-structure-regression-rainbow.md`)

Cette étude (rédigée le 2026-09-12, jamais implémentée à ce jour) avait posé une piste d'architecture — `SWING_STRUCTURE` / `REGRESSION_CHANNEL` / `RAINBOW_STATE` — pour une *autre* Strategy (`StructuralReversalStrategy`, timing de ré-entrée Rainbow, usage tactique ENTRY). Volontairement mise de côté le 2026-09-12 pour ne pas être rouverte sans demande explicite ("option b" de [`tradeio5_dca_rainbow_decoupled_architecture_2026-09-12.md`]).

Elle est rouverte ici (2026-09-18) car sa brique `SWING_STRUCTURE` (§1 de cette spec) répond quasi directement au besoin de Confiance/BOS de ce chantier :

- Détection de pivots hauts/bas confirmés dès la bougie qui prouve le retournement, sans seuil ni paramètre (réécriture du 2026-09-19, cf. §9.0).
- Classification HH/HL (haussier) vs LH/LL (baissier) des pivots successifs.
- Régime : **BULL confirmé** (HH+HL), **BEAR confirmé** (LH+LL), **WARNING_BULL_BREAK/WARNING_BEAR_BREAK** (une seule jambe casse le schéma en cours — régime qui change, pas encore confirmé), **UNDEFINED** (historique insuffisant).

`WARNING_*` = exactement l'état "changement de trend qui vient d'arriver, pas confirmé" recherché ici, et la transition de régime elle-même = le Break of Structure. Aucun nouveau concept à inventer : `SWING_STRUCTURE` porte nativement Direction + Fraîcheur/Confiance + BOS en un seul calcul.

**Bénéfice croisé** : implémenter `SWING_STRUCTURE` maintenant avance aussi le chantier `StructuralReversalStrategy` de la spec d'origine (même indicateur, deux consommateurs — le calculateur de Trend ici, la future Strategy de ré-entrée Rainbow plus tard). `REGRESSION_CHANNEL` et `RAINBOW_STATE` (§2 et §3 de la spec d'origine) restent hors périmètre de ce chantier — ils répondent au besoin de timing de ré-entrée, pas à celui de régime de fond.

*Note de réconciliation ajoutée le 2026-09-18 au fichier `spec-structure-regression-rainbow.md` lui-même : un renvoi vers cette étude a été ajouté en tête de ce fichier (cf. §6 ci-dessous).*

## 4. Décisions actées le 2026-09-18

- **Portée de calibration : BTC seul**, cohérent avec le Rainbow V0 déjà BTC-only. Pas de bench multi-actif (ETH/PAXG) à ce stade — des seuils calibrés BTC ne sont pas supposés se transposer sans recalibration, mais ce n'est pas le sujet de cette étude.
- **`SWING_STRUCTURE` sans paramètre de réglage** (depuis la réécriture du 2026-09-19, cf. §9.0) : une seule sémantique, confirmation de pivot à 1 bougie ; plus de distinction d'instance "macro"/"micro" par paramètre.
- **Multi-period plutôt que multi-timeframe** : Clem confirme préférer plusieurs fenêtres sur le même TF D1 (ex. 7j/15j/30j) plutôt que de changer de TF (W1 jugé trop large, H1 trop fin). Nuance actée : cette logique de fenêtre glissante s'applique aux indicateurs classiques (ADX/EMA/pente de régression, qui acceptent nativement plusieurs périodes) — **pas** à `SWING_STRUCTURE`, dont la détection de pivots a besoin de contexte long et ne se règle pas via une troncature temporelle courte (et, depuis le 2026-09-19, n'a plus aucun paramètre). `SWING_STRUCTURE` reste donc une seule instance sur tout l'historique disponible ; les 3 horizons 7j/15j/30j se jouent sur les briques ADX/EMA/régression.
- **Classification RANGE : à trancher empiriquement, pas analytiquement.** Intuition de Clem confirmée à l'œil sur graphique réel : un RANGE "pur" est rare, on observe plus souvent des variantes (range haussier, range baissier, plus ou moins volatile) qu'un état plat unique. Piste retenue pour affiner sans construire un nouveau mécanisme : réutiliser le principe de score continu de position dans un canal (`REGRESSION_CHANNEL`, §2 de la spec d'origine — score [-1,1], proche du haut = biais baissier, proche du bas = biais haussier) plutôt qu'un 3e état figé. **Pas décidé** — nécessite un test empirique sur historique réel avant de figer la classification.
- **Séquencement d'implémentation validé** : `SWING_STRUCTURE` construit et testé seul en premier (comme suggéré par la spec d'origine §7), puis harnais de calibration empirique (bench historique BTC) pour trancher les seuils/classification RANGE, et seulement ensuite assemblage du calculateur de Trend unifié final — pas de calibration analytique a priori.

## 5. Points ouverts restants

| # | Décision à prendre | Dépend de |
|---|---|---|
| A | **Sans objet** (ex-calibration `atrMultiplier`) : `SwingStructureCalculator` réécrit le 2026-09-19 sans paramètre de calibration (cf. §9.0) | — |
| B | Classification RANGE fine (haussier/baissier/volatile) — état discret supplémentaire vs score continu façon `REGRESSION_CHANNEL` | Test empirique sur historique réel |
| C | Modalité exacte de repli RANGE quand `SWING_STRUCTURE` est `UNDEFINED` ou flip-flope (ADX seul maître, ou double détection avec règle de résolution) | Résultats du point B |
| D | Refactor précis de `TrendConfirmationStrategy` pour consommer le composant unifié (remplacement complet vs simple délégation interne) | Une fois le calculateur assemblé |
| E | ~~Contrat d'API exact du composant~~ **Tranché le 2026-09-18** : pattern à 2 couches — un calculateur (service pur, DTO riche en enum) + un adaptateur `Indicator` fin qui encode en `IndicatorResult`. Appliqué à `SWING_STRUCTURE` (vrai `IndicatorType` dès cette V0) ; le calculateur de Trend unifié lui-même reste un service Java interne, pas d'exposition MCP dans cette roadmap (à rouvrir plus tard). | — |

## 6. Modification apportée à `spec-structure-regression-rainbow.md`

Un renvoi a été ajouté en tête de ce fichier le 2026-09-18, indiquant que sa brique `SWING_STRUCTURE` est désormais aussi référencée par cette étude comme brique de Confiance/BOS pour le calculateur de Trend unifié — sans autre changement du contenu de la spec d'origine (son propre périmètre, `StructuralReversalStrategy`, reste inchangé).

## 7. Suite

Roadmap rédigée le 2026-09-18 : [`docs/prompts/prompt-implementation-trend-unifie-roadmap.md`](../prompts/prompt-implementation-trend-unifie-roadmap.md). 4 étapes : (1) `SWING_STRUCTURE` complet, (2) harnais de calibration empirique BTC (devait trancher les points A et B/C ci-dessus — A devenu sans objet, B/C toujours ouverts, cf. §9.0), (3) `TrendAnalyzer` (assemblage Régime/Force/Confiance/BOS), (4) refactor `TrendConfirmationStrategy` pour consommer le composant partagé. Exposition MCP du calculateur et branchement sur le futur calculateur DCA Rainbow explicitement hors roadmap, à rouvrir séparément.

## 8. Addendum 2026-09-21 — réflexion dialectique sur la composition du signal (`RegressiveTrendStrategy`)

### Contexte

`RegressiveTrendStrategy` (cf. javadoc de la classe, posée le 2026-09-19, hors périmètre de cette étude à l'origine) introduit une seconde brique directionnelle — 3 fenêtres `LINEAR_REGRESSION` D1 (7/14/30j typiquement), combinées par `tanh(normalizedSlope×scaleFactor)×r²`, pondérées 0.2/0.3/0.5 (court/moyen/long) — indépendante de `TrendAnalyzer`/`SWING_STRUCTURE`, pas encore intégrée, pas calibrée. Clem a posé la question du "meilleur combo d'indicateurs" pour la Trend en vue d'une roadmap d'implémentation ; une réflexion dialectique (thèse / anti-thèse / jugement) a été menée le 2026-09-21 pour trancher.

### Verdict

Le combo à 3 briques — `SWING_STRUCTURE` (Régime), `LINEAR_REGRESSION` multi-fenêtres (Dynamique), ADX (Force) — est confirmé comme socle valide. Pas de 4e brique directionnelle à ajouter (le reste du catalogue `IndicatorType` a déjà été trié empiriquement et rejeté comme porteur de direction, cf. §1.1 et mémoire projet : `MovementQualificationStrategy`, `RejectionZoneIndicator`, `EtfFlowConfidenceStrategy`, Supertrend en directionnel). Pas de changement de granularité pour l'instant (rester D1 multi-period, cohérent avec §4) — le débat n'a pas produit d'argument suffisant pour introduire du W1 à ce stade, et la contrainte d'historique ci-dessous rendrait cette extension prématurée de toute façon.

**Mais le choix des briques n'est pas le point qui déterminera si le résultat est "peu bruité" — c'est la fonction de composition.** Deux points structurants restent non tranchés, identifiés par la réflexion dialectique :

1. **Fonction de composition régime/régression.** `TrendAnalyzer` (régime, discret, confirmé par BOS — donc en retard) et `RegressiveTrendStrategy` (régression, continue, recalculée à chaque bougie — donc réactive mais plus bruitée) peuvent diverger en signe, et aucune règle d'arbitrage n'existe à ce jour. Orientation retenue à l'issue du débat (à valider avec Clem avant codage — matière d'un futur lot d'assemblage, pas de ce lot-ci) : **hiérarchiser, pas pondérer à plat** — `SWING_STRUCTURE` sert de gate directionnel (BULL/BEAR confirmé autorise le sens du signal), la régression sert de signal de timing/momentum à l'intérieur de ce gate (elle module la confiance/le score, elle n'inverse jamais un régime confirmé). Cette hiérarchie n'est pas encore spécifiée précisément (seuils, comportement pendant `WARNING_*`/`UNDEFINED`) — matière du lot d'assemblage qui suivra les Étapes 5/6 ci-dessous.
2. **Rôle exact d'ADX, risque de double comptage avec `r²`.** ADX (force directionnelle) et `r²` de la régression (qualité de l'ajustement) mesurent potentiellement la même propriété sous-jacente (la "propreté" d'un mouvement) sous deux formes différentes — hypothèse non vérifiée, à trancher par les données (cf. Étape 6 ci-dessous). Si confirmée, ADX sera reconverti en filtre binaire (seuil d'activation minimal du composite) plutôt que laissé en facteur multiplicatif continu redondant avec `r²`.

**RANGE** : le garde-fou suivant a été identifié et doit être respecté par toute future règle de classification RANGE (points ouverts B/C, §5) : un état `WARNING_BULL_BREAK`/`WARNING_BEAR_BREAK` accompagné d'un `regressionScore` fort et croissant est un **candidat-retournement, pas un range** — ne jamais étiqueter RANGE cette configuration précise, qui est au contraire le moment le plus informatif d'un changement de régime.

**Contrainte d'historique.** La calibration walk-forward envisagée (Étape 2 — close depuis, sans objet, cf. §9.0 — et toute calibration future de `RegressiveTrendStrategy`) suppose un historique D1 fiable sur plusieurs années (plusieurs cycles de marché). Cette fiabilité — profondeur réellement persistée, comportement du cache-aside (`CachingMarketDataApiClient`) sur une fenêtre pluriannuelle — n'a jamais été vérifiée depuis l'observation initiale de juillet 2026 (mémoire projet `tradeio5_db_h1_history_gap`, ~2 mois de cache H1 constatés à l'époque, avant l'introduction du cache-aside actuel). C'est désormais un **préalable bloquant**, pas un point de détail : sans cette vérification, aucune calibration walk-forward (poids de régression, seuils RANGE) n'a de base fiable.

### Deux gates ajoutés à la roadmap, avant toute calibration/assemblage

Cf. Étape 5 (vérification de l'historique D1) et Étape 6 (corrélation ADX/régression) dans [`docs/prompts/prompt-implementation-trend-unifie-roadmap.md`](../prompts/prompt-implementation-trend-unifie-roadmap.md) — chacune fait l'objet d'un prompt d'implémentation séparé, cohérent avec la convention du projet.

### Ce que ce chantier devient

`RegressiveTrendStrategy` rejoint donc formellement le périmètre de cette étude/roadmap (elle n'y était pas rattachée à sa création le 2026-09-19) — la Trend unifiée de TradeIO5 est désormais explicitement composée de 3 briques (`SWING_STRUCTURE`, `LINEAR_REGRESSION`, ADX), pas 2. Le titre de cette étude ("Régime + Force + Confiance + BOS") reste valable ; la brique Dynamique/régression vient enrichir l'axe Régime (résolution RANGE) sans en changer la structure globale.

## 9. Addendum 2026-09-24 — spécification de la composition (Étape 7)

### 9.0 Écart factuel corrigé (staleness `atrMultiplier`)

`SwingStructureCalculator` a été réécrit intégralement le 2026-09-19 : un pivot est désormais confirmé dès la bougie qui prouve le retournement, sans ATR ni seuil. Le calculateur n'a **aucun paramètre de calibration**, et plus aucun `atrMultiplier` n'existe dans le code (`SwingStructureCalculator`, `IndicatorParametersFactory`, `StrategyParametersFactory`). Les mentions de ce paramètre dans cette étude et dans la roadmap étaient périmées. Elles ont été retirées le 2026-09-24, le point A (§5) est sans objet, et l'Étape 2 de la roadmap est close pour ce motif. Les points B/C (classification RANGE fine, repli `UNDEFINED`) restent ouverts. Il s'agit d'un nettoyage de staleness, pas d'une décision.

Conséquence empirique à garder en tête (mesurée sur BTC D1 2017-2026) : sans filtre d'amplitude, le régime affiché change tous les ~4,2 jours et passe 55 % du temps en `WARNING_*`. Il est donc plus nerveux que la régression, ce qui inverse la prémisse « gate lent / timing rapide » du §8.

### 9.1 Spécification de composition

Candidates C1/C2/C3, rôle d'ADX, contrat `TrendState`, esquisse du protocole de walk-forward et questions ouvertes : [`spec-composition-trend-unifie.md`](spec-composition-trend-unifie.md). Proposition non retenue telle quelle par le walk-forward exécuté ensuite (cf. §10 ci-dessous).

## 10. Addendum 2026-09-24 — décision finale et implémentation (Étape 8)

Le walk-forward exécuté pour trancher la spécification de composition (§9.1) a produit un résultat qui invalide la prémisse même de cette spécification : face à la tendance réelle, la régression multi-fenêtres seule domine chaque année testée, tout gate construit sur `SWING_STRUCTURE` dégrade le résultat, et ADX n'apporte rien (détail chiffré : [`spec-composition-trend-unifie.md`](spec-composition-trend-unifie.md) §10). Après visualisation interactive des candidates sur BTC D1, **Clem a tranché (2026-09-24) pour la solution la plus radicale plutôt qu'un compromis** : la Trend unifiée n'est plus composée de 3 briques (`SWING_STRUCTURE`, `LINEAR_REGRESSION`, ADX, cf. §8) mais **portée entièrement par la régression, avec une hystérésis sur l'état discret** (candidate REG_H, entrée ±1/6, sortie 0) plutôt qu'un lissage EMA.

Conséquence directe sur le constat du §1 de cette étude : le titre historique ("Régime + Force + Confiance + BOS") reste correct comme axes de sortie, mais leur brique porteuse a changé — `SWING_STRUCTURE` ne joue plus aucun rôle dans `TrendAnalyzer`/`TrendConfirmationStrategy` (elle reste utilisée ailleurs, `SwingStructureIndicator`, hors périmètre de ce chantier). L'axe Régime gagne enfin un véritable état RANGE (`TrendRegime.RANGE`), sans classification fine haussier/baissier/volatil (points B/C du §5 : toujours ouverts, cf. limite documentée en §11 de la spec de composition).

Implémentation, contrat `TrendState` final et écarts vs. la proposition du §9.1 : [`spec-composition-trend-unifie.md`](spec-composition-trend-unifie.md) §11. Prompt : [`prompt-implementation-trend-unifie-etape8-regression-hysteresis.md`](../prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md). Roadmap close (8 étapes, cf. [`prompt-implementation-trend-unifie-roadmap.md`](../prompts/prompt-implementation-trend-unifie-roadmap.md)) ; prochain chantier (hors roadmap) : branchement du calculateur DCA Rainbow sur `TrendAnalyzer`.
