---
statut: archivé
sujet: Spec initiale — Decision
archivé_le: 2026-11-10
raison: Spec de départ (jan-fév 2026) antérieure à l'implémentation ; l'état courant est dans architecture/
modifié_le: 2026-11-10
empreinte: a6f1f832d6
---
# Decision

> ⚠ **ARCHIVÉ le 2026-11-10** — spec initiale, souvent dépassée par le code. Original : `../sources/specs-initiales/Decision.odt`. Ne jamais lire comme référence de l'état courant.

### Organisation proposée – couche **Decision**

**Vue d’ensemble (schéma logique)**

*DecisionEngine*

*   └── DecisionRegistry*

*           └── Decision (interface)*

*                 └── AbstractDecision (optionnel)*

*                        └── ConcreteDecision(s)*

*                               └── List\<Strategy\>*

*                                      └── StrategyEngine / Strategy*

### Description des classes

**DecisionEngine**  
Point d’entrée de la couche Decision.

  - Reçoit le **DecisionContext** + **DecisionParameters** génériques
  - Enrichit le contexte (wallet, profil user, risk, mode exit, renforcement…)
  - Sélectionne et exécute une ou plusieurs Decision via le registry

**DecisionRegistry**  
Registre central des décisions disponibles.

  - Lookup par *DecisionType*
  - Permet d’activer plusieurs décisions en parallèle
  - Découple le moteur des implémentations concrètes

**Decision (interface)**  
Contrat commun à toutes les décisions.

  - Déclare la méthode *evaluate(...)* ou *decide(...)*
  - Ne contient aucune logique technique (orchestration uniquement)

**AbstractDecision (optionnelle mais recommandée)**  
Base commune aux décisions.

  - Gestion de la liste de *Strategy*
  - Méthodes utilitaires (filtrage, agrégation de signaux…)
  - Évite la duplication entre décisions

**ConcreteDecision (ex : RiskDecision, ExitMarketDecision…)**  
Implémentation métier réelle.

  - Définit quelles stratégies sont utilisées
  - Interprète leurs signaux pour produire une **DecisionResult**
  - Ne connaît pas le moteur

**Strategy**  
Déjà existant.

  - Une Decision peut en agréger plusieurs
  - La Decision orchestre, la Strategy calcule

**DecisionContext**  
Contexte enrichi côté Decision.

  - MarketContext
  - WalletSnapshot
  - UserProfile (risk, objectifs…)
  - États globaux (exiting market, renforcement actif…)

**DecisionParameters**  
Paramètres génériques et configurables.

  - Seuils
  - Modes d’exécution
  - Overrides user / strategy

**DecisionResult**  
Résultat d’une décision.

  - Actions proposées (BUY / SELL / HOLD / ADJUST)
  - Niveau de confiance / priorité
  - Métadonnées (quelle stratégie a pesé)

👉 Cette organisation garde **exactement la même philosophie** que Indicator / Strategy :  
contrats clairs, registry, moteur d’orchestration, logique métier isolée.

**Anecdote**  
Sur certains bots pro, la couche “Decision” est souvent ajoutée trop tard… et finit par devenir un gros *if/else*. Là, tu poses directement une vraie couche d’orchestration propre — très bon réflexe.

\#\#\# Synthèse

Je verrais la structure comme \*\*un système nerveux\*\* :

\* les \*\*indicateurs\*\* perçoivent

\* les \*\*stratégies\*\* interprètent

\* le \*\*moteur de décision\*\* arbitre

\* l’\*\*exécution\*\* agit (ou s’abstient)

L’objectif n’est pas de “trouver le bon signal”, mais de \*\*prendre une décision globale cohérente\*\* à partir de signaux partiels, parfois contradictoires.

\---

\#\# 1️⃣ Les grandes briques (responsabilités claires)

\#\#\# ① Indicator

\*\*Pourquoi\*\*

Un indicateur ne doit jamais décider. Il décrit l’état du monde.

\*\*Responsabilité\*\*

\* produire une mesure

\* être déterministe

\* ne rien connaître du DCA, du user, ni de l’argent

\*\*Conceptuellement\*\*

\> \*“Voici ce que j’observe, maintenant.”\*

\---

\#\#\# ② IndicatorSnapshot

\*\*Pourquoi\*\*

Les stratégies ne doivent pas recalculer les indicateurs.

\*\*Responsabilité\*\*

\* figer l’état du marché à un instant T

\* servir de base commune à toutes les stratégies

