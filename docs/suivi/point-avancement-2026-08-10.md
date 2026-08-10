# Point d'avancement — 2026-08-10

Session de recadrage demandée par Clem suite au point d'avancement global : correction du bug
d'unité ETF, recadrage de 2 indicateurs jugés utilisables mais mal cadrés en doc, revue complète
des TODO/FIXME, et élaboration d'un plan d'action pour le chantier prioritaire suivant — la
mécanique de décision aboutissant à de vrais ordres d'achat/vente.

## 1. Bug d'unité ETF_FLOW — corrigé

`EtfFlowBackfillService` mélangeait deux échelles dans `etf_flow_snapshot.total_net_inflow` :
Farside en millions USD, SoSoValue en USD brut (cf. `docs/calibration/calibration-etf-flow.md`,
bug repéré le 2026-07-17). Corrigé le 2026-08-10 : toute ligne Farside est convertie en USD brut
(`EtfFlowBackfillService#toRawUsd`) avant persistance ; SoSoValue n'a besoin d'aucune conversion.
450 tests OK (`test:tradeio-5` via ssh-gateway, build local réel).

**Action restante (hors code, côté ops)** : les lignes déjà en base avant ce correctif sont encore
à l'ancienne échelle. Rejouer `POST /api/admin/etf-flow/backfill` une fois ce correctif déployé —
l'upsert est idempotent par `(asset, date)`, il réécrit proprement les lignes Farside historiques
avec la bonne échelle sans dupliquer ni perturber le dernier mois SoSoValue.

## 2. RejectionZoneIndicator et EtfFlowConfidenceStrategy — recadrés, pas réécrits

Les deux verdicts de calibration ("pas d'edge robuste") restent statistiquement corrects mais
avaient été mal interprétés comme des refus d'usage. Recadrage documenté à 3 endroits pour éviter
de retomber sur la lecture "inutilisable" dans une session future : javadoc des classes concernées,
docs de calibration (`docs/calibration/calibration-rejection-zone.md` et
`docs/calibration/calibration-etf-flow.md`), et mémoire (corps **et** champ `description` — c'est
ce dernier qui est scanné en premier — de [[tradeio5_rejection_zone_calibration_verdict]] et
[[tradeio5_etf_flow_calibration_verdict]], mis à jour le 2026-08-10) :

- **RejectionZoneIndicator / technique `consolidation`** : le test statistique générique
  (`reaction_rate`) ne valide pas d'edge, mais la détection est qualitativement cohérente avec la
  lecture manuelle de Clem (cluster 81k-90k retrouvé sur 2 timeframes). Décision : **utilisable, à
  introduire en pondération basse** (modulateur secondaire, pas signal fort). Pas encore branché —
  reste à écrire le mécanisme (probable nouveau `CONFIDENCE_MODULATOR`, cf. §4 ci-dessous pour la
  suite).
- **EtfFlowConfidenceStrategy** : la calibration testait un edge directionnel autonome — ce n'est
  pas le rôle d'un `CONFIDENCE_MODULATOR`. Rôle réel : valider si le flux institutionnel confirme
  ou contredit le mouvement de prix récent, avec une échelle de quantité, en utilisant toujours une
  donnée **J-1** (jamais le jour courant — maintenant explicite dans le javadoc). Décision :
  **utilisable telle quelle**, source de données désormais fiable (§1).

## 3. Calendrier macro — pas connecté au MCP

Vérifié dans le code (`TreeAnalysisMcpTools`, `TreeAnalysisFacade`, `DcaMcpTools`) : aucune
référence à `MacroEventCalendarService`. Le calendrier macro (Finnhub + ForexFactory, dédoublonné)
reste accessible uniquement en Java direct/tests, aucun tool MCP (`get_indicator`/
`evaluate_strategy`/`get_opinion`) ne l'expose. Toujours vrai depuis l'état des lieux du
2026-07-09 : "décision explicitement reportée" dans le code. Reste un choix d'architecture non
tranché (fenêtre de risque événementiel avant FOMC/NFP), pas juste du câblage — cf. §4.4 pour le
relier au plan d'action.

## 4. GitGardian — ack résolu

Anciennes clés Binance/Kraken committées (alerte GitGardian) confirmées mortes et retirées du
code source depuis le 2026-07-09 (cf. `docs/suivi/etat-des-lieux-indicateurs-strategies-opinions.md`
§1). Ack GitGardian acquitté par Clem le 2026-08-10.

## 5. Revue des 13 TODO/FIXME du code

