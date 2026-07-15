# Étude — Unifier les mécanismes de modulation de confidence (2026-07-16)

Demande de Clem, née d'une relecture à froid de l'architecture Indicator/Strategy/Opinion (cf.
schémas produits en conversation) : avant de basculer sur une refonte de `DecisionEngine`, corriger
un défaut déjà présent aujourd'hui — trois mécanismes différents font la même chose (atténuer une
confidence sans jamais toucher au score directionnel), codés séparément, à trois endroits distincts,
sans aucun contrat commun.

## 1. Constat — la duplication existe déjà, avant tout ajout

Trois fonctions vivent côte à côte dans `MarketOpinionHelper`
(`src/main/java/fr/ses10doigts/tradeIO5/service/tree/helper/MarketOpinionHelper.java`), chacune
n'étant appelée que par un seul endroit :

| Fonction | Seul appelant | Entrée | Sortie |
|---|---|---|---|
| `computeSentimentShiftDampening(now, yesterday, buyThreshold, sellThreshold, deltaThreshold)` | `GlobalMarketOpinion.decide` | mouvement Fear&Greed 24h | `double` facteur `]0,1]` |
| `computeStalenessDampening(lastTradeTimeEpochSeconds, now, staleThresholdHours)` | `MacroMarketOpinion.decide` | ancienneté d'une quote SP500/NASDAQ | `double` facteur `]0,1]` |
| `computeConfidenceModulationFactor(modulatorScore)` | `AbstractMarketOpinion.applyConfidenceModulators` | score `[-1,1]` d'une Strategy `CONFIDENCE_MODULATOR` | `double` facteur `[0.5,1.0]` |

