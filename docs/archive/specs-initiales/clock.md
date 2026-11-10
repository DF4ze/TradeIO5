---
statut: archivé
sujet: Spec initiale — Clock
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: 3bef0df768
---
# Clock

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/Clock.odt`. Ne jamais lire comme référence de l'état courant.

# Formalisation de la Clock dans TradeIO

## 1\. Objectif

Introduire une gestion **centralisée, explicite et contrôlée du temps** afin de :

  - Garantir la **reproductibilité** (backtesting, simulations)
  - Séparer clairement le **temps métier** du **temps système**
  - Éviter toute dépendance implicite à *Instant.now()* dans la logique cœur

## 2\. Principe fondamental

> **Aucune logique métier ne doit appeler ***Instant.now()*** directement.**

Le temps devient :

  - soit **injecté** (paramètre explicite)
  - soit **fourni par une Clock abstraite**

## 3\. Les deux notions de temps

### 3.1 Temps métier (Domain Time)

Utilisé pour :

  - MarketDataset
  - Decisions / DecisionContext
  - Indicators
  - Cache métier
  - Backtesting

➡️ Source : *****endTime*** d’une requête** ou **Clock métier**

### 3.2 Temps système (System Time)

Utilisé pour :

  - createdAt / updatedAt techniques
  - audit
  - logs
  - initialisation applicative

➡️ Source : *Clock.systemUTC()* ou équivalent

## 4\. Interface Clock

*public interface DomainClock {*

*    Instant now();*

*}*

## 5\. Implémentations

### 5.1 Clock système (production)

*@Component*

*public class SystemDomainClock implements DomainClock {*

*    @Override*

*    public Instant now() {*

*        return Instant.now();*

*    }*

*}*

Usage :

  - Live trading
  - Execution temps réel

### 5.2 Clock figée (tests / backtesting)

*public class FixedDomainClock implements DomainClock {*

*    private Instant current;*

*    public FixedDomainClock(Instant start) {*

*        this.current = start;*

*    }*

*    @Override*

*    public Instant now() {*

*        return current;*

*    }*

*    public void set(Instant instant) {*

*        this.current = instant;*

*    }*

*    public void advance(Duration duration) {*

*        this.current = this.current.plus(duration);*

*    }*

*}*

## 6\. Règles d’utilisation

### Règle 1 — Request first

Si une méthode reçoit un *Instant* explicite (ex: *endTime*) :  
➡️ **Il prime sur la Clock**

*Instant now = request.endTime();*

### Règle 2 — Pas de now() caché

❌ Mauvais :

*state.setLastUpdate(Instant.now());*

✅ Bon :

*state.setLastUpdate(now);*

### Règle 3 — Engine = point d’ancrage temporel

Les Engines (MarketDatasetEngine, DecisionEngine, etc.) sont responsables de :

  - déterminer le **temps courant métier**
  - le propager à tous les composants appelés

## 7\. Application aux composants existants

### MarketDatasetEngine

  - *now* = *request.endTime()*
  - Aucun appel direct à la Clock

### MarketDatasetManager / State

  - Reçoivent toujours un *Instant providedNow*
  - Ne calculent jamais le temps eux-mêmes

### DecisionContext

  - Doit **toujours** contenir un *Instant now*
  - Jamais de valeur par défaut implicite

## 8\. Tests unitaires

  - Ne pas mocker le temps
  - Utiliser une *FixedDomainClock*
  - Avancer le temps explicitement

➡️ Tests lisibles, déterministes, sans *Thread.sleep()*

## 9\. Bénéfices obtenus

  - Backtesting fiable
  - Comparaison live / simulé possible
  - Debug temporel simple
  - Élimination d’une classe entière de bugs subtils

## 10\. Étape suivante naturelle

Une fois la Clock formalisée :

1.  Backtesting Engine
2.  Versionning config + résultats
3.  Métriques de performance (PnL, drawdown, etc.)

➡️ La Clock est le **socle** de tout le reste.
