# Le moteur "tree" — pipeline Indicator → Strategy → Opinion → Scenario → Decision

Vérifié le : 2026-09-12 (`service/tree/{indicator,strategy,opinion,scenario,decision,event}/**`).

C'est le cœur métier du projet, package `service.tree`. Chaîne de transformation à 5 étages, chaque étage consommant le(s) précédent(s) :

```
Indicator  →  Strategy  →  Opinion  →  Scenario  →  Decision
(donnée      (signal      (agrégat    (état        (intention
 brute)       directionnel  multi-      persistant   d'action,
              ou modulateur strategy    par owner,   cycle de vie
              de confiance) par scope)  expirable)   CREATED→
                                                      EXECUTED/ABORTED)
```

## 1. Indicator

`service/tree/indicator/**`. Un `Indicator` calcule une valeur à partir de données de marché ou externes (RSI, EMA, ADX, Fear&Greed, DXY, ETF flow, etc.). `IndicatorEngine` + `IndicatorRegistry` : résolution par `IndicatorType` (enum). Les indicateurs qui dépendent d'un provider externe étendent `AbstractExternalIndicator` et résolvent leur credential via `IndicatorCredentialResolver` (voir [`architecture/03-ownership-and-lifecycle.md`](03-ownership-and-lifecycle.md) pour la résolution multi-owner des credentials).

`IndicatorType` recense un ensemble large de types (techniques : RSI/EMA/ADX ; macro : DXY/SP500/NASDAQ/UNRATE ; on-chain/dérivés : OI/Funding/Liquidations via Coinalyze ; ETF_FLOW ; FEAR_AND_GREED ; STABLECOIN_CAP). Détail des providers par indicateur : [`architecture/05-external-providers.md`](05-external-providers.md).

## 2. Strategy

`service/tree/strategy/**`. Une `Strategy` (interface, implémentations sous `impl/`) consomme un ou plusieurs `Indicator` et produit un signal directionnel ou un facteur de confiance. `StrategyAggregator` + `StrategyRegistry`.

**`StrategyType` — distinction structurante, source d'erreurs de lecture fréquente si ignorée :**

- `DIRECTIONAL` : contribue additivement au score directionnel agrégé (ex. `TrendConfirmationStrategy`, `MovementQualificationStrategy`, `OrderFlowStrategy`).
- `CONFIDENCE_MODULATOR` : **n'est jamais agrégé au score directionnel.** Multiplie uniquement la confidence finale (ex. `EtfFlowConfidenceStrategy`, `MacroRiskWindowModulator`). Un `CONFIDENCE_MODULATOR` qui semble "ne rien faire" au score directionnel fonctionne comme prévu — ce n'est pas un bug.

Combinaison par défaut utilisée à la fois par l'orchestrateur automatique et par le déclenchement manuel `/api/admin/decision/opinion` (scope `LOCAL`) : `DefaultLocalOpinionParamsProvider` — TrendConfirmation + MovementQualification + OrderFlow (DIRECTIONAL) + EtfFlow (CONFIDENCE_MODULATOR). Ce composant existe spécifiquement pour que les deux chemins de déclenchement ne divergent jamais silencieusement.

## 3. Opinion

`service/tree/opinion/**`. Une `MarketOpinion` (interface, `AbstractMarketOpinion` de base) agrège les strategies pour un `OpinionScope` donné et produit un `OpinionSignal` (signal majoritaire/pondéré + confidence + sources). `MarketOpinionRegistry` résout l'implémentation par scope.

**`OpinionScope` (4 valeurs) :**

| Scope | Symbole ? | Implémentation(s) | Contenu |
|---|---|---|---|
| `LOCAL` | oui (symbol-scoped) | `DefaultMarketOpinion` | EMA/ADX/RSI + modulateurs (ETF flow, etc.) |
| `GLOBAL` | non (`symbol=null` en sortie) | `GlobalMarketOpinion` | Fear&Greed + plafond stablecoin |
| `MACRO` | non (`symbol=null` en sortie) | `MacroMarketOpinion` | DXY/SP500/NASDAQ (appétit pour le risque) |
| `EXTERNAL` | dépend | **2 implémentations co-enregistrées**, voir ci-dessous | Avis externe |

