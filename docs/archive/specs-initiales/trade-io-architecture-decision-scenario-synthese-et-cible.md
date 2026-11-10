---
statut: archivé
sujet: Spec initiale — Trade Io – Architecture Decision & Scenario (synthèse Et Cible)
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: b6f54baae9
---
# Trade Io – Architecture Decision & Scenario (synthèse Et Cible)

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/Trade Io – Architecture Decision & Scenario (synthèse Et Cible).odt`. Ne jamais lire comme référence de l'état courant.

# <span id="anchor"></span>TradeIO – Synthèse de réflexion et architecture cible

## <span id="anchor-1"></span>1. Objectif général

TradeIO vise à être un **moteur de décisions de trading intelligent**, capable de : - interpréter le marché via des scénarios, - transformer certaines hypothèses en **décisions traçables**, - exécuter ces décisions dans des environnements variés (exchange, non-custodial), - mesurer la qualité des décisions (PnL, durée, risque), - fonctionner **à l’identique en live et en backtest**.

Le cœur du système n’est pas l’ordre, mais **la décision**.

## <span id="anchor-1"></span>2. État actuel (point de départ)

### <span id="anchor-1"></span>Ce qui est déjà en place

  - ScenarioEngine centralisant :
    
      - observation du marché,
      - création, mutation et fusion de MarketScenario,
      - gestion du cycle de vie (INITIAL → EMERGING → CONFIRMING → VALIDATED / INVALIDATED / EXPIRED),
      - historisation des changements d’état.

  - MarketScenarioImpl joue déjà très bien son rôle de :
    
      - **formulation d’hypothèses marché**,
      - montée / descente progressive en confiance,
      - proposition d’une intention d’action lorsque le scénario est VALIDATED et stable.

### <span id="anchor-1"></span>Limite identifiée

Le scénario : - interprète le marché, - décide qu’une action est pertinente, - **mais ne doit pas être responsable de l’exécution ni du suivi de performance**.

Il manque une couche explicite entre *hypothèse* et *engagement*.

## <span id="anchor-1"></span>3. Décision clé prise

### <span id="anchor-1"></span>Séparation stricte des responsabilités

  - MarketScenario
    
      - \= hypothèse marché vivante
      - peut évoluer, se renforcer, s’invalider
      - ne sait rien du trade réel

  - **Decision**
    
      - \= engagement stratégique pris à un instant donné
      - créé à partir d’un scénario VALIDATED
      - traçable, mesurable, finie

## <span id="anchor-1"></span>4. Choix du modèle de décision

### <span id="anchor-1"></span>Décision composite dès la V1

  - Une décision représente une **intention longue** (ex : DCA intelligent).
  - Elle est composée de plusieurs **ActionSteps** (BUY, SELL, EXIT…).
  - Chaque step est lié à des conditions explicites.

### <span id="anchor-1"></span>Discipline V1 : « composite au goût d’atomique »

Pour limiter la complexité initiale : - le plan d’actions est défini dès la création de la décision, - un seul step est actif à la fois, - pas de branching dynamique implicite, - les règles d’évolution sont explicites et historisables.

Cela permet : - des backtests simples, - un PnL clair par décision, - une montée en complexité progressive sans refonte.

## <span id="anchor-2"></span>5. Environnement d’exécution

### <span id="anchor-2"></span>Principe

La décision **ne dépend pas d’un exchange concret**, mais : - elle connaît un **ExecutionEnvironment** abstrait.

### <span id="anchor-2"></span>Rôle de l’environnement

Il fournit : - frais (maker / taker / réseau), - slippage estimé, - contraintes (taille minimale, latence), - type d’environnement (exchange vs non-custodial).

➡️ L’environnement **influence la décision** (fréquence, granularité), sans la coupler à une implémentation technique.

## <span id="anchor-2"></span>6. Live et Backtest

Décision forte prise :

Le même code doit tourner en live et en backtest.

Seuls changent : - la Clock, - la source de données marché, - l’adaptateur d’exécution.

Les règles de décision, d’arbitrage et de suivi sont strictement identiques.

## <span id="anchor-2"></span>7. Suivi et métriques (V1)

### <span id="anchor-2"></span>Métriques retenues pour la V1

  - PnL final par décision
  - **Durée de vie** (EXECUTED → CLOSED)

### <span id="anchor-2"></span>Anticipation des extensions

L’architecture doit permettre d’ajouter plus tard : - excursions de prix, - MFE / MAE, - drawdown intra-décision, - métriques de risque.

## <span id="anchor-2"></span>8. Ownership

  - Les décisions restent liées à un ScenarioOwner.
  - Ce concept pourra évoluer (renommage futur),
  - mais il est suffisant et cohérent pour la V1.

## <span id="anchor-2"></span>9. Architecture cible (vue conceptuelle)

Market Data  
  ↓  
MarketOpinion  
  ↓  
ScenarioEngine  
  ↓  
MarketScenario (hypothèses)  
  ↓ (VALIDATED)  
DecisionCandidate  
  ↓  
DecisionEngine  
  ↓  
Decision (composite)  
  ├─ ActionStep 1 (EXECUTED)  
  ├─ ActionStep 2 (PENDING)  
  └─ ...  
  ↓  
DecisionMonitor  
  ↓  
Decision CLOSED (PnL, durée)

## <span id="anchor-2"></span>10. Rôles des principaux objets

### <span id="anchor-2"></span>ScenarioEngine

  - Observe le marché
  - Crée / met à jour / expire les scénarios
  - Ne connaît pas l’exécution

### <span id="anchor-2"></span>MarketScenario

  - Porte une hypothèse marché
  - Gère confiance, signal, stabilité
  - Peut proposer un **DecisionCandidate**

### <span id="anchor-2"></span>DecisionCandidate

  - Objet léger
  - Signale qu’un scénario est mûr pour engagement
  - Ne déclenche rien seul

### <span id="anchor-2"></span>DecisionEngine

  - Centralise les décisions
  - Déduplique (1 scénario → 1 décision)
  - Arbitre entre décisions concurrentes
  - Crée les Decision

### <span id="anchor-2"></span>Decision

  - Représente un engagement stratégique
  - Contient un plan d’ActionSteps
  - Gère son propre lifecycle (CREATED → EXECUTED → MONITORING → CLOSED)

### <span id="anchor-2"></span>ActionStep

  - Action élémentaire (BUY, SELL, EXIT…)
  - Conditions explicites
  - Résultat mesurable

### <span id="anchor-2"></span>ExecutionEnvironment

  - Modélise les coûts et contraintes
  - Influence la décision sans la lier à un exchange

### <span id="anchor-2"></span>DecisionMonitor

  - Suit l’évolution post-exécution
  - Calcule PnL et durée
  - Prépare les métriques futures

## <span id="anchor-2"></span>11. Prochaine étape naturelle

  - Valider définitivement cette architecture
  - Puis définir **les interfaces Java** correspondantes
  - Sans encore coder la logique métier

L’objectif est une base conceptuelle solide, traçable et évolutive.  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
  
\=== Rapport d'Architecture TradeIO (version enrichie) ===

## 1\. Synthèse générale

TradeIO est un bot de trading orienté DCA intelligent et décisions basées sur la combinaison de scénarios locaux et globaux. Le système est conçu pour :

  - Collecter et traiter les données de marché.
  - Maintenir un état des scénarios (local et global).
  - Générer des décisions de trading traçables.
  - Évaluer la performance des décisions de manière cohérente (live et backtest).

L'objectif principal est de créer un **pipeline complet et traçable** : indicateurs → scénarios → décision → action → suivi / évaluation.

## 2\. Architecture conceptuelle de la partie “tree” arbre de décisions

### 2.1. Flux général

*Market Data*

*    │*

*    ▼*

*Market Opinion Result*

*    │*

*    ▼*

*ScenarioEngine*

*    │*

*    ├─ Local MarketScenario(s) (symbol spécifique)*

*    └─ Global MarketScenario(s) (macro / global)*

*    │*

*    ▼*

*DecisionCandidate(s)*

*    │*

*    ▼*

*DecisionEngine*

*    │*

*    ▼*

*Decision (composite)*

*    │*

*    ▼*

*ActionStep(s)*

*    │*

*    ▼*

*DecisionMonitor / DecisionEvaluation*

### 2.2. Rôle des principaux composants

#### a) MarketScenario

  - **Local** : relié à un symbole, construit le signal et la confiance.

  - **Global** : agrège l'information macro, influence les décisions locales.

  - **Fonctions clés** :
    
      - *observe()* pour intégrer de nouvelles opinions.
      - *mutateScenario()* pour ajuster le signal et la confiance.
      - *enrichFrom()* pour combiner scénarios globaux.
      - *proposeIntent()* pour fournir des candidatures à la décision.

  - **Historisation** : chaque changement d'état est logué dans un historique interne.

#### b) DecisionCandidate

  - Objet intermédiaire léger créé par un scénario validé.
  - Contient la référence au scénario local, snapshots globaux et contexte complet.
  - Sert de base pour le DecisionEngine.

#### c) DecisionEngine

  - Transforme les candidats en Decisions effectives.
  - Applique filtres, priorisation, et contexte d’exécution (Exchange vs Wallet non-custodial).
  - Peut combiner plusieurs candidats dans une **Decision composite**.

#### d) Decision

  - **Composite de ActionStep(s)**, correspondant à un engagement réel sur le marché.
  - **Lifecycle** : CREATED → EXECUTED → OBSERVING → CLOSED (ou ABORTED/EXPIRED).
  - **Traçabilité complète** : garde la référence aux scénarios locaux et globaux qui l’ont influencée, snapshots d’indicateurs, contexte d’exécution.
  - **Règle clé** : une décision est toujours rattachée à un symbol/portefeuille.

#### e) ActionStep

  - Unité d’exécution concrète : BUY, SELL, HOLD, EXIT.
  - Paramétrable avec seuils et conditions.
  - Traçable individuellement pour évaluation fine.

#### f) DecisionMonitor / DecisionEvaluation

  - Suit les décisions exécutées dans le temps.

  - **Métriques** :
    
      - PnL réalisé et latent
      - Durée de vie et temps jusqu’au profit/perte
      - Drawdown maximum
      - Volatilité sur la période
      - Frais et ratio gain/risque

  - Permet la **traçabilité complète** depuis la justification initiale jusqu’au résultat.

  - Compatible avec backtesting pour reproduire exactement le comportement historique.

## 3\. Principes d’architecture

1.  **Séparation claire** :
    
      - Scénarios = producteurs de contexte et signaux
      - Decisions = consommateurs, responsables des actions
      - Evaluation = couche transverse, indépendante de l’exécution

2.  **Décision composite par défaut** :
    
      - Facilite le DCA intelligent ++
      - Permet de simuler des comportements atomiques via ActionSteps

3.  **Traçabilité totale** :
    
      - Chaque Decision conserve les scénarios et indicateurs qui l’ont influencée
      - Historisation des changements de scénario
      - Historisation des actions et métriques

4.  **Influence des Global Scenarios** :
    
      - Ne créent jamais de Decision seules
      - Ajustent la priorisation, le sizing et la fréquence des décisions locales

5.  **Evaluation post-exécution** :
    
      - Repose sur métriques financières et temporelles
      - Permet analyse qualitative et quantitative
      - S’intègre au backtesting

6.  **Extensibilité** :
    
      - Facile d’ajouter de nouvelles métriques (ex : MFE/MAE)
      - Décisions et scénarios peuvent évoluer indépendamment

## 4\. Schéma conceptuel (simplifié)

*                     ┌────────────────────┐*

*                     │   Market Data    │*

*                     └────────┬───────────┘*

*                              │*

*                              ▼*

*                     ┌────────────────────┐*

*                     │  Market Opinion  │*

*                     └────────┬───────────┘*

*                              │*

*                              ▼*

*                     ┌────────────────────┐*

*                     │  ScenarioEngine  │*

*                     └────────┬───────────┘*

*          ┌───────────────────┴───────────────────┐*

*          │                                      │*

*          ▼                                      ▼*

*┌────────────────────┐                ┌────────────────────┐*

*│ Local MarketScenario│                │ Global MarketScenario│*

*└─────────┬──────────┘                └────────────────────┘*

*          │ (VALIDATED)*

*          ▼*

* ┌───────────────────────────────┐*

* │     DecisionCandidate       │*

* └─────────┬─────────────────────┘*

*           │*

*           ▼*

* ┌───────────────────────────────┐*

* │       DecisionEngine        │*

* └─────────┬─────────────────────┘*

*           │*

*           ▼*

* ┌───────────────────────────────┐*

* │          Decision           │*

* └─────────┬─────────────────────┘*

*           │*

*           ▼*

* ┌───────────────────────────────┐*

* │        ActionStep(s)        │*

* └─────────┬─────────────────────┘*

*           │*

*           ▼*

* ┌───────────────────────────────┐*

* │ DecisionMonitor / Evaluation │*

* └───────────────────────────────┘*

**Note** : ce rapport peut servir de base pour implémenter les interfaces Java, les abstracts et les classes concrètes, tout en maintenant une traçabilité complète et un pipeline d’évaluation robuste.