\*\*Conceptuellement\*\*

\> \*“À cet instant, le marché ressemble à ça.”\*

\---

\#\#\# ③ Strategy

\*\*Pourquoi\*\*

Une stratégie est une \*\*opinion\*\*, pas une action.

\*\*Responsabilité\*\*

\* lire le snapshot

\* exprimer une conviction graduée

\* pouvoir être ignorée

\*\*Elle ne fait jamais :\*\*

\* d’ordre

\* d’accès exchange

\* de logique temporelle globale

\*\*Conceptuellement\*\*

\> \*“Dans ce contexte, je penche plutôt pour…”\*

\---

\#\#\# ④ StrategySignal

\*\*Pourquoi\*\*

Tout signal doit être contextualisé.

\*\*Responsabilité\*\*

\* score directionnel

\* confiance

\* horizon (court / moyen / long)

\* optionalement : type de risque

\*\*Conceptuellement\*\*

\> \*“Je suis confiant à 70% que renforcer légèrement est pertinent.”\*

\---

\#\#\# ⑤ StrategyAggregator

\*\*Pourquoi\*\*

Aucune stratégie ne doit avoir le dernier mot.

\*\*Responsabilité\*\*

\* agréger

\* lisser

\* neutraliser les extrêmes

\* détecter les conflits

\*\*Conceptuellement\*\*

\> \*“Globalement, que racontent les stratégies ?”\*

\---

\#\#\# ⑥ DecisionContext

\*\*Pourquoi\*\*

La décision dépend aussi de \*\*toi\*\*, pas que du marché.

\*\*Responsabilité\*\*

\* état du wallet

\* historique des actions

\* exposition actuelle

\* règles de risque

\*\*Conceptuellement\*\*

\> \*“Avec ce portefeuille, ce user, à ce moment…”\*

\---

\#\#\# ⑦ DecisionEngine (le cerveau)

\*\*Pourquoi\*\*

C’est le seul endroit où une action peut émerger.

\*\*Responsabilité\*\*

\* combiner :

  \* score global

  \* contexte user

  \* contraintes de risque

\* décider :

  \* agir

  \* moduler

  \* s’abstenir

\*\*Conceptuellement\*\*

\> \*“Voici ce que je fais, ou pourquoi je ne fais rien.”\*

\---

\#\# 2️⃣ Comment obtenir une décision globale cohérente

\#\#\# Étape 1 — Perception unique

Tous les indicateurs sont calculés \*\*une seule fois\*\*

→ un snapshot partagé

\> Évite les décisions incohérentes dues à des calculs décalés.

\---

\#\#\# Étape 2 — Opinions indépendantes

Chaque stratégie :

\* lit le même snapshot

\* produit son signal

\* ignore les autres

\> Isolation = robustesse

\---

\#\#\# Étape 3 — Agrégation non linéaire

L’agrégateur :

\* pondère

\* atténue

\* plafonne

Exemples conceptuels :

\* 5 stratégies faiblement positives \> 1 très positive

\* 1 stratégie très négative peut bloquer tout le reste

\* trop de divergence → neutralité

\> Le conflit est un signal en soi.

\---

\#\#\# Étape 4 — Décision sous contraintes

Le moteur :

\* adapte l’intensité

\* vérifie les règles de survie

\* peut dire \*non\*

\> Un bon bot sait \*\*quand ne pas jouer\*\*.

\---

\#\# 3️⃣ Pourquoi cette structure tient dans le temps

\* Tu peux :

  \* ajouter un indicateur sans toucher au reste

  \* désactiver une stratégie sans casser la décision

  \* changer la logique DCA sans réécrire les signaux

\* Elle est :

  \* testable

  \* explicable

  \* observable (logs, métriques)

\---

\#\# 4️⃣ Le point vraiment important (et souvent oublié)

La \*\*décision n’est pas un calcul\*\*, c’est un \*\*processus\*\*.

Si tu peux répondre à :

\> \*“Pourquoi le bot a acheté ici ?”\*

alors ton architecture est saine.

\---

\#\#\# Anecdote pour conclure

Les systèmes de trading qui survivent longtemps sont rarement les plus “intelligents”, mais ceux dont les responsabilités sont \*\*impossibles à confondre\*\*. Tu es clairement sur cette voie.

Si tu veux, au prochain échange on peut :

\* simuler une journée complète de décision

\* réfléchir à la gestion des conflits entre stratégies

\* ou définir ce qui mérite d’être loggé pour comprendre le bot a posteriori