**Point de confusion vérifié dans le code, à connaître avant de raisonner sur `EXTERNAL` :** deux classes implémentent `MarketOpinion` pour le scope `EXTERNAL` — `ExternalMarketOpinion` (conseiller LLM en direct) et `MediaMarketOpinion` (lecture du store de claims pré-extraites de la veille média, voir [`architecture/06-media-watch.md`](06-media-watch.md)). Les deux sont enregistrées sous le même scope dans `MarketOpinionRegistry` ; la façade (`TreeAnalysisFacade`) résout la **première** trouvée et logue un warning. Ce n'est pas un bug caché — c'est un état transitoire du code à vérifier/trancher si le scope `EXTERNAL` est utilisé en pratique (lequel des deux gagne dépend de l'ordre d'enregistrement Spring, non garanti stable).

`GLOBAL`/`MACRO` ignorent les strategies par construction (pas de notion directionnelle par actif) — un appel `/api/admin/decision/opinion?scope=GLOBAL` avec des strategies non vides n'est pas une erreur, elles sont simplement ignorées.

## 4. Scenario

`service/tree/scenario/**`. `MarketScenario` (interface, `DefaultMarketScenario` par défaut) représente un état persistant, par owner et par symbole/scope, qui évolue à partir des `OpinionSignal` successifs (validation continue, expiration). `ScenarioEngine`/`DefaultScenarioEngine` gèrent le cycle de vie ; `ScenarioFactory` la création. `ScenarioType`/`ScenarioStatus` (enums) qualifient l'état.

Constantes notables (codées en dur, non externalisées — voir [`known-gaps/code-level-inconsistencies.md`](../known-gaps/code-level-inconsistencies.md)) : `EXPIRATION_IDLE = 2h`, poids de blend confidence `0.7/0.3`.

Un `Scenario` peut proposer un `ActionIntent` (candidat d'action) — dédupliqué par épisode de validation continue depuis le correctif du 2026-08-11 (`proposedScenarioIds`, reset à la sortie de `VALIDATED`/stable).

## 5. Decision

`service/tree/decision/**`. `DecisionEngine` transforme un `ActionIntent` validé en `Decision` avec un `ActionStep` (BUY/SELL/NO_OP → `DecisionType` ENTER/EXIT/REBALANCE/STOP via `mapDecisionType`, corrigé le 2026-08-11 — avant cela le type était codé en dur à `EXIT` quelle que soit l'action réelle).

Cycle de vie d'un `ActionStep` : `CREATED → EXECUTED | ABORTED`, piloté par événements — mais **rien n'émet `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED` dans le code actuel** (voir [`architecture/01-overview.md`](01-overview.md) et [`known-gaps/decision-to-order-gap.md`](../known-gaps/decision-to-order-gap.md)).

`DecisionOrchestrator` pilote un cycle complet (calcul des 5 signaux LOCAL×3 + GLOBAL + MACRO, propagation à tous les owners actifs, verrou anti-doublon `OwnerRefreshGuard` de 1h en mémoire — pas de moyen de le forcer via l'API, seul un redémarrage le réinitialise).

Persistance/rejeu : `DecisionScenarioSnapshotService` prend une photo quotidienne (scénarios + décisions actifs) ; `DecisionScenarioRestoreService`/`DecisionScenarioRestoreRunner` rejouent cet état au redémarrage de l'app, pour ne pas repartir de zéro. `UserArchivalService` évince de la mémoire active (`evictOwner`) les owners inactifs depuis longtemps (seuil vérifié dans le plan de test manuel : > 60 jours sans connexion) ; restauré automatiquement à la reconnexion (`AuthController#authenticateUserForm`).

## Event engine (infrastructure transverse)

`service/tree/event/engine/**` : `EventBus` synchrone custom + `PersistableEvent` + `EventStore` pluggable (implémentation JPA pour `LIVE`, InMemory pour `DEV`/`BACKTEST` — voir `ExecutionMode` dans le glossaire) + `EventStoreRegistry` + `EventLogger`. Utilisé pour propager les transitions d'état (scénario créé/mis à jour, decision créée, etc.) de façon découplée entre les étages du pipeline.

## Point d'entrée unique : la façade MCP/REST

`TreeAnalysisFacade` (`service/tree/api/mcp/`) est le point de convergence utilisé à la fois par les tools MCP (`TreeAnalysisMcpTools` : `get_indicator`, `evaluate_strategy`, `get_opinion`) et par les contrôleurs REST admin (`OpinionAdminController`, etc.) — un seul chemin de calcul, pas d'implémentation parallèle. Détail des endpoints : [`api/mcp-tools.md`](../api/mcp-tools.md) et [`api/rest-endpoints.md`](../api/rest-endpoints.md).
