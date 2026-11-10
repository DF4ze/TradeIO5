---
statut: archivé
sujet: Spec initiale — Gestion des data et dataset
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: 618c86038c
---
# Gestion des data et dataset

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/Gestion des data et dataset.odt`. Ne jamais lire comme référence de l'état courant.

Voici le **schéma logique d’imbrication** (responsabilités + flux), volontairement **conceptuel** et lisible.

## 1️⃣ Noyau métier (données pures)

*MarketData*

*   ↑*

*   │ (élément unitaire : candle / tick)*

*   │*

*MarketDataset*

*   ↑*

*   │ (snapshot IMMUTABLE)*

*   │*

*MarketDatasetRequest*

### Rôles

  - **MarketData**
    
      - une bougie / un point de marché
      - totalement stateless

  - **MarketDataset**
    
      - vue figée à un instant T
      - consommée par indicateurs / stratégies
      - ne sait PAS comment elle a été construite

  - **MarketDatasetRequest**
    
      - clé fonctionnelle
      - (symbol, timeframe, source)
      - identifie *un* dataset logique

## 2️⃣ État interne (invisible)

*MarketDatasetState*

### Rôle

  - buffer circulaire (*Deque\<MarketData\>*)
  - lastUpdate
  - complete
  - **mutable**
  - jamais exposé hors du module dataset

👉 C’est **la vérité vivante**, jamais consommée directement.

## 3️⃣ Services techniques (responsabilités claires)

### Cache

*MarketDatasetCache*

*   └── Map\<MarketDatasetRequest, MarketDatasetState\>*

  - gestion du cycle de vie
  - création lazy
  - thread-safe
  - **aucune logique métier**

### Manager (cerveau)

*MarketDatasetManager*

*   ├── merge(...)*

*   ├── detectGaps(...)*

*   ├── updateCompleteness(...)*

*   └── snapshot(...)*

  - seule classe autorisée à :
    
      - modifier *MarketDatasetState*
      - décider si un dataset est complet

  - 100 % testable sans API

### Provider (entrée monde extérieur)

*MarketDatasetProvider*

*   └── fetchMarketData(...)*

  - abstraction exchange / DEX / oracle
  - stateless
  - peut être multiple (Binance, Kraken…)

## 4️⃣ Engine (orchestration)

*MarketDatasetEngine (interface)*

*   ├── getDataset(request)*

*   └── refresh(request)*

*           ▲*

*           │*

*DefaultMarketDatasetEngine*

### Rôle

  - relie **Provider → Manager → Cache**
  - garantit la cohérence
  - ne contient aucune règle métier complexe
  - point d’entrée unique du système

## 5️⃣ Flux d’exécution (lecture)

*Strategy / Indicator*

*        ↓*

*MarketDatasetEngine.getDataset(request)*

*        ↓*

*MarketDatasetCache.getState(request)*

*        ↓*

*\[si incomplet\]*

*    Provider.fetch(...)*

*        ↓*

*    Manager.merge(state)*

*        ↓*

*Manager.snapshot(state)*

*        ↓*

*MarketDataset (IMMUTABLE)*

## 6️⃣ Résumé en une phrase

> **Le Dataset est une photo,  
> le State est le film,  
> le Manager monte le film,  
> le Provider filme,  
> l’Engine orchestre,  
> le Cache se souvient.**

### Anecdote

Dans les systèmes de trading “qui tiennent”, **aucune stratégie ne sait d’où vient la donnée**.  
Quand tu peux changer Binance par un replay CSV sans toucher une ligne de stratégie…  
c’est que ton architecture est saine 😉

👉 Prochaine brique naturelle :  
**définir précisément les règles de ***complete*** par timeframe (marchés ouverts / fermés)**.
