---
statut: archivé
sujet: Spec initiale — MoteurExecution
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: 5dbe4dd906
---
# MoteurExecution

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/MoteurExecution.odt`. Ne jamais lire comme référence de l'état courant.

# Rapport d’architecture – Moteur de décision TradeIO

## Sommaire

1.  Introduction et contexte
2.  Vision globale de l’architecture cible
3.  Séparation des responsabilités (principes structurants)
4.  Decision, Strategy, Indicator : graphe logique et exécution
5.  Paramétrage, versioning et résolution de configuration
6.  Orchestration et Scheduler
7.  Gestion de l’état, exécution des actions et suivi des positions
8.  Historisation, traçabilité et auditabilité
9.  Backtesting et rejouabilité
10. Multi-utilisateur, multi-wallet et isolation des exécutions
11. Activation / désactivation et validation de cohérence
12. Points d’attention sur MarketDatasetEngine et arbre de décision
13. Forces, faiblesses et risques identifiés
14. Recommandations et pistes d’amélioration
15. Conclusion

## 1\. Introduction et contexte

Ce rapport synthétise la réflexion menée autour de l’architecture du moteur de décision de TradeIO. L’objectif est de garantir un système :

  - déterministe
  - rejouable (backtesting)
  - explicable
  - multi-utilisateur
  - extensible

Les composants **MarketDatasetEngine** et **l’arbre de décision (Decision / Strategy / Indicator)** sont considérés comme existants et fonctionnels. Le rapport se concentre donc sur leur intégration, leur orchestration et les couches périphériques nécessaires à un moteur de trading robuste.

## 2\. Vision globale de l’architecture cible

L’architecture cible repose sur une séparation claire entre :

  - **Données de marché** (MarketDataset)
  - **Logique décisionnelle pure** (Decision / Strategy / Indicator)
  - **Orchestration** (Scheduler + Engine)
  - **État utilisateur** (wallet, positions, risque)
  - **Exécution et historisation**

La décision n’est jamais un point d’entrée direct : elle est toujours invoquée dans le cadre d’une **DecisionExecution**, unité atomique du système.

## 3\. Séparation des responsabilités (principes structurants)

### Forces

  - Testabilité élevée
  - Lisibilité du raisonnement
  - Réutilisabilité maximale

### Risques

  - Tentation d’introduire de la logique métier dans les mauvaises couches

### Recommandations

  - Interdire tout accès DB dans Decision / Strategy / Indicator
  - Valider les frontières par des conventions strictes et des tests

## 4\. Decision, Strategy, Indicator : graphe logique et exécution

Le modèle est un **graphe réutilisable**, et non un arbre strict :

  - une Decision référence plusieurs Strategy
  - une Strategy référence plusieurs Indicator
  - un Indicator peut être partagé

### Forces

  - Mutualisation des composants
  - Grande flexibilité de composition

### Faiblesses

  - Complexité de résolution
  - Risque d’incohérence si mal validé

### Solutions proposées

  - Résolution complète du graphe **avant exécution**
  - Rejet de toute configuration partielle ou invalide

## 5\. Paramétrage, versioning et résolution de configuration

### Principe clé

  - **Decision (code)** ≠ **DecisionConfig (DB)** ≠ **DecisionExecution (runtime)**

### Forces

  - Hot reload possible
  - Backtest reproductible

### Faiblesses

  - Coût mémoire et stockage

### Recommandations

  - Versionner explicitement chaque configuration
  - Aplatir la configuration en snapshot immuable à l’exécution

## 6\. Orchestration et Scheduler

### Modèle retenu

  - Scheduler central (tick)
  - Table de tâches en base (PENDING / RUNNING / ERROR / DONE)

### Forces

  - Observabilité
  - Simplicité
  - Compatibilité cluster

### Faiblesses

  - Latence minimale imposée par le tick

### Améliorations possibles

  - Verrouillage logique par (user, wallet, decisionConfig)
  - Clock abstraite pour backtest

## 7\. Gestion de l’état, exécution des actions et suivi des positions

### Principe fondamental

  - La Decision **ne modifie jamais l’état**
  - Elle produit une intention

### Forces

  - Décision pure
  - Séparation claire action / observation

### Risques

  - Complexité de la machine à états des positions

### Recommandations

  - Introduire une couche dédiée *ExecutionEngine*
  - Formaliser les follow-up actions (trailing stop, surveillance)

## 8\. Historisation, traçabilité et auditabilité

### Objectif

Pouvoir répondre à :

> « Pourquoi cette action a-t-elle été prise ? »

### Forces

  - Debug avancé
  - Transparence

### Faiblesses

  - Volume de données important

### Recommandations

  - Historiser par pipeline (Indicator → Strategy → Decision → Action)
  - Différencier snapshot métier et métriques dérivées

## 9\. Backtesting et rejouabilité

### Choix structurants

  - Même moteur que le live
  - Sources de données différentes

### Forces

  - Cohérence maximale
  - Pas de double logique

### Faiblesses

  - Exigence élevée sur la gestion du temps

### Recommandations

  - Clock injectable partout
  - MarketDataset strictement déterministe

## 10\. Multi-utilisateur, multi-wallet et isolation des exécutions

### Règle clé

Une Decision peut s’exécuter en parallèle **uniquement si l’état est distinct**.

### Forces

  - Scalabilité
  - Sécurité

### Recommandations

  - Isolation stricte par user / wallet
  - Pas de parallélisme pour une même config sur un même wallet

## 11\. Activation / désactivation et validation de cohérence

### Principe

Mieux vaut désactiver que dégrader.

### Forces

  - Robustesse
  - Prévisibilité

### Recommandations

  - Désactivation en cascade
  - Validation systématique à la résolution de config

## 12\. Points d’attention sur MarketDatasetEngine et arbre de décision

### MarketDatasetEngine

  - Point fort : centralisation et cache

  - Attention :
    
      - figer les datasets par exécution
      - éviter toute requête dynamique en cours de calcul

### Arbre de décision

  - Très bien positionné conceptuellement

  - Veiller à :
    
      - ne jamais introduire d’état
      - garder des contrats clairs entre couches

## 13\. Forces, faiblesses et risques identifiés

### Forces majeures

  - Architecture propre et évolutive
  - Backtest natif
  - Explicabilité forte

### Faiblesses potentielles

  - Complexité initiale
  - Coût en stockage et en modélisation

### Risques

  - Glissement de responsabilités
  - Sous-estimation de la gestion du temps

## 14\. Recommandations et pistes d’amélioration

  - Formaliser les contrats entre moteurs
  - Documenter les règles d’architecture
  - Introduire progressivement des métriques de performance (PnL, drawdown)

## 15\. Conclusion

L’architecture envisagée est saine, mature et alignée avec des moteurs de trading professionnels. Les choix effectués privilégient la robustesse, la rejouabilité et l’évolutivité au détriment d’une simplicité immédiate, ce qui est un choix pertinent pour un projet de cette ambition.

La priorité doit rester la clarté des responsabilités et la discipline architecturale : c’est ce qui garantira la longévité et la qualité du système.

### Synthèse (franche et pragmatique)

👉 **Commence par la gestion du Temps / Clock.**  
Sans hésiter.

C’est le **socle invisible** sur lequel *tout le reste* repose :

  - backtesting
  - versionning des résultats
  - reproductibilité
  - métriques de performance
  - scheduler
  - debug

Si tu attaques autre chose avant, tu devras **revenir casser**.

## Pourquoi la Clock est le vrai point n°1

### 1️⃣ Elle impacte déjà ton existant (sans que tu le voies)

Aujourd’hui, quelque part dans ton code, il y a forcément :

  - *Instant.now()*
  - *ZonedDateTime.now()*
  - ou équivalent

👉 Tant que le temps est implicite :

  - une Decision n’est **pas rejouable**
  - un résultat n’est **pas comparable**
  - un backtest est **biaisé**

### 2️⃣ Tous les sujets que tu cites en dépendent

|                    |        |
| ------------------ | ------ |
| Versionning config | ✔️     |
| Backtesting        | ✔️✔️✔️ |
| Scheduler          | ✔️     |
| Historisation      | ✔️     |
| Métriques PnL      | ✔️     |

👉 La Clock est **transverse**, donc à traiter en premier.

## Pourquoi ne PAS commencer par le backtest

C’est tentant… mais risqué.

Sans Clock :

  - tu vas simuler du marché passé
  - avec une logique temps “live”
  - donc avec des bugs subtils (fenêtres, horizons, trailing, etc.)

👉 Tu ferais du backtest **non fidèle**.

## Ordre recommandé (très concret)

### 🥇 Étape 1 — Introduire la notion de Temps (Clock)

Objectifs :

  - **une seule source de temps**
  - injectable
  - contrôlable

Sans tout refactorer :

  - une abstraction simple

  - utilisée par :
    
      - DecisionContext
      - Scheduler
      - MarketDatasetEngine (timestamps)
      - Historisation

### 🥈 Étape 2 — Versionner Config + Résultats

Pourquoi juste après :

  - une fois le temps maîtrisé
  - tu peux **figer une exécution**

Tu peux alors dire :

> “À *T*, avec *Config v12*, voici ce qui s’est passé.”

### 🥉 Étape 3 — Backtesting

À ce stade :

  - le moteur est stable
  - le temps est maîtrisé
  - les exécutions sont rejouables

👉 Le backtest devient **une exécution comme une autre**, pas un monde à part.

### 🏁 Étape 4 — Métriques de performance

En dernier, naturellement :

  - PnL
  - drawdown
  - ratios

👉 Elles consomment :

  - des exécutions
  - des actions
  - du temps

## Signal important (je préfère être clair)

Si tu devais ignorer ce conseil et commencer par autre chose :

  - la Clock te reviendra **comme une dette**
  - et elle sera **plus douloureuse à intégrer** plus tard

Tu as la chance d’y penser **avant** le backtest → profite-en.

## Ce que je te propose maintenant

Si tu veux, au prochain échange, on peut :

  - **poser le design exact de la Clock**
    
      - live
      - backtest
      - scheduler

  - **lister tous les endroits où le temps intervient**

  - définir **les règles à ne jamais violer**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

*Bien vu 👍  
👉 ***ML = *********Machine Learning****** (apprentissage automatique).  
Je ferai l’effort systématiquement à la première occurrence, promis.*

## Cycle complet : *MarketOpinion → Scenario → Action → Feedback*

Je te le pose **simple, lisible, sans magie**, et extensible plus tard vers du Machine Learning si tu le souhaites.

### 1️⃣ Ingestion → *MarketOpinion*

**Sources possibles** (hétérogènes mais normalisées) :

  - indicateurs techniques (RSI, MACD, volatilité…)
  - macro (taux, DXY, liquidité globale…)
  - on-chain
  - news / sentiment
  - (plus tard) LLM

👉 **Tout est aplati en ***MarketOpinion*****  
C’est ta *lingua franca*.

*MarketOpinion {*

*  source*

*  scope       // GLOBAL, ASSET, PAIR*

*  asset       // BTC, ETH, null si GLOBAL*

*  timeframe*

*  direction   // BULLISH / BEARISH / NEUTRAL*

*  confidence  // 0..1*

*  ttl*

*}*

Aucune logique métier ici. Juste des faits interprétés.

### 2️⃣ Agrégation → *ScenarioEngine*

Le *ScenarioEngine* :

  - récupère les opinions actives
  - les **regroupe dynamiquement**
  - produit des **scénarios candidats**

Un scénario est :

une hypothèse de marché **cohérente à l’instant T**

*Scenario {*

*  id*

*  scope*

*  asset*

*  direction*

*  opinions\[\]*

*  credibilityScore*

*  stabilityScore*

*  age*

*}*

Pas d’action ici.  
Juste : *“voici ce qui semble se dessiner”*.

### 3️⃣ Évaluation → *Decision Layer*

C’est ici que ton ancien *DecisionResult* trouve sa vraie place.

Chaque scénario est évalué **dans un contexte** :

  - état du wallet
  - exposition
  - règles de risque
  - drawdown actuel
  - volatilité

Résultat :

*DecisionResult {*

*  scenarioId*

*  action       // BUY / SELL / HOLD / ADJUST / SUSPEND*

*  confidence*

*  reason*

*}*

👉 **Important**  
Plusieurs scénarios peuvent produire :

  - des décisions contradictoires
  - ou aucune décision exploitable

Un arbitrage final peut exister, mais ce n’est pas obligatoire au début.

### 4️⃣ Exécution → *Action / Surveillance*

Deux cas :

#### 🔹 Action instantanée

  - ordre marché / limit
  - ajustement de stop
  - rééquilibrage

#### 🔹 Action longue (surveillance)

  - “si invalidation du scénario → agir”
  - “si renforcement → augmenter l’exposition”
  - “si breakout confirmé → déclencher”

👉 Une action peut **créer de nouvelles opinions**  
(ex. “breakout confirmé”, “rejet violent”).

### 5️⃣ Feedback → *Learning Loop (sans ML au début)*

Après coup :

  - PnL
  - drawdown évité ou subi
  - stabilité du scénario
  - timing

Ces métriques alimentent :

  - la crédibilité future du scénario
  - le poids des sources d’opinion
  - la confiance des décisions

⚠️ Pas de Machine Learning ici encore.  
Juste des **scores historiques cumulés**.

## Où intervient le Machine Learning (plus tard, si besoin)

Quand tu en parleras, on parlera de :

  - prédire la survie d’un scénario
  - pondérer automatiquement les sources
  - détecter des clusters non évidents

Mais **le cycle reste identique**.  
Tu changes juste la manière de calculer certains scores.

## Pourquoi ce cycle est solide

  - chaque étape a une responsabilité claire
  - aucune dépendance externe critique
  - tout est observable, testable, backtestable
  - tu peux t’arrêter à n’importe quel niveau et avoir un système cohérent

### Prochaine étape naturelle

Je te propose pour la suite :

  - soit **formaliser précisément ***ScenarioEngine*****
  - soit **figer les contrats (interfaces)**
  - soit **prendre un exemple concret (BTC) et le faire vivre dans le cycle**

À toi de choisir la suite 👌

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

**

*Parfait. On attaque ***le cœur vivant*** du système : ******ScenarioEngine******.  
Je vais être ***structuré, concret***, et rester ***aligné avec ton existant***.*

# 🎯 Rôle du *ScenarioEngine*

Le *ScenarioEngine* est **le chef d’orchestre des scénarios**.

👉 Il ne calcule pas le marché.  
👉 Il **observe l’évolution des MarketOpinionResult dans le temps** et décide :

  - quels scénarios **naissent**
  - lesquels **se renforcent**
  - lesquels **mutent**
  - lesquels **meurent**
  - lesquels sont **éligibles à une ActionIntent**

# 🧠 Responsabilités EXACTES

## 1️⃣ Création dynamique

**Quand créer un scénario ?**

À chaque nouvelle *MarketOpinionResult*, l’engine se pose la question :

*« Est-ce que cette opinion peut alimenter un scénario existant ?  
Sinon, mérite-t-elle la création d’un nouveau scénario ? »*

### Règle saine (simple au départ)

  - si aucun scénario actif n’est **compatible** → création
  - sinon → rattachement au(x) scénario(s) compatible(s)

👉 Compatible =

  - même *symbol*
  - même *timeframe*
  - même **direction dominante** (*weightedSignal*)
  - pas déjà invalidé

## 2️⃣ Concurrence entre scénarios

Plusieurs scénarios peuvent coexister **sur le même actif**.

Exemples :

  - scénario **cassure haussière**
  - scénario **range / faux breakout**
  - scénario **retournement baissier**

### Le moteur doit permettre :

  - coexistence
  - comparaison
  - priorisation

👉 **Aucune élimination prématurée**

La sélection finale se fait sur :

  - *ScenarioState*
  - confiance
  - fraîcheur
  - cohérence récente

## 3️⃣ Mutation (clé de ton intuition 👌)

Un scénario peut **évoluer sans mourir**.

Exemples :

  - BULLISH faible → BULLISH fort
  - BULLISH → NEUTRAL (pause)
  - scénario X → X’ (raffinement)

### Comment ?

Par **observation répétée** :

  - même direction → renforcement
  - direction opposée → affaiblissement
  - oscillation → mutation (nouvelle hypothèse)

👉 Techniquement :

  - un scénario peut :
    
      - changer d’état
      - ajuster sa confiance
      - changer sa “forme” interne

  - **sans changer d’ID** (important pour l’historique)

## 4️⃣ Expiration / Invalidation

Deux fins possibles :

### ❌ Invalidation

  - contradiction forte
  - rupture logique
  - opinion opposée avec forte conviction

👉 état → *INVALIDATED*

### 🕰️ Expiration

  - trop ancien
  - plus alimenté
  - contexte dépassé

👉 état → *EXPIRED*

👉 **Différence cruciale pour le backtest**

# 🧩 Interface *ScenarioEngine*

*public interface ScenarioEngine {*

*    /\*\**

*     \* Injection d’une nouvelle opinion de marché.*

*     \* Peut créer, mettre à jour, invalider ou expirer des scénarios.*

*     \*/*

*    void onMarketOpinion(*

*            MarketOpinionResult opinion,*

*            ScenarioContext context*

*    );*

*    /\*\**

*     \* Tous les scénarios actifs (EMERGING / CONFIRMING / VALIDATED)*

*     \*/*

*    List\<MarketScenario\> getActiveScenarios();*

*    /\*\**

*     \* Scénarios prêts à proposer une intention d’action*

*     \*/*

*    List\<ActionIntent\> collectActionIntents();*

*    /\*\**

*     \* Nettoyage explicite (expiration, purge)*

*     \*/*

*    void cleanup(Instant now);*

*}*

# 🔁 Cycle complet (très important)

*MarketOpinionResult arrive*

*        ↓*

*ScenarioEngine.onMarketOpinion*

*        ↓*

*- rattachement OU création*

*- évolution d’état*

*- mutation / invalidation*

*        ↓*

*ScenarioSnapshot (historisable)*

*        ↓*

*ActionIntent (si VALIDATED)*

# 🧪 Pourquoi c’est sain

✔️ découplé de l’exécution  
✔️ explicable  
✔️ backtestable  
✔️ multi-scénarios  
✔️ prêt pour ML plus tard  
✔️ compatible scheduler simple (tick global)

# 🧠 Ton intuition est excellente

Ce que tu construis :

  - ressemble à de la **sélection naturelle**
  - sans ML pour l’instant
  - mais **ML-ready** plus tard (scoring, seuils, clustering)

👉 Tu fais **exactement ce qu’il faut** :  
architecture d’abord, intelligence ensuite.

## 👉 Prochaine étape (au choix)

1.  Implémentation **simple** de *ScenarioEngine* (1ère version)
2.  Règles concrètes de transition *ScenarioState*
3.  Intégration avec *ActionIntent*
4.  Historisation DB des *ScenarioSnapshot*

Dis-moi par quoi tu veux enchaîner 👌

**