| # | Fichier:ligne | Sujet | Sévérité | Décision |
|---|---|---|---|---|
| 1 | `DefaultMarketScenario.java:170` | `new BigDecimal(0.0)` — la quantité d'un `ActionIntent` proposé n'est **jamais** calculée, toujours zéro | **Critique** | Bloquant direct pour §6 (sizing) — voir plan d'action |
| 2 | `DefaultScenarioEngine.java:136` | Aucune dédup avant de proposer un nouvel `ActionIntent` — risque de flooder d'intents déjà proposées | **Critique** | Bloquant direct pour §6 (idempotence avant ordre réel) |
| 3 | `DecisionEngine.java:123,145` | `DecisionType` toujours codé en dur à `EXIT`, quel que soit `ExecutionAction` (BUY/SELL/NO_OP) — alors que l'enum a `ENTER`/`EXIT`/`REBALANCE`/`STOP` | **Critique** | Bloquant direct pour §6 (le type de décision doit refléter l'action réelle) |
| 4 | `BinanceApiClient.java:80` | `try { … } catch (Exception e)` générique sur le prix ticker (chemin wallet/balance, distinct du `MarketDataApiClient` déjà typé par la roadmap fallback) | Modéré | Backlog : aligner sur la hiérarchie `MarketDataProviderException` existante |
| 5 | `KrakenApiClient.java:82` | `RuntimeException` générique sur erreur API Kraken (balance) | Modéré | Backlog : même traitement que #4 |
| 6 | `ProviderApiService.java:30` | `IllegalArgumentException` générique si provider inconnu pour un wallet | Mineur | Backlog : remplacer par `NotFoundException` (déjà présente dans le projet) |
| 7 | `BalanceCacheManager.java:12` | `TTL_MS` balance cache codé en dur (60s) | Mineur | Backlog : externaliser en propriété si besoin d'ajuster sans redéploiement |
| 8 | `Bucket.java:24` | `BASE_TIME_FRAME=H1` codé en dur | Mineur | Backlog, lié à [[tradeio5_db_h1_history_gap]] — pas prioritaire seul |
| 9 | `CoinstatsFearAndGreedClient.java:40` | URI `/insights/fear-and-greed` codée en dur | Cosmétique | Backlog, faible valeur |
| 10 | `DefaultMarketScenario.java:44` | `EXPIRATION_IDLE=2h` codé en dur | Mineur | Backlog : externaliser si besoin de tuning par régime de marché |
| 11 | `DefaultMarketScenario.java:190` | Poids de blend confidence `0.7/0.3` codés en dur | Mineur | Backlog : externaliser, candidat à du tuning empirique |
| 12 | `DefaultMarketScenario.java:203` | Question de conception : faut-il forcer `lastUpdated=clock.now()` à chaque enrichissement ? | Design non tranché | À trancher au moment où le scheduler (§4.4) rendra ce comportement observable en continu — pas urgent en usage ad hoc actuel |
| 13 | `IndicatorParameterService.java:43` | `credential` toujours `null` en sortie de `loadParameters` | Par design | Pas un bug : le credential est résolu par l'appelant (`IndicatorCredentialResolver`), pas stocké dans le `IndicatorParameterSet`. TODO obsolète, à retirer au prochain passage sur ce fichier |