Les trois retournent la même chose (un facteur multiplicatif appliqué uniquement à `confidence`,
jamais au `score`), avec la même philosophie documentée dans les trois javadocs ("jamais un 0
brutal", "jamais d'amplification, seulement une atténuation continue"). Rien ne les relie : pas
d'interface, pas de liste, pas de registry — chaque `MarketOpinion` qui a besoin de moduler sa
confidence a dû réinventer son propre point d'appel.

Deux confirmations supplémentaires que ce n'est pas un cas isolé :

- Le seul mécanisme réutilisable qui existe (`StrategyType.CONFIDENCE_MODULATOR`, dans
  `AbstractMarketOpinion.decide`/`applyConfidenceModulators`) est soudé à `Strategy` : il ne peut
  servir qu'à une `MarketOpinion` qui agrège des `StrategyKey` (aujourd'hui, seule
  `DefaultMarketOpinion`/LOCAL). `GlobalMarketOpinion` et `MacroMarketOpinion` ne passent pas par
  `Strategy` (javadoc de `GlobalMarketOpinion` : "ni l'un ni l'autre n'est une décision d'entrée/
  sortie sur un actif") — elles ne peuvent donc pas réutiliser ce mécanisme, d'où leurs dampenings
  maison.
- `OpinionContext.globalState` (`Map<String,Object>`, javadoc "état global de l'application pour
  cette décision") est déclaré mais n'est ni lu ni écrit nulle part dans le code — une trappe prévue
  pour ce genre de généralisation, jamais utilisée.

## 2. Design cible

Extraire une interface `ConfidenceModulator`, indépendante de `Strategy` et de `MarketOpinion`,
que n'importe quelle `MarketOpinion` peut consulter :

```java
public interface ConfidenceModulator {
    ModulationResult evaluate(OpinionContext context, MarketOpinionParameters parameters);
}

public record ModulationResult(
        boolean applied,   // false = donnée indisponible, ignorer (jamais invalider l'Opinion)
        double factor,     // ]0,1], 1.0 = aucune atténuation
        String reason
) {}
```

Point de conception central, à garder de bout en bout : **les fonctions pures de
`MarketOpinionHelper` ne changent pas**. `computeSentimentShiftDampening`,
`computeStalenessDampening` et `computeConfidenceModulationFactor` restent telles quelles (mêmes
signatures, même comportement) — elles deviennent le corps de trois petites implémentations de
`ConfidenceModulator` (adaptateurs), pas des fonctions réécrites. Conséquence directe : les 19 cas
de `MarketOpinionHelperTest` qui testent ces fonctions n'ont aucune raison de changer.

Trois implémentations à créer (adaptateurs, package
`service/tree/opinion/modulator/` proposé) :

- `SentimentShiftModulator` — enrobe `computeSentimentShiftDampening`, utilisé par
  `GlobalMarketOpinion`.
- `StalenessModulator` — enrobe `computeStalenessDampening`, utilisé par `MacroMarketOpinion`
  (potentiellement une instance par indicateur si SP500/NASDAQ doivent rester distincts, ou une
  instance qui prend le facteur le plus conservateur des deux comme fait aujourd'hui).
- `StrategyConfidenceModulator` — enrobe une `Strategy` de type `CONFIDENCE_MODULATOR`
  (`MovementQualificationStrategy`, `OrderFlowStrategy`) + `computeConfidenceModulationFactor`,
  pour que `AbstractMarketOpinion` puisse traiter ce cas par la même liste que les deux autres au
  lieu d'un chemin séparé (`applyConfidenceModulators`).

`MarketOpinion.decide` (ou une méthode commune factorée, ex. dans une nouvelle classe utilitaire
`ConfidenceModulation` appelée par les trois `decide()` concernés) applique alors la même boucle
partout : produit du facteur de chaque `ConfidenceModulator` fourni, ignoré si `applied == false`,
jamais de score directionnel touché.

## 3. Inventaire précis — fichiers impactés

**Nouveaux fichiers :**
- `ConfidenceModulator` (interface) + `ModulationResult` (record) — nouveau package
  `service/tree/opinion/modulator/`.
- `SentimentShiftModulator`, `StalenessModulator`, `StrategyConfidenceModulator` — implémentations.
- Tests unitaires dédiés pour chacun (comportement identique aux dampenings actuels, testable
  isolément).

**Fichiers modifiés :**
- `GlobalMarketOpinion.java` — remplacer l'appel direct à `computeSentimentShiftDampening` (lignes
  183-184 actuelles) par une consultation de `SentimentShiftModulator`.
- `MacroMarketOpinion.java` — remplacer les deux appels à `computeStalenessDampening` (lignes
  162-166 actuelles, `sp500Staleness`/`nasdaqStaleness`/`Math.min`) par `StalenessModulator`.
- `AbstractMarketOpinion.java` — remplacer `isConfidenceModulator`/`applyConfidenceModulators`
  (lignes 79-125 actuelles) par la boucle commune décrite en §2, en passant les Strategies
  `CONFIDENCE_MODULATOR` à travers `StrategyConfidenceModulator`.
- `MarketOpinionHelper.java` — **inchangé** (les 3 fonctions restent, appelées désormais par les
  adaptateurs plutôt que par les Opinions directement).

**Tests à revalider (comportement inchangé attendu, pas de nouvelle assertion) :**
- `MarketOpinionHelperTest.java` (19 occurrences) — ne devrait nécessiter aucune modification.
- `AbstractMarketOpinionTest.java` (6 occurrences liées à `CONFIDENCE_MODULATOR`) — à adapter à la
  nouvelle boucle, mêmes cas testés.
- `GlobalMarketOpinionTest.java` — le test qui exerce `decide()` de bout en bout doit continuer à
  produire le même facteur de dampening final ; à vérifier après migration.
- `MacroMarketOpinionTest.java` (classe imbriquée `DecideTest`, 5 tests) — même remarque, en
  particulier ceux qui portent sur la fraîcheur SP500/NASDAQ.

**Non touché, à ne pas re-analyser :** `StrategyAggregator`, `StrategySignal`, `OpinionSignal`,
`DecisionEngine`, `ScenarioEngine` — aucun contrat de sortie ne change.

## 4. Hors scope de ce lot (volontairement)

- Brancher `ATR` (volatilité), `MacroEventCalendarService` (proximité FOMC/CPI) ou `ETF_FLOW` comme
  nouveaux `ConfidenceModulator` — l'objectif de ce lot est de rendre l'extraction possible sans
  changer aucun comportement observable, pas d'ajouter de nouveaux signaux. Ces branchements
  deviennent des ajouts triviaux une fois l'interface en place (chacun est une nouvelle classe qui
  implémente `ConfidenceModulator`, sans toucher à `AbstractMarketOpinion`/`GlobalMarketOpinion`/
  `MacroMarketOpinion` une seconde fois), mais restent un lot séparé.
- Extension du même contrat côté `DecisionEngine` (gate/sizing à partir de `WalletSnapshot`/
  `UserProfile`) — explicitement différée par Clem, pas avant.
- Renommer `StrategyType.RISK` / statuer sur son avenir — question distincte, non traitée ici.

## 5. Décisions à valider avec Clem avant codage

1. Nom définitif de l'interface (`ConfidenceModulator` proposé) et de son package.
2. `StalenessModulator` : une instance par indicateur (SP500, NASDAQ séparément) ou une seule qui
   reproduit le `Math.min` actuel ? Le second est plus fidèle au comportement existant à l'identique.
3. Faut-il un `ConfidenceModulatorRegistry` (même patron que `StrategyRegistry`/
   `IndicatorRegistry`) dès ce lot, ou une simple liste construite à la main dans chaque
   `MarketOpinion` suffit tant qu'il n'y a que 3 implémentations ?
4. Où vit la boucle d'application commune : méthode statique utilitaire, ou nouvelle classe
   dédiée injectée dans les 3 `MarketOpinion` concernées ?

## 6. Garantie de non-régression

Le critère de succès de ce lot n'est pas "de nouveaux signaux apparaissent" mais : pour un même
jeu d'entrées, `GlobalMarketOpinion`, `MacroMarketOpinion` et `DefaultMarketOpinion` publient
exactement le même `OpinionEvent` (même `score`, même `confidence`, même `signal`) qu'avant
refactor. Tous les tests existants sur ces trois classes doivent passer sans modification de leurs
assertions — seule leur construction (mocks/injection) peut changer si la nouvelle boucle change la
façon dont le modulateur est fourni.
