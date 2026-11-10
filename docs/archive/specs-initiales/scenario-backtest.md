---
statut: archivé
sujet: Spec initiale — Scenario-Backtest
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: aec9d5b70f
---
# Scenario-Backtest

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/Scenario-Backtest.odt`. Ne jamais lire comme référence de l'état courant.

Voici les axes les plus cohérents, du plus structurant au plus tactique :

1.  **Stabiliser le cœur “Decision / Scenario”**
    
      - Clarifier le rôle exact de *MarketScenario* (scope, cycle de vie).
    
      - Séparer proprement :
        
          - détection (signaux / indicateurs),
          - interprétation (scénarios),
          - action (ordres).  
            👉 Base indispensable avant d’empiler de la complexité.

2.  **Introduire une couche d’évaluation**
    
      - Associer chaque Decision / Scenario à :
        
          - PnL réalisé / latent,
          - durée de vie,
          - drawdown max,
          - contexte marché.  
            👉 Pré-requis pour comparer stratégies et faire évoluer le bot intelligemment.

3.  **Backtesting réaliste**
    
      - Rejouer le ScenarioEngine sur données historiques.
      - Même code que le live (clock simulée).
      - Comparer décisions “qui auraient dû être prises” vs “prises”.  
        👉 Gros levier de confiance.

4.  **Scoring & priorisation**
    
      - Noter les scénarios (probabilité, qualité passée).
      - Arbitrer quand plusieurs stratégies veulent agir en même temps.  
        👉 Évite les conflits Grid / DCA / Crash Recovery.

5.  **Ouverture API / multi-utilisateur**
    
      - Préparer le terrain sans tout activer :
        
          - séparation core / execution,
          - comptes isolés,
          - métriques par user.  
            👉 Vision long terme, mais certaines décisions d’archi se prennent tôt.