**3 items critiques (#1-3) sont exactement sur le chemin du chantier "décision → ordre"** que Clem
priorise maintenant — pas une coïncidence : ce sont les seuls TODO du code qui documentaient déjà
noir sur blanc ce qui manque pour transformer une `Decision` en ordre réel. Détail au §6.

## 6. Plan d'action — mécanique décision → ordre (scheduler toujours postposé)

Objectif rappelé par Clem : ne pas rebrancher le scheduler de génération de décision pour l'instant
(reste postposé), mais investir dans la mécanique qui, une fois un scheduler branché, permettrait à
une décision de réellement aboutir à un ordre d'achat/vente dimensionné correctement — lecture du
portefeuille réel, politique de risque utilisateur (conservatrice → agressive), etc.

### 6.1 Constat : le squelette existe déjà, mais n'est nulle part connecté

Recherche dans le code (2026-08-10) — bonne nouvelle, il ne s'agit pas de partir de zéro :

- `RiskProfile` (enum `LOW`/`MEDIUM`/`HIGH`, conservateur/équilibré/agressif) existe déjà
  (`model/enumerate/tree/RiskProfile.java`).
- `UserProfile` DTO existe déjà (`riskProfile`, `exitingMarket`, `reinforcementActive`,
  `maxAllocationPerAsset`, `minCashReserve`) — exactement la forme d'une politique de risque
  conservatrice → agressive.
- `WalletSnapshot` DTO existe déjà (`balances`, `openPositions`, `totalValue`, `investedValue`).
- `OpinionContext` transporte déjà `UserProfile`/`WalletSnapshot` jusqu'à `AbstractAdvisor`.

**Mais aucun de ces 3 DTO n'est jamais peuplé** : `TreeAnalysisFacade` construit
`OpinionContext` avec `WalletSnapshot.builder().build()` et `UserProfile.builder().build()` —
tous les champs à leur valeur par défaut (`null`/`false`/`0`), toujours, peu importe l'utilisateur
ou le symbole. Pire, `AbstractAdvisor#userProfileBlock`/`#walletBlock` (censés injecter ce contexte
dans le prompt de l'advisor LLM) sont stubbés à `return ""` — même s'ils étaient peuplés, ils ne
seraient pas encore transmis au modèle. Séparément, l'infra de lecture de balances réelles existe
bel et bien (`Wallet` entity, `BalanceCacheManager`, `ProviderApiService#getUserBalance`) mais rien
ne fait le pont vers `WalletSnapshot`.

Côté `User` (`security/model/User.java`) : aucun champ de profil de risque aujourd'hui — à ajouter
(ou une entité de settings séparée, à trancher selon si d'autres préférences utilisateur sont
prévues à côté du risque).

Côté exécution : `Decision`/`ActionStep`/`ActionStepExecutedCause` modélisent bien un cycle de vie
(`CREATED` → `EXECUTED`/`ABORTED`) piloté par événements, mais **aucun composant du code
n'émet jamais `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED`** — personne ne transforme un
`ActionStep` en appel réel à `BinanceApiClient`/`KrakenApiClient`. C'est un stub d'état, pas une
exécution.

### 6.2 Ordre de priorité proposé

1. **Corriger les 3 TODO critiques du §5** (`DecisionType` correct selon l'action, quantité
   réellement calculée, dédup des intents déjà proposées) — préalable minimal, indépendant du
   reste, sans lequel même un branchement partiel du portefeuille produirait des décisions
   incohérentes (toujours `EXIT`, toujours quantité 0, potentiellement dupliquées).
2. **Brancher `WalletSnapshot` sur les vraies données** : nouveau service (ex.
   `WalletSnapshotService`) qui agrège `ProviderApiService#getUserBalance` (déjà existant, déjà
   multi-provider Binance/Kraken) par utilisateur en un `WalletSnapshot` réel — remplace
   `WalletSnapshot.builder().build()` dans `TreeAnalysisFacade`. Prérequis technique pour toute
   règle de sizing (§6.3).
3. **Ajouter un profil de risque persistant côté `User`** (nouveau champ ou entité settings) +
   endpoint pour le lire/l'éditer, alimentant un `UserProfile` réel (au lieu de
   `UserProfile.builder().build()`). `RiskProfile`/`maxAllocationPerAsset`/`minCashReserve`
   existent déjà comme DTO — il ne manque que la persistance et l'API.
4. **Écrire la règle de sizing** qui consomme `WalletSnapshot`+`UserProfile` pour remplacer le TODO
   #1 (§5) : quantité proportionnelle à `RiskProfile` (LOW/MEDIUM/HIGH → fraction du solde
   disponible), plafonnée par `maxAllocationPerAsset`, respectant `minCashReserve`. C'est une
   formule à spécifier avec Clem avant d'être codée (comment `RiskProfile` se traduit en fraction
   exacte n'est pas dans le code aujourd'hui).
5. **Écrire le composant d'exécution** qui consomme un `ActionStep` `BUY`/`SELL` validé et appelle
   réellement `BinanceApiClient`/`KrakenApiClient` (ou le provider résolu via `asset_provider`,
   cf. roadmap fallback déjà fermée), puis publie `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED` pour
   fermer la boucle d'état de `Decision`. **Point le plus sensible** : nécessite une discussion
   explicite avec Clem sur les garde-fous avant toute activation réelle (mode dry-run/simulation
   d'abord, limites de taille d'ordre, confirmation manuelle ou automatique, gestion des erreurs
   d'exécution partielle).
6. **Calendrier macro (§3)** : une fois le pipeline décision→ordre plus mature, trancher où vit la
   "fenêtre de risque événementiel" (suspendre/réduire l'exposition avant FOMC/NFP) — dépend de #5,
   pas prioritaire avant.
7. **Scheduler de génération de décision** : reste explicitement postposé par Clem. À reprendre une
   fois #1-5 solides, pour éviter de brancher un déclenchement automatique sur une mécanique de
   sizing/exécution encore incomplète.

### 6.3 Question ouverte à trancher avec Clem avant de coder le sizing

`RiskProfile` a 3 paliers (`LOW`/`MEDIUM`/`HIGH`) mais aucune formule n'existe encore pour les
traduire en une fraction de portefeuille ou une taille d'ordre. Suggestions à valider, pas une
décision prise :
- Fraction fixe du solde disponible par palier (ex. LOW=2%, MEDIUM=5%, HIGH=10% par position) ?
- Fraction modulée par la confidence de la `Decision` (`RiskProfile` fixe un plafond, la confidence
  de l'Opinion module en dessous) ?
- `maxAllocationPerAsset`/`minCashReserve` (déjà dans `UserProfile`) comme garde-fous durs
  indépendants du palier, ou comme seule source de vérité (auquel cas `RiskProfile` ne sert qu'à
  documenter l'intention, pas au calcul) ?
