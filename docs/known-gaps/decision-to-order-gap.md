# Le trou décision → ordre réel

Vérifié le : 2026-09-12 (connecteurs d'exchange et exécution revérifiés le 2026-10-10), en croisant le code (`service/tree/decision/**`, `model/dto/tree/{UserProfile,WalletSnapshot}`, `service/tree/api/mcp/TreeAnalysisFacade`) avec `docs/suivi/point-avancement-2026-08-10.md` (archivé, § 6). **C'est le malentendu le plus probable pour un agent qui lit ce projet superficiellement — à lire avant toute affirmation sur la capacité de l'app à trader réellement.**

## Constat

Chaîne cible du DCA Rainbow automatisé et état de chaque brique : [`rainbow-dca-chantiers-ouverts.md`](rainbow-dca-chantiers-ouverts.md).

TradeIO5 calcule des signaux et produit des `Decision`/`ActionStep` en base. Les décisions ne sont pas branchées sur l'exécution. Le chemin d'achat du bench Rainbow (plan d'ordres → exécuteur → port OKX) existe côté back-end mais **reste verrouillé** (`LIVE` non déverrouillé, aucun ordre réel envoyé à ce jour) : [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md). Le moteur `Decision`/`ActionStep` n'y est pas relié.

## Ce qui existe déjà (squelette, pas de bug de conception)

- `RiskProfile` (enum `LOW`/`MEDIUM`/`HIGH`) — `model/enumerate/tree/RiskProfile.java`.
- `UserProfile` (DTO : `riskProfile`, `exitingMarket`, `reinforcementActive`, `maxAllocationPerAsset`, `minCashReserve`).
- `WalletSnapshot` (DTO : `balances`, `openPositions`, `totalValue`, `investedValue`).
- `OpinionContext` transporte déjà ces deux DTO jusqu'à `AbstractAdvisor`.
- Lecture **seule** et fiable des soldes réels : entité `Wallet`, interface `ReadOnlyBalanceReader` (`KrakenApiClient`, `BinanceApiClient`, `OkxBalanceReader` ; soldes disponibles, Earn automatique inclus (Kraken `.F`, Binance `LD*`), symboles standard ; toute panne lève `BalanceUnavailableException`, jamais une map vide ; cache 60 s via `BalanceCacheManager`, échecs non mis en cache). `ProviderApiService#getUserBalance`/`getAllBalances` s'appuient sur ces clients (une panne Kraken se propage désormais comme celle de Binance). Un plan d'ordres dry-run est enregistré ([`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md)) et un exécuteur verrouillé peut l'envoyer ([`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md)). Aucun composant de `service.connector` ne déclare de méthode d'ordre (test `ConnectorNoOrderMethodTest` ; les ordres n'existent que dans `service.execution.exchange`).
- `Decision`/`ActionStep`/`ActionStepExecutedCause` modélisent un cycle de vie `CREATED → EXECUTED/ABORTED` piloté par événements.

## Ce qui manque (vérifié, pas supposé)

1. **`WalletSnapshot`/`UserProfile` ne sont jamais peuplés** : `TreeAnalysisFacade` construit `WalletSnapshot.builder().build()` et `UserProfile.builder().build()` — tous les champs à leur valeur par défaut (`null`/`false`/`0`), pour tout utilisateur, systématiquement. Aucun service ne fait le pont entre `ProviderApiService#getUserBalance` et `WalletSnapshot`.
2. **`AbstractAdvisor#userProfileBlock`/`#walletBlock` sont stubbés à `return ""`** — même si les DTO étaient peuplés, ce contexte ne serait pas encore transmis au prompt de l'advisor LLM.
3. **`User` n'a aucun champ de profil de risque persistant.** (Un curseur de risque continu 0-10, distinct, existe et est persisté via `UserTradingSettingsController`/`Service` — sa consommation réelle en aval du calcul de sizing n'est pas vérifiée dans ce lot.)
4. **La quantité d'un `ActionStep` est un placeholder constant** (`BigDecimal.ONE`, `PLACEHOLDER_QUANTITY` dans `DefaultMarketScenario`) — pas de règle de sizing réelle (proportionnelle au solde disponible, plafonnée par `maxAllocationPerAsset`, respectant `minCashReserve`). La formule de traduction `RiskProfile` → fraction de portefeuille reste une question ouverte, non tranchée avec Clem au 2026-08-10.
5. **Aucun composant n'émet `ACTION_STEP_EXECUTED`/`ACTION_STEP_FAILED`.** Personne ne transforme un `ActionStep` validé en ordre : l'exécuteur ne traite que les plans du bench Rainbow. Le cycle de vie de `Decision` reste un stub d'état.
6. **Le scheduler de génération continue de décisions (`DecisionOrchestratorJob`) reste désactivé par défaut**, volontairement, pour ne pas brancher un déclenchement automatique sur une mécanique de sizing/exécution encore incomplète (voir [`operations/scheduled-jobs.md`](../operations/scheduled-jobs.md)).

## Ordre de priorité proposé (tel qu'arrêté avec Clem au 2026-08-10, statut à reconfirmer)

1. ~~Corriger les 3 TODO critiques bloquant la cohérence interne (DecisionType figé à EXIT, quantité toujours 0, doublons d'intents)~~ — **fait, 2026-08-11**.
2. Brancher `WalletSnapshot` sur les vraies données (`ProviderApiService#getUserBalance` déjà existant) — **pas fait**.
3. Ajouter un profil de risque persistant côté `User` + endpoint — **pas fait** (curseur 0-10 existant à part, cf. ci-dessus).
4. Écrire la règle de sizing réelle — **pas fait**, formule à spécifier.
5. Écrire le composant d'exécution réelle d'ordres — **fait pour le bench Rainbow** (plans, plafonds, kill switch, réconciliation, audit), **verrouillé** et non relié au moteur de décision ; déverrouillage = étape d avec Clem.
6. Calendrier macro comme garde-fou avant exécution — partiellement fait différemment : `MacroRiskWindowModulator` module déjà la confidence en amont (2026-08-14), mais ne bloque/ne suspend aucune exécution puisque l'exécution elle-même (point 5) n'existe pas.
7. Réactivation du scheduler de génération de décision — explicitement postposée, à reprendre une fois 2-5 solides.

**Ce plan date du 2026-08-10/2026-08-14 — à reconfirmer avec Clem avant de le présenter comme la feuille de route actuelle si une session ultérieure a fait évoluer ces priorités.**
