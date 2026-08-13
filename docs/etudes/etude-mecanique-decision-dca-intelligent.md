# Étude — Mécanique de décision & DCA intelligent

Chantier demandé par Clem le 2026-08-10, faisant suite à `docs/suivi/point-avancement-2026-08-10.md`
§6 (plan d'action décision → ordre). Périmètre : tout ce qui vit "sous" les Opinions — Scenario,
Decision, sizing, exécution — avec pour finalité produit un **DCA intelligent** multi-actifs
(BTC/ETH/PAXG), pilotable par profil de risque et par wallet, dans une application **multi-utilisateurs**,
et **backtestable** de bout en bout.

> **Principe directeur (ajouté le 2026-08-10, relecture Clem).** Une grande partie des valeurs et
> même des choix de structure décrits en §2 (analyse de l'existant) sont des **placeholders** :
> `EXPIRATION_IDLE = 2h`, seuils `0.7`/`0.9`, poids de blend `0.7/0.3`, `DecisionType.EXIT` codé en
> dur, `RiskProfile` en enum à 3 paliers... Le fait qu'une classe/un attribut/une implémentation
> existe déjà **ne les rend pas acquis**. Cette étude sert justement à remettre en question
> l'existant face au besoin, pas à l'entériner parce qu'il est déjà là.

## Sommaire

1. [Cadrage du besoin](#1-cadrage-du-besoin)
2. [Analyse de l'existant](#2-analyse-de-lexistant)
3. [Confrontation besoin ↔ existant](#3-confrontation-besoin--existant)
4. [Le sizing par profil de risque](#4-le-sizing-par-profil-de-risque)
5. [Rôle des wallets](#5-rôle-des-wallets)
6. [Multi-utilisateur](#6-multi-utilisateur)
7. [DCA intelligent — comment le brancher](#7-dca-intelligent--comment-le-brancher)
8. [Backtestabilité](#8-backtestabilité)
9. [Points de vigilance transverses](#9-points-de-vigilance-transverses)
10. [Questions ouvertes](#10-questions-ouvertes)
11. [Par où commencer ?](#11-par-où-commencer-)
12. [Analyse critique — incohérences fonctionnelles](#12-analyse-critique--incohérences-fonctionnelles)

Chaque chapitre est volontairement gardé court ici ; l'idée (proposée par Clem) est d'approfondir
un chapitre à la fois dans les échanges suivants plutôt que de tout figer d'un coup.

---

## 1. Cadrage du besoin

- **Objectif produit** : un DCA qui n'est pas une cadence aveugle (acheter X€ tous les jours peu
  importe le prix) mais un DCA *modulé* — acheter davantage dans les creux, réduire/prendre des
  profits partiels dans les sommets, tout en gardant un biais structurel d'accumulation (on ne
  cherche pas à "trader" activement, l'app doit rester DCA-first).
- **Périmètre actifs** : BTC, ETH, PAXG. Les trois sont déjà seedés dans `AssetInitializer` avec
  leurs `AssetProvider` (Binance/Kraken/OKX) — pas de travail de sourcing de données à prévoir pour
  démarrer ce chantier.
- **Contrôle utilisateur** :
  - Profil de risque conservateur ↔ agressif (impacte la taille des ordres, l'agressivité du
    "acheter le creux").
  - Rôle des wallets (un utilisateur a plusieurs wallets — Binance, Kraken, hardware wallet... —
    et veut sans doute leur donner des rôles différents, ex. "wallet d'accumulation long terme" vs
    "wallet satellite plus actif").
- **Contrainte transverse** : l'app est multi-utilisateurs (pas mono-utilisateur comme le code le
  traite implicitement aujourd'hui par endroits, cf. §6).
- **Contrainte non négociable** : tout l'algorithme doit être **backtestable** — donc conçu pour
  tourner aussi bien en mode "décision réelle live" qu'en mode "rejoue-moi cet historique et donne-moi
  une performance chiffrée", sans deux implémentations parallèles qui divergent.

## 2. Analyse de l'existant

### 2.1 La chaîne existe, "en pointillés"

Le pipeline `Indicator → Strategy → Opinion → Scenario → Decision → ActionStep` existe réellement
en code, pas juste sur le papier :

- `OpinionEvent` → `DefaultScenarioEngine.onOpinionEvent` fait vivre des `MarketScenario`
  (`DefaultMarketScenario`) à travers une machine à états `INITIAL → EMERGING → CONFIRMING →
  VALIDATED (→ INVALIDATED/EXPIRED)`, pilotée par la confiance cumulée des Opinions reçues.
- Un scénario `VALIDATED` + stable propose un `ActionIntent` (`proposeIntent`).
- `DecisionEngine.onScenarioEvent` transforme un `ActionIntent` en `Decision` (avec un arbitrage
  d'unanimité inter-scopes LOCAL/EXTERNAL déjà en place — `isUnanimousAcrossScopes`).
- `Decision` a un cycle de vie événementiel propre (`CREATED → EXECUTED/ABORTED`) piloté par
  `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED`.

C'est une base saine : event-driven, testée (465 tests au 2026-08-11, après le Palier 1 — cf.
§2.2), avec une séparation claire des responsabilités. **Ce n'est pas à jeter.**

*Un schéma interactif de cette chaîne (Opinion → Scenario → ActionIntent → Decision → ActionStep →
Exécution, avec le statut de chaque maillon) a été présenté à Clem en chat le 2026-08-10 — à
régénérer si besoin plutôt que reconstruit ici en texte, cf. mémoire
`tradeio5_decision_dca_intelligent_etude`.*

### 2.2 3 maillons critiques étaient des coquilles vides — résolus au Palier 1 (2026-08-11)

Déjà identifiés dans le point d'avancement (§5, TODO #1-3), confirmés à la lecture, et corrigés
depuis via `docs/prompts/prompt-implementation-decision-palier1.md` (465 tests OK, dont 7 nouveaux) :

- `DefaultMarketScenario.proposeIntent` : `new BigDecimal(0.0)` — **la quantité n'était jamais
  calculée**, toujours zéro. **Résolu** : remplacé par une constante `PLACEHOLDER_QUANTITY`
  (`BigDecimal.ONE`) explicitement documentée comme placeholder — ce n'est toujours **pas** le
  sizing réel (§4/§7/§12 pt 7 ci-dessous, chantier entier à part), juste une valeur non nulle et
  non dégénérée pour que le pipeline soit testable de bout en bout.
- `DecisionEngine.createDecision`/`mapToCandidate` : `DecisionType.EXIT` codé en dur à **deux**
  endroits, quelle que soit l'action réelle (BUY/SELL) — alors que `DecisionType` a
  `ENTER`/`EXIT`/`REBALANCE`/`STOP`. **Résolu** : `mapDecisionType(ExecutionAction)` (BUY→ENTER,
  SELL/EXIT/NO_OP→EXIT) utilisé aux deux sites ; `REBALANCE`/`STOP` restent volontairement hors
  scope de ce mapping (nécessitent l'état du portefeuille, pas encore disponible ici).
- `DefaultScenarioEngine.collectActionIntents` : aucune dédup — un scénario stable continuait de
  proposer le même `ActionIntent` à chaque cycle tant qu'il restait `VALIDATED`, sans mémoire de ce
  qui avait déjà été proposé. **Résolu** : règle "un intent par épisode de validation continue"
  (`proposedScenarioIds`), reset dès que le scénario quitte `VALIDATED`/stable, purgé dans
  `cleanup(...)`.

Ce lot ne rend **pas** la chaîne exécutable en prod : `DecisionEngine`/`DefaultScenarioEngine`
restent de simples POJO non instanciés hors tests (pas d'annotation Spring, volontairement — cf.
§12 pt 1), pas de sizing réel, pas de persistance d'état. Il rend uniquement la logique interne
Scenario/Decision cohérente et testable en isolation.

### 2.3 Le pont "contexte utilisateur" n'est jamais construit

`OpinionContext` transporte `WalletSnapshot` + `UserProfile` jusqu'à l'advisor LLM — la forme est
là (`RiskProfile` LOW/MEDIUM/HIGH, `maxAllocationPerAsset`, `minCashReserve`,
`reinforcementActive` — ce dernier champ anticipe déjà la logique d'accumulation demandée ici).
Mais concrètement :

- `TreeAnalysisFacade.getOpinionCommon` construit `WalletSnapshot.builder().build()` et
  `UserProfile.builder().build()` — **tout est à sa valeur par défaut**, peu importe l'utilisateur.
- `AbstractAdvisor.userProfileBlock`/`walletBlock` retournent `""` — même peuplés, ces DTO ne
  seraient pas injectés dans le prompt LLM.
- Rien ne fait le pont entre les balances réelles (`Wallet` entity, `ProviderApiService`,
  `BalanceCacheManager` — infra qui existe et fonctionne) et `WalletSnapshot`.
- `User` (JPA) n'a **aucun champ de profil de risque** — `RiskProfile` n'est stocké nulle part côté
  utilisateur.

### 2.4 L'exécution réelle n'existe pas

`ProviderApiService.buy()`/`sell()` :

```java
public boolean buy(Wallet wallet, BigDecimal amount, String asset) { return true; }
public boolean sell(Wallet wallet, BigDecimal amount, String asset) { return true; }
```

Des stubs qui renvoient toujours `true`. Aucun appel réel à `BinanceApiClient`/`KrakenApiClient`
pour poser un ordre. Séparément, personne n'émet jamais `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED`
— `Decision` reste un état figé à `CREATED` en pratique. C'est un stub d'état de bout en bout, pas
une exécution partielle.

### 2.5 Le DCA existant est un simulateur isolé, pas un mécanisme de décision

`DcaCalculatorService` (exposé via le tool MCP `calculate_dca`) est solide dans ce qu'il fait :
calendrier calendaire fixe (D1/W1/M1/...), prix moyen pondéré, PnL, avec gestion propre des limites
d'historique par provider. Mais :

- C'est une **simulation à cadence fixe et montant fixe** — aucune notion de "creux"/"sommet",
  aucun branchement sur les Opinions/Strategies existantes.
- Il ne produit ni `Decision` ni `ActionStep` : c'est un outil d'analyse a posteriori
  ("si j'avais fait X€/mois depuis telle date"), pas un composant qui pilote de vrais achats.
- Il n'a aucune notion de wallet, d'utilisateur, ni de profil de risque.

Le "DCA intelligent" demandé ici n'est donc pas une extension de `DcaCalculatorService` mais un
**nouveau mécanisme de décision** à part entière, que `DcaCalculatorService` pourra éventuellement
réutiliser côté market-data (fetch de bougies, gestion multi-provider) mais pas côté logique.

### 2.6 Pas de moteur de backtest générique pour la chaîne de décision

Aujourd'hui, "backtester" une Strategy/Opinion se fait par des scripts de calibration ad hoc
(`docs/calibration/*.md` — ex. `calibration-rejection-zone.md`, `calibration-etf-flow.md`),
généralement un script one-shot qui rejoue une série historique et calcule un taux de réaction, pas
un moteur réutilisable. Le seul concept de "mode backtest" dans le code de production est
`BacktestExecutionPolicy` (`service/market/dataset/execution/`), qui ne concerne que la **façon de
lire les données de marché** (bornes de dates figées vs glissantes), pas l'exécution de la chaîne
Opinion→Scenario→Decision sur un historique. Il n'existe aucun harnais qui, pour une période donnée,
rejoue tick par tick/bougie par bougie la mécanique complète et produise des métriques de
performance (cf. §8).

## 3. Confrontation besoin ↔ existant

| | Garder tel quel | Améliorer | Jeter / repenser | Vigilance |
|---|---|---|---|---|
| Scenario (état, machine à états, event-driven) | ✅ base saine, testée | seuils codés en dur à externaliser (§5 pt avancement) | — | le `ScenarioType` actuel (TREND_UP, RANGE, CRASH...) est un régime de marché, pas un concept d'accumulation — ne pas essayer d'y faire rentrer le DCA de force |
| Decision / ActionStep (cycle de vie événementiel) | ✅ structure | ~~`DecisionType` à corriger (TODO #3), dédup (TODO #2)~~ fait au Palier 1 (§2.2) | — | le cycle `CREATED→EXECUTED/ABORTED` suppose un ActionStep = un ordre ; le DCA intelligent proposera probablement des `Decision` de type nouveau (accumulation régulière) qu'il faut faire rentrer dans ce modèle sans le dénaturer |
| `UserProfile`/`WalletSnapshot`/`RiskProfile` (DTO) | ✅ forme correcte | à peupler réellement (§6.2 pt 2-3 pt avancement) | — | `WalletSnapshot` actuel n'a pas de notion de wallet *individuel* (un seul agrégat par user) — incompatible avec "rôle par wallet" (§5) sans extension |
| `DcaCalculatorService` | ✅ garder comme outil de simulation "cadence fixe" (utile en comparaison / baseline) | — | ne pas le faire porter le DCA intelligent : nouveau composant | risque de duplication de logique de fetch de bougies si mal articulé avec le futur moteur de backtest (§8) |
| `ProviderApiService.buy/sell` | — | à écrire réellement | stub à remplacer, pas à garder | **le plus sensible** : premier point où de l'argent réel bouge — dry-run obligatoire avant tout branchement (déjà noté §6.2 pt 5 du point d'avancement) |
| Backtest | — | — | rien à jeter (il n'existe pas) | construire le moteur de backtest **en même temps** que la mécanique de décision, pas après — sinon nouvelle dette (cf. §8) |
| Multi-user | `ScenarioOwner.UserOwner` déjà threadé dans Scenario/Decision | `User` JPA à enrichir (risk profile, settings) | — | deux notions d'utilisateur cohabitent aujourd'hui : `ScenarioOwner.UserOwner(userId: String)` côté decision engine, et `User` (JPA, `Long id`) côté sécurité/wallet — à unifier explicitement, pas supposer qu'elles coïncident |

## 4. Le sizing par profil de risque

Question déjà posée dans le point d'avancement (§6.3), reprise et précisée ici avec la contrainte
DCA.

### Approfondi le 2026-08-10 — exit l'enum, place à un curseur continu

Clem écarte l'enum `RiskProfile` (LOW/MEDIUM/HIGH) comme mécanisme de pilotage final : il se
représente le réglage plutôt comme un **curseur continu de 0 (conservateur) à 10 (super agressif)**,
manipulable par l'utilisateur (visualisation type slider). L'enum à 3 paliers reste éventuellement
utile comme *catégorie affichée* (dérivée du curseur pour l'UI, ex. 0-3 = conservateur), mais plus
comme source de vérité du calcul.

Deux sous-questions distinctes à garder ouvertes, toutes deux "pondérées par la situation" (sinon,
comme le dit Clem, "ce n'est plus un DCA intelligent") :

1. **Montant fixe vs pourcentage de liquidité** — le curseur pilote-t-il un montant absolu par
   échéance (ex. 50€ à 500€ selon la position sur le curseur), ou un pourcentage de liquidité
   (restante sur le wallet cible, ou globale au portefeuille) ? Les deux sont plausibles, pas
   tranché — un pourcentage de liquidité a l'avantage de s'auto-adapter à la taille du portefeuille
   dans le temps, un montant fixe est plus simple à comprendre pour l'utilisateur.
2. **La modulation "intelligente" peut être nulle, voire négative (vente)** — correction d'une
   erreur de cadrage du brouillon précédent (§7.4) : il ne faut *pas* supposer un plancher d'achat
   non nul. En plein rush bullish, ce n'est justement pas le moment d'acheter, potentiellement même
   le moment de vendre. Le curseur de risque et le score de valorisation (§7) doivent pouvoir
   produire un montant d'achat nul un jour donné, ou basculer en ordre de vente — pas juste réduire
   l'intensité d'achat vers un minimum.

## 5. Rôle des wallets

Concept absent du code aujourd'hui. `Wallet` (entity) a `name`, `source`, `webProviderCode`,
`credential`, `user` — c'est une identité technique (à quel exchange/adresse ça correspond), pas un
rôle fonctionnel. `WalletSnapshot` (DTO decision) agrège balances/positions/valeur — pas de notion
de wallet individuel du tout, encore moins de rôle.

Trou de modélisation à combler dans tous les cas : `Decision`/`ActionStep` ne référencent aujourd'hui
aucun wallet, seulement un `symbol` et un `owner` (utilisateur) — "acheter 0.01 BTC" doit devenir
"acheter 0.01 BTC **sur le wallet X**" pour que `ProviderApiService` sache quel provider/credential
utiliser.

### Approfondi le 2026-08-10 — vers une taxonomie riche, pas 2 rôles figés

Clem confirme vouloir plus qu'une étiquette binaire "accumulation / satellite". Plutôt que de lister
des rôles fermés a priori, il semble plus solide d'identifier les **axes indépendants** qui composent
un rôle — un "rôle riche" est vraisemblablement une combinaison de plusieurs axes, pas une seule
étiquette :

- **Axe horizon/vocation** : accumulation long terme (jamais vendu par l'algo) / satellite tactique
  (peut être allégé) / réserve de cash (destination des prises de profit, peut alimenter le DCA d'un
  autre wallet) / suivi seul (wallet visible mais jamais piloté par l'algo — ex. wallet froid géré à
  la main).
- **Axe actions autorisées** : `BUY_ONLY` / `BUY_AND_SELL` / `SELL_ONLY` (ex. wallet en sortie
  progressive) / `READ_ONLY`.
- **Axe actifs autorisés** : wallet dédié à un seul actif (ex. "coffre BTC") ou ouvert à plusieurs
  (BTC/ETH/PAXG) avec des poids cibles différents par actif.
- **Axe override du profil de risque** : le `RiskProfile` est-il uniquement global (un par
  utilisateur) ou surchageable par wallet (ex. profil global MEDIUM, wallet "satellite" en HIGH) ?
- **Axe priorité d'allocation** : quand plusieurs wallets sont éligibles pour le même actif, lequel
  reçoit l'ordre en premier — même logique que le champ `priority` déjà utilisé sur `AssetProvider`.

Piste de modélisation (à valider, pas figée) : plutôt qu'un `enum WalletRole` fermé, une **politique
par wallet** (nouvelle entité, ex. `WalletPolicy` liée à `Wallet`) qui combine ces axes — même esprit
que `UserProfile` aujourd'hui (un faisceau de règles, pas une étiquette unique).

Question à garder ouverte, pas à trancher maintenant : le rôle se définit-il **par wallet** (le
wallet entier a une vocation) ou **par couple wallet+actif** (le même wallet Binance "accumulation"
pour BTC mais "satellite" pour un autre actif) ? La deuxième option est plus flexible mais
complexifie tout le reste (sizing, exécution, backtest) — à ne trancher qu'une fois le besoin réel
observé, pas par anticipation.

## 6. Multi-utilisateur

Deux mondes à réconcilier, identifiés en §3 :

- **Côté sécurité/patrimoine** : `User` (JPA), `Wallet.user`, `ApiCredential` — multi-utilisateur
  réel et fonctionnel (chaque wallet appartient à un `User`).
- **Côté decision engine** : `ScenarioOwner.UserOwner(String userId)` — déjà conçu pour être
  multi-utilisateur (`isVisible` filtre par owner, `SystemOwner` pour le global), mais jamais
  connecté à un vrai `User.id`. Aucun test ni code de prod n'instancie aujourd'hui plusieurs
  `DecisionEngine`/`ScenarioEngine` pour plusieurs utilisateurs simultanément — l'architecture le
  permet sur le papier, mais c'est non vérifié en pratique.

Implication directe pour ce chantier : peupler `WalletSnapshot`/`UserProfile` "pour de vrai" (§6.2
pt 2-3 du point d'avancement) doit se faire **par utilisateur dès le départ**, pas comme un
singleton global qu'on multiplierait après coup — sinon on recrée la même dette que celle qu'on est
en train de combler.

### Approfondi le 2026-08-10 — pressenti comme prochaine vague d'implémentation

Intuition de Clem, à valider en marche plutôt qu'à figer ici : le multi-utilisateur touche presque
tous les autres chapitres (WalletSnapshot par user, politique de wallet par user, sizing piloté par
un curseur *par* utilisateur, DCA par user+wallet+actif) — commencer à monter en compétence dessus
avant d'écrire la mécanique du Decision Engine limite le risque de devoir tout reprendre en
découvrant après coup une incompatibilité structurelle. Développé en piste concrète au §11.

## 7. DCA intelligent — comment le brancher

### Approfondi le 2026-08-10 — briques à discuter avant de trancher l'architecture

Clem préfère explorer ensemble plutôt que choisir entre deux architectures figées tout de suite.
Voici les briques qui composent le problème — une fois qu'on est d'accord dessus, l'architecture
(nouveau mécanisme, extension du Scenario existant, ou autre) devient une conséquence plutôt qu'un
choix arbitraire.

#### 7.1 Deux questions, pas forcément le même rythme (mis à jour le 2026-08-10)

Le "DCA intelligent" mélange en réalité 2 questions qui n'ont pas forcément la même réponse :

1. **Quand évaluer (cadence)** — Clem confirme préférer une cadence **journalière**, mais hebdo et
   mensuel restent des cadences valides selon le profil de l'utilisateur/du wallet : ce doit rester
   un paramètre, pas une valeur figée. La cadence détermine seulement *quand on réévalue*, pas si
   on agit ce jour-là (cf. point 2).
2. **Que faire à cette échéance (intensité, un seul axe continu du buy fort à la vente)** — le cœur
   du "acheter le creux / vendre le sommet". Clem a déjà posé une échelle de règles DCA concrète,
   à reprendre comme point de départ plutôt qu'un multiplicateur borné abstrait :

   - Achat x3
   - Achat x2
   - Achat normal (montant de base)
   - Achat x0.5
   - Pas d'achat
   - Vente

   Point important : **il peut donc y avoir des jours sans aucun achat, voire des jours de vente**
   (et dans ce cas, logiquement, pas d'achat ce même jour). Ce n'est *pas* à requalifier en "market
   timing" au sens négatif du terme (le §7.1 précédent l'écartait à tort comme tel) — c'est une
   conséquence normale d'un DCA qui module réellement son intensité, à prendre en compte dans la
   conception plutôt qu'à exclure. Le rôle du wallet (§5) vient ensuite *plafonner* cette échelle :
   un wallet `BUY_ONLY` verrait par exemple la case "Vente" transformée en "Pas d'achat".

#### 7.2 Quelles données nourrissent le score de "creux/sommet" ?

Pas besoin de nouveaux indicateurs pour démarrer, mais les candidats existants ne sont pas tous du
même registre temporel — un DCA doit rester ancré long terme :

- **Candidats "valorisation / cycle long terme"** (a priori les plus pertinents pour moduler un
  DCA) : `RainbowSmaIndicator` (bandes de valorisation par régression, conçu justement pour situer
  le cycle), Fear & Greed aux extrêmes, zones de rejet techniques en pondération basse
  (`RejectionZoneIndicator` — cluster 81k-90k déjà identifié, cf. mémoire), ETF flow J-1
  (confirmation institutionnelle).
- **Candidats court terme** (RSI/MACD/Bollinger sur H1/H4...) : probablement à exclure ou à
  pondérer très faible — un DCA qui réagit au bruit court terme cesse d'être un DCA. Point de
  vigilance à garder plutôt qu'un choix à faire immédiatement.

Sous-question à approfondir avec toi plus tard : réutiliser directement le pipeline `Opinion`/
`Strategy` existant (ex. un `OpinionScope` dédié "ACCUMULATION", au même niveau que
LOCAL/EXTERNAL/GLOBAL/MACRO) — cohérent, moins de code, mais couple le DCA à des Opinions pensées à
l'origine pour du signal directionnel court/moyen terme — ou construire un score de valorisation
séparé, plus simple, dédié et découplé.

#### 7.3 La state machine `Scenario` a probablement les bons concepts, avec des valeurs placeholder

`DefaultMarketScenario` expire après 2h d'inactivité (`EXPIRATION_IDLE`) et a une maturation binaire
(`VALIDATED` + stable → propose *un* intent). Une intention d'accumulation, elle :

- doit **vivre en permanence** tant que le wallet cible a une politique "accumulation active" — pas
  s'éteindre après une courte durée sans nouvelle Opinion ;
- a besoin d'un **score continu** (l'échelle x3→vente du §7.1), pas d'un booléen validé/pas-validé ;
- ne se déclenche qu'aux échéances de cadence (§7.1 point 1), pas à chaque événement de marché —
  alors que `Scenario`/`Decision` réagissent aujourd'hui event-by-event.

**Mise à jour 2026-08-10 (relecture Clem)** : `2h` pour `EXPIRATION_IDLE` est un pur placeholder,
bien trop court pour une lecture de marché à l'échelle d'un DCA — on parle plutôt de plusieurs jours,
voire une semaine, valeur à définir/calculer/backtester (cf. principe directeur en tête de doc).
Clem est d'accord sur le fond (un scénario doit exposer plus qu'un simple validé/invalidé) mais sa
première intuition est **qu'il ne faut pas construire de mécanisme parallèle** : c'est le mécanisme
`Scenario`/`Decision` existant — actuellement en cours de construction, pas figé — qui doit
s'adapter et se calibrer pour porter ce besoin, plutôt qu'un second système à côté. À confirmer une
fois qu'on aura commencé à implémenter concrètement (cf. §11) et qu'on touchera du doigt les
limites réelles de cette approche.

#### 7.4 Invariants, quelle que soit l'architecture retenue (mis à jour le 2026-08-10)

- **Backtestable par construction** : score de valorisation et déclenchement de cadence doivent
  être des fonctions pures du temps + de l'historique de marché (`DomainClock` +
  `MarketDatasetEngine`/`BacktestExecutionPolicy` déjà en place), rejouables à l'identique en live et
  en backtest — pas deux implémentations qui peuvent diverger (cf. §8).
- **Un seul axe d'intensité continu** (§7.1), pas deux mécanismes séparés "combien acheter" /
  "faut-il vendre" — l'échelle x3→vente de Clem est une seule graduation. À l'exécution, ça produit
  bien soit un ordre BUY soit un ordre SELL (jamais les deux le même jour), mais c'est une
  conséquence de la lecture du score, pas deux calculs indépendants. Cet axe reste filtré/plafonné
  par le rôle du wallet cible (§5).
- **Pas de plancher d'achat forcé** — correction du brouillon précédent : le score peut légitimement
  aboutir à "pas d'achat" voire à une vente (cf. §7.1 : ce n'est pas du market timing à exclure, c'est
  le comportement attendu). Le seul garde-fou dur reste le **plafond** déjà présent dans
  `UserProfile` (`maxAllocationPerAsset`/`minCashReserve`), pas un minimum bas.

Question ouverte ajoutée par Clem sur `reinforcementActive` (déjà présent dans `UserProfile`) : son
utilité reste à valider — ce booléen global "l'utilisateur veut renforcer certaines positions"
pourrait-il être remplacé entièrement par un **rôle temporaire sur un wallet/actif** (§5, axe
horizon/vocation) plutôt que de rester un champ séparé sur `UserProfile` ? À trancher une fois la
politique par wallet (§5) plus mature — ne pas dupliquer le même concept à deux endroits.

## 8. Backtestabilité

Contrainte transverse à trancher tôt, pas en fin de chantier.

**Précision de cadrage (2026-08-10, Clem)** : l'objectif n'est pas seulement de pouvoir backtester
la mécanique globale bout-en-bout, mais que **chaque mécanique individuelle qui aboutit à une
volonté d'achat/vente** — chaque "stratégie" au sens large (pas la classe `Strategy` au sens strict
du code : une Opinion, un Scenario, une règle de sizing...) — soit backtestable **indépendamment**,
pour pouvoir auditer son efficacité isolément. Ça implique de définir des **KPI communs** à toutes
ces mécaniques (points de contrôle sur des valeurs partagées), pas des métriques ad hoc différentes
à chaque fois : PnL global, max drawdown, etc. — un socle de mesure commun réutilisable, sur lequel
n'importe quelle brique (Opinion, Scenario, règle de sizing, DCA intelligent complet) peut être
évaluée et comparée aux autres.

Deux besoins à distinguer par ailleurs :

- **Backtest de la mécanique complète** (Opinion→Scenario→Decision→sizing) sur un historique : need
  un mode où `DomainClock` avance sur des dates passées (`FixedDomainClock` existe déjà), où le
  `MarketDatasetEngine` sert des données bornées dans le temps (`BacktestExecutionPolicy` existe
  déjà pour ça), et où l'exécution des `ActionStep` est simulée (jamais un vrai
  `ProviderApiService.buy`) — un "paper trading" historique. Rien de tout cela n'est aujourd'hui
  assemblé en un harnais unique.
- **Simulation DCA pure** (ce que fait déjà `DcaCalculatorService`) — à garder comme cas particulier
  / baseline de comparaison ("qu'aurait donné un DCA naïf sur la même période ?").

Sur "quels indicateurs de backtest exposer" — première liste à valider/enrichir avec Clem plutôt
qu'à figer seul ici :

- Prix moyen d'achat (déjà dans `DcaResult`) vs prix moyen d'un DCA naïf sur la même période (edge
  du "intelligent" vs cadence fixe).
- Total investi, valeur actuelle, PnL €/% (déjà dans `DcaResult`).
- Fréquence et taille réelle des ordres générés (pour vérifier que la modulation par profil de
  risque produit un comportement cohérent, pas juste un bruit).
- Max drawdown du portefeuille simulé sur la période.
- Un indicateur de "timing" : part des achats effectués sous le prix moyen de la période (mesure
  directe de la capacité à "acheter le creux").
- Comparaison à plusieurs positions du curseur de risque (§4) sur le même historique, pour valider
  que la hiérarchie du curseur produit bien une hiérarchie de résultats/volatilité cohérente.

Ce chapitre mérite un approfondissement dédié une fois le sizing (§4) tranché — les métriques
utiles dépendent directement de ce que le sizing cherche à optimiser.

## 9. Points de vigilance transverses

- **Argent réel** : `ProviderApiService.buy/sell` est le point de bascule vers de l'argent réel.
  Même en gardant le scope de cette étude à la mécanique de décision, ne pas perdre de vue que le
  point d'avancement demande explicitement un mode dry-run/simulation avant toute activation réelle
  (§6.2 pt 5).
- **Idempotence** : sans la dédup des `ActionIntent` déjà proposées (TODO #2), un DCA intelligent
  mal cadré pourrait proposer le même achat en boucle tant que la condition de marché reste vraie.
  Plus critique encore pour un DCA que pour un scénario ponctuel, puisque la logique tourne en
  continu par design.
- **Cohérence des seuils codés en dur** : plusieurs constantes (`CONFIRMATION_THRESHOLD`,
  `EXPIRATION_IDLE`, poids de blend `0.7/0.3`) sont aujourd'hui figées dans le code
  (`DefaultMarketScenario`) — de purs placeholders (cf. principe directeur en tête de doc), pas des
  valeurs calibrées. Deux actions distinctes, confirmées par Clem le 2026-08-10 : (1) une passe
  dédiée en mode backtest sera nécessaire pour déterminer les seuils réellement efficaces (pas les
  deviner) ; (2) leur **externalisation est indispensable** dans tous les cas — sortir ces constantes
  du code, qu'elles finissent globales, par profil de risque, ou par wallet reste à trancher, mais
  elles ne doivent plus vivre comme `static final` figées dans `DefaultMarketScenario`.
- **Granularité wallet vs user** : tant que `WalletSnapshot` reste un agrégat unique par
  utilisateur (§5), toute règle "par rôle de wallet" devra soit attendre l'extension du modèle, soit
  être posée comme prérequis explicite avant le sizing (§4).

## 10. Questions ouvertes

Reprises en fin de document, à trancher avant de coder quoi que ce soit (cf. questions posées à
Clem dans le chat).

## 11. Par où commencer ?

Ajouté le 2026-08-10, à la demande de Clem : il préfère avancer dans l'implémentation pour se
rendre compte concrètement des contraintes, plutôt que de tout spécifier à l'avance sur les points
critiques (sizing exact, architecture DCA finale...) — cf. §10, et le principe déjà appliqué dans
cette étude de poser les briques avant de forcer un choix.

### Recommandation : le socle multi-utilisateur avant la mécanique fine de décision

L'intuition de Clem au §6 est cohérente avec l'analyse : presque tous les autres chantiers de cette
étude ont besoin de savoir "pour quel utilisateur, sur quel wallet" on calcule quelque chose — le
sizing (§4), la politique de wallet (§5), la cadence DCA (§7), le backtest par profil (§8). Or
aujourd'hui :

- `ScenarioOwner.UserOwner(String userId)` existe dans la mécanique de décision, mais n'est **jamais
  connecté** à un `User.id` réel (JPA) — deux mondes qui ne se recoupent que par convention, jamais
  vérifiés ensemble.
- `WalletSnapshot`/`UserProfile` restent des builders vides (§2.3) — tant qu'ils ne sont pas peuplés
  *par utilisateur*, aucune règle de sizing/wallet ne peut être testée avec des données réelles.
- Aucun test, aujourd'hui, ne fait tourner la mécanique de décision pour deux utilisateurs
  simultanément — l'isolation multi-user de `DecisionEngine`/`ScenarioEngine` est une hypothèse de
  conception, pas un fait vérifié.

Attaquer directement le sizing ou l'architecture DCA (§4/§7) sans ce socle risque de devoir tout
reprendre dès que le multi-user matérialise une contrainte non anticipée — exactement le risque que
Clem signale au §6.

### Briques concrètes, ordonnées, chacune livrable et vérifiable séparément

1. **Unifier l'identité utilisateur** : faire porter à `ScenarioOwner.UserOwner` le vrai `User.id`
   (au lieu d'un identifiant ad hoc), puis écrire un test d'intégration qui fait tourner
   `ScenarioEngine`/`DecisionEngine` pour deux utilisateurs distincts en parallèle et vérifie
   l'isolation (`ScenarioOwner.isVisible`) — non vérifié en pratique aujourd'hui.
2. **Persister un profil de risque par utilisateur** : ajouter le curseur de risque (0-10, §4) de
   façon persistante côté `User` ou une entité settings dédiée, + endpoint minimal pour le
   lire/l'écrire. Seulement la persistance à ce stade, pas encore le calcul de sizing qui le
   consomme — trop tôt (cf. §4, question laissée ouverte).
3. **Construire un vrai `WalletSnapshotService`** : agréger `ProviderApiService#getUserBalance`/
   `getAllBalances` (déjà existant, déjà multi-provider) par utilisateur, pour remplacer
   `WalletSnapshot.builder().build()` dans `TreeAnalysisFacade`. Prérequis technique cité dans
   presque tous les chapitres de cette étude.
4. **Esquisser un attribut de rôle minimal sur `Wallet`** — pas toute la richesse du §5 tout de
   suite, juste assez pour que `Decision`/`ActionStep` puissent référencer un wallet cible (aucun
   `ActionStep` ne sait aujourd'hui "sur quel wallet" agir).
5. **Un test d'intégration bout en bout minimal** : deux utilisateurs fictifs, wallets différents,
   vérifier que le pipeline Opinion→Scenario→Decision reste isolé par utilisateur de bout en bout.

**Les 5 briques ci-dessus sont faites (Palier 2, mergé le 2026-08-11,
`docs/prompts/prompt-implementation-decision-palier2.md`), 485 tests OK (baseline Palier 1 : 465) :**

1. ✅ `ScenarioOwner.of(User)`/`asUserId()` — conversion canonique, testée avec de vrais `User`
   persistés (`ScenarioOwnerIsolationDataJpaTest`).
2. ✅ `UserTradingSettings` (entité + repo + service + `UserTradingSettingsController`) — curseur de
   risque 0-10 persisté par utilisateur, valeur par défaut 5 non forcée en base tant qu'aucune
   écriture n'a eu lieu.
3. ✅ `WalletSnapshotService` réel (package `service.tree.opinion`), réutilise
   `ProviderApiService`/`WalletService.getWalletsByUser` (déjà existant, pas dupliqué).
   `openPositions`/`investedValue` restent volontairement à leur valeur par défaut (nécessitent
   `TransactionService`, hors scope). Nettoyage `BalanceCacheManager` fait au passage (clé de cache
   dérivée de `credential.getId()` plutôt que reconstruite par chaque appelant).
4. ✅ `ActionStep`/`DecisionCandidate` portent désormais un `walletId` nullable (toujours `null` dans
   ce lot : personne ne sait encore le choisir, cf. §5).
5. ✅ `MultiUserIsolationIntegrationTest` — deux `User` persistés, deux `WalletSnapshot` distincts,
   scénario mené jusqu'à `VALIDATED` pour l'un, jamais visible pour l'autre.

Reste hors scope, comme prévu : la formule de sizing (§4), la taxonomie de rôles de wallet (§5), et
les points bloquants du §12 (branchement Spring de `DecisionEngine`/`ScenarioEngine`, persistance de
l'état vivant) — prochain palier à discuter avec Clem.

Cet ordre recoupe en partie le plan déjà posé au §6.2 pts 2-3 du point d'avancement du 2026-08-10,
mais le fait précéder par l'unification de l'identité utilisateur (point 1, pas identifié comme tel
dans ce plan précédent) — c'est le point qui, s'il révèle une incompatibilité, coûterait le plus
cher à découvrir tard.

## 12. Analyse critique — incohérences fonctionnelles

Demandé par Clem le 2026-08-10 : au-delà de "quoi garder/jeter" (§3), est-ce que l'existant est
réellement **en phase** avec le besoin, y a-t-il des points **bloquants** qu'il faut repenser ?
Vérifié en code (pas supposé) le 2026-08-10. Classé par sévérité.

### Bloquant dur — à traiter avant tout le reste

1. **La chaîne Scenario/Decision n'est même pas branchée dans l'application qui tourne.**
   `DecisionEngine` et `DefaultScenarioEngine` sont des POJO **sans aucune annotation Spring**
   (`@Component`/`@Service`) — vérifié dans le code, aucun import `org.springframework` dans
   `DefaultScenarioEngine`. Ils ne sont instanciés **nulle part** en dehors des tests
   (`ScenarioEngineIntegrationTest`, `DefaultScenarioEngineUnitTest`) : pas de bean, pas
   d'endpoint, rien ne les construit au runtime. `TreeAnalysisFacade` (le seul chemin réellement
   exposé via MCP, `get_opinion`) publie bien des `OpinionEvent` sur l'`EventBus`, mais comme aucun
   `ScenarioEngine` n'est enregistré comme listener en production, **personne ne les consomme**.
   C'est plus grave que "le scheduler reste postposé" (point d'avancement §6.2 pt 7) : ce n'est pas
   qu'on ne déclenche pas le cycle périodiquement, c'est que le circuit n'est même pas raccordé.
   Aujourd'hui, en prod, aucune `Decision` n'est jamais créée, quoi qu'il arrive.
2. **Aucune persistance de l'état vivant — un redémarrage efface tout.** `DefaultScenarioEngine`
   stocke ses scénarios dans un simple `ConcurrentHashMap` en mémoire JVM ; `DecisionEngine` fait
   pareil pour `activeDecisions`. Aucun `ScenarioRepository`, aucune rehydratation au démarrage.
   **Précision du 2026-08-11** : `JpaEventStore` est en fait déjà un `@Component` Spring actif
   (`@PostConstruct` s'abonne à `PersistableEvent` sur l'`EventBus`) — le côté écriture fonctionne
   donc réellement en prod dès qu'un `OpinionEvent`/`ScenarioEvent` est publié sur le bus partagé.
   Mais deux trous confirmés en le relisant : (a) rien n'appelle `loadByType`/`loadByTargetId` au
   démarrage pour reconstruire `scenarios`/`activeDecisions` — la lecture-rejeu manque entièrement ;
   (b) `JpaEventStore.toDomain()` ne sait désérialiser que `SCENARIO`/`OPINION`
   (`switch (entity.getType())`) — **`DecisionEvent` (qui implémente pourtant bien
   `PersistableEvent`, `EventType.DECISION`) ferait lever une `IllegalArgumentException` s'il fallait
   le relire**, alors qu'il serait sauvegardé sans erreur à l'écriture (`append` ne filtre pas par
   type). Bug confirmé, pas supposé. Pour un DCA censé "vivre en permanence sur plusieurs
   jours/semaines" (§7.3), l'absence de rejeu reste structurellement incompatible en l'état : un
   redéploiement réinitialiserait silencieusement toutes les intentions d'accumulation en cours.
   **Analyse dédiée (2026-08-11)** : `docs/etudes/etude-branchement-persistance-decision-engine.md`
   — reconfirme ces deux points en code, pose les options de branchement et de persistance avec
   leurs tradeoffs, et les questions fermées pour Clem avant de rédiger le prompt d'implémentation
   Palier 3.
3. ~~Bug concret de cache cross-utilisateur dans `BalanceCacheManager`~~ — **correction du
   2026-08-11** : affirmation initiale fausse, faite en lisant `BalanceCacheManager` isolément sans
   vérifier ses appelants. En réalité `BinanceApiClient`/`KrakenApiClient` passent déjà
   `credential.getApiKey() + ":" + credential.getWebProvider().getApiBaseUrl()` comme clé (le
   paramètre s'appelle `asset` dans `BalanceCacheManager`, ce qui est trompeur, mais sa valeur réelle
   distingue bien les credentials). Pas de fuite entre utilisateurs dans le cas normal. Résidu mineur,
   pas bloquant : le nom du paramètre (`asset`) est trompeur, et la clé composite
   apiKey+baseUrl est plus fragile qu'un identifiant stable (`credential.getId()`) — nettoyage à bas
   risque à envisager en touchant cette zone (cf. Palier 2 étape 3), pas un correctif d'urgence.

### Incohérence de conception — à repenser avant de coder le sizing/DCA

4. **L'arbitrage "unanimité inter-scopes" est binaire, incompatible avec une intensité graduée.**
   `DecisionEngine.isUnanimousAcrossScopes` n'autorise une `Decision` que si **tous** les scénarios
   actifs du symbole proposent exactement la même action — sinon, rien. Le DCA intelligent voulu
   (§7) a besoin d'un score continu (l'échelle x3→vente de Clem), pas d'un tout-ou-rien : si LOCAL
   dit BUY et MACRO dit NEUTRAL, le résultat actuel est *aucune action*, alors qu'un DCA intelligent
   voudrait probablement moduler (acheter un peu moins) plutôt que s'arrêter net. Cette logique
   devra être repensée en profondeur, pas juste reparamétrée.
5. **Un scénario GLOBAL peut aujourd'hui bloquer identiquement tous les utilisateurs**, ce qui
   contredit l'objectif "l'utilisateur a la main sur son agressivité" (§1/§4). `ScenarioOwner.
   SystemOwner` est toujours visible (`isVisible` retourne `true` pour tout scénario système), donc
   un scénario global bearish entre dans l'arbitrage d'unanimité pour tout le monde, **avant** toute
   pondération individuelle par curseur de risque. Un utilisateur à 10/10 devrait pouvoir continuer
   d'acheter malgré un signal global baissier ; le mécanisme actuel ne laisse pas cette place — le
   filtre global s'applique en amont, uniforme, non personnalisable.
6. **Tension non résolue entre §4 (risque au niveau utilisateur) et §5 (risque potentiellement au
   niveau wallet).** Le curseur de risque vit aujourd'hui conceptuellement sur `UserProfile` — un
   scalaire global par utilisateur — alors que §5 envisage un override par wallet (axe 4). Le modèle
   ne peut représenter qu'un seul niveau à la fois. Pas encore un bug de code (rien n'est encore
   implémenté), mais une incohérence de conception entre deux chapitres déjà écrits de cette étude,
   à trancher avant de coder l'un ou l'autre plutôt qu'après.

### Choix implicite à figer explicitement — pas un bug, mais un flou dangereux

7. **La dimension "wallet" est absente des clés de tout le modèle** (`ScenarioKey`,
   `DecisionCandidate`, `ActionStep`) — pas juste un champ à ajouter, un axe entier manquant.
   Point plutôt rassurant en creusant : il semble cohérent de garder la lecture de marché
   (Opinion/Scenario) **non wallet-scopée** (le marché ne change pas selon le wallet visé) et de ne
   faire porter le wallet qu'au niveau sizing/exécution, en aval de la `Decision`. Mais ce choix de
   couche n'est écrit nulle part dans le code — à figer explicitement (probablement dans le futur
   composant "Sizing", cf. schéma du 2026-08-10) pour éviter qu'il soit posé au mauvais endroit plus
   tard par accident.
8. **`DcaCalculatorService` est un silo architectural complet**, avec bypass volontaire et documenté
   de `MarketDatasetEngine`/`Bucket`, sa propre pagination, sa propre résolution de client. Pas un
   problème en soi aujourd'hui, mais si le futur moteur de backtest générique (§8) et le DCA
   intelligent doivent réutiliser cette brique, ce sera un rapprochement conscient à faire, pas une
   simple extension — en l'état c'est un cul-de-sac, pas une fondation partagée.

### Vigilance — à auditer, pas confirmé comme bug

9. La logique de mutation de confiance (`mutateScenario`/`adjustSignal` dans
   `DefaultMarketScenario`) applique un delta de renforcement dans `mutateScenario` **et** une
   logique de renforcement à nouveau évoquée dans `adjustSignal` (commentaire : "renforcement
   implicite via la confiance") — à auditer via des tests dédiés/le futur backtest avant de faire
   confiance aux seuils, un double comptage n'est pas exclu.
10. Le cache LLM d'`AbstractAdvisor` (`Map<String, LlmAdvice>` en mémoire, clé = type + hashCode du
    contexte) n'a **aucune éviction/TTL**. Avec `UserProfile`/`WalletSnapshot` bientôt réellement
    peuplés par utilisateur (donc des `hashCode()` différents par utilisateur), chaque combinaison
    utilisateur×contexte s'accumule indéfiniment — croissance mémoire non bornée en usage
    multi-utilisateur continu. Mineur aujourd'hui, à garder en tête avant la mise en prod multi-user.

### Verdict global

L'architecture évènementielle (Opinion→Scenario→Decision, `EventBus`, `DomainClock` pour le temps
maîtrisé) reste une bonne base conceptuelle — pas à jeter, comme dit en §3. Mais la réponse honnête
à "l'existant est-il en phase avec le besoin" est **non, pas encore, et plus loin qu'il n'y paraît
sur le papier** : rien de cette mécanique ne tourne réellement en production aujourd'hui (#1), l'état
ne survivrait pas à un redéploiement (#2), et l'arbitrage central (#4, #5) est fonctionnellement
incompatible avec le cœur du besoin (DCA gradué, personnalisé par utilisateur) — pas juste mal
calibré, mal conçu pour cet usage (le point #3 initial, cache cross-user, s'est avéré faux à la
vérification des appelants, corrigé le 2026-08-11 — rappel que même les constats de cette étude
doivent être revérifiés au contact du code, pas pris pour acquis une fois écrits). Les points 1 et 2
devraient être traités avant même de considérer commencer le sizing ou l'architecture DCA finale, et
les points 4 à 6 doivent être tranchés en conception avant d'écrire ce code, pas découverts en
cours de route.
