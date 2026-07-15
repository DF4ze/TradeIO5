# État des lieux — Indicateurs / Strategies / Opinions (2026-07-09)

Point de situation demandé par Clem : pour chaque indicateur/stratégie/opinion codé ces derniers
lots, qu'est-ce qu'il reste à faire pour le rendre réellement opérationnel (compte/clé API,
branchement dans le pipeline de décision, calibration/validation empirique). Basé sur une relecture
du code actuel (`src/main/java`), des études (`docs/etude-*.md`), du protocole de calibration
(`docs/calibration-rejection-zone.md`) et du backlog (`docs/BackLog.ods`).

## 0. Constat général

Tout ce qui a été codé compile et passe les tests unitaires (`mvn test` : 210 tests, 0 échec). Mais
"les tests passent" ne veut pas dire "opérationnel" — trois choses manquent encore, à des degrés
différents selon l'indicateur :

1. **Clé API / compte** côté fournisseur externe (Coinalyze, Twelve Data, Finnhub).
2. **Branchement** dans une `Strategy`/`Opinion` réellement invoquée (beaucoup d'indicateurs
   Lot 2/3 sont codés et testables via le tool MCP générique `get_indicator`, mais ne sont
   consommés par **aucune** `Strategy`/`Opinion` — donc invisibles pour une décision automatique).
3. **Calibration/validation sur données réelles** pour les indicateurs "faits maison" sans formule
   standard (zones de rejet, et dans une moindre mesure le scraping Farside).

Autre constat transverse, indépendant indicateur par indicateur : **aucun scheduler** (`@Scheduled`)
n'existe dans le projet. `DecisionEngine`/`ScenarioEngine` réagissent bien à un `OpinionEvent`, mais
rien ne déclenche périodiquement `MarketOpinion.decide()` pour un symbole donné — aujourd'hui, tout
passe par un appel MCP à la demande (`get_indicator`/`evaluate_strategy`/`get_opinion`). Le système
n'est donc pas "autonome" tant qu'aucune tâche planifiée n'appelle ces points d'entrée pour les
symboles suivis.

## 1. Comptes/clés API à créer (bloquant pour plusieurs indicateurs)

`application-dev.properties` a déjà les 3 lignes prévues, toutes vides :

| Propriété | Fournisseur | Compte | Indicateurs bloqués tant qu'elle est vide |
|---|---|---|---|
| `tradeio.coinalyze.apiKey` | [Coinalyze](https://coinalyze.net/account/api-key/) | Gratuit | `OPEN_INTEREST`, `FUNDING_RATE`, `LIQUIDATIONS` |
| `tradeio.twelvedata.apiKey` | [Twelve Data](https://twelvedata.com) | Gratuit | `DXY`, `SP500`, `NASDAQ` |
| `tradeio.finnhub.apiKey` | [Finnhub](https://finnhub.io) | Gratuit | Calendrier macro Finnhub (ForexFactory reste dispo sans clé) |

Sans ces 3 clés, `ApiCredentialInitializer` n'insère tout simplement aucune `ApiCredential` pour ces
providers (le code le gère proprement : log `WARN` + indicateur `invalid()`, pas de crash) — mais
concrètement 6 des indicateurs Lot 2/3 restent inutilisables tant que ce n'est pas fait. C'est
l'action la plus rentable de toute la liste : 5 minutes par compte, débloque 6 indicateurs d'un coup.

DefiLlama, ForexFactory et Farside ne demandent aucune clé (endpoints publics) — déjà opérationnels
côté credential.

### Point de sécurité (backlog item 5.1 "Sortir les clés d'API du code !") — traité le 2026-07-09

`ApiCredentialInitializer.java` contenait en clair, **committées dans le code source** (pas dans
`application-dev.properties` gitignoré comme le reste) : une clé Binance Testnet, une clé **Binance
réelle** (déjà confirmée invalide côté Binance, `-2015 Invalid API-key`), une clé **Kraken réelle**, et
une clé **CoinStats**.

Distinction importante entre ces 4 : Binance/Kraken sont des clés **par utilisateur** (compte
trading réel de Clem) — le patron `application-dev.properties` (config System partagée) ne leur
convient pas, il n'existe aujourd'hui aucun flux de saisie utilisateur pour ce type de secret.
CoinStats en revanche est une clé **System** (comme Coinalyze/Twelve Data/Finnhub, aucun wallet/user
rattaché — cf. `IndicatorCredentialResolver`), donc éligible au même patron `application-dev.properties`.

Décisions/actions :
- Clé Kraken régénérée par Clem (ancienne révoquée) — mise à jour directement en DB (`api_credentials`),
  pas via le code (le seeder n'écrase jamais une ligne déjà existante, il ne fait qu'un insert initial).
- CoinStats migré vers `application-dev.properties` (`tradeio.coinstats.apiKey`), même patron que les
  3 autres System. Nouvelle clé régénérée et renseignée par Clem — FEAR_GREED opérationnel avec la
  clé propre (plus l'ancienne clé committée).
- Binance/Kraken : littéraux retirés de `ApiCredentialInitializer.java` (plus de seed automatique
  d'une vraie clé user en dur dans le repo) — gestion en DB directe en attendant un vrai flux de
  saisie utilisateur (ex: écran de configuration exchange côté web).

## 2. Détail par indicateur — Lot 1 (fondations)

- **FEAR_GREED** — DONE et branché. Credential CoinStats (System), lu par `GlobalMarketOpinion` (scope
  `GLOBAL`), avec la règle "hausse/baisse brutale atténue la confidence" déjà écrite
  (`computeSentimentShiftDampening`). Rien de bloquant. Point mineur : `lastWeek` n'est toujours pas
  exploité (seulement `now`/`yesterday`) — à ajouter seulement si on veut une fenêtre 7j.

  **Piste non exploitée identifiée le 2026-07-09** : au-delà du Fear & Greed, l'API CoinStats expose
  `GET /wallet/balance` (balances de tokens pour une adresse on-chain donnée, ~40 crédits/appel,
  120+ chains) — pertinent pour agréger les wallets `WalletSource.NON_CUSTODIAL` (Ledger/MetaMask)
  aujourd'hui non synchronisés automatiquement (contrairement à `EXCHANGE` via `BinanceApiClient`/
  `KrakenApiClient`). À évaluer : un `CoinstatsWalletBalanceClient` (même patron que
  `CoinstatsFearAndGreedClient`, sur `AbstractExternalIndicator`) branché dans `BalanceCacheManager`/
  `WalletService` pour les wallets non-custodial. Les endpoints `/portfolio/value`/`/portfolio/transaction`
  (portefeuille CoinStats lui-même) sont moins immédiatement exploitables : lecture via `shareToken` à
  générer manuellement, écriture réservée au plan payant "Degen".

- **STABLECOIN_MARKET_CAP** (DefiLlama) — indicateur codé et testé, credential déjà en base (pas de
  clé requise). **Pas branché** : aucune `Strategy`/`Opinion` ne le consomme, seulement accessible en
  ad hoc via `get_indicator`. Reste à faire : décider où l'intégrer (candidat naturel :
  `GlobalMarketOpinion`, comme proxy d'injection de liquidités) et écrire la règle d'interprétation.

- **OPEN_INTEREST / FUNDING_RATE / LIQUIDATIONS** (Coinalyze) — codés et testés. **Bloqués par la
  clé Coinalyze manquante** (§1). Une fois la clé fournie : `OPEN_INTEREST`+`FUNDING_RATE` sont
  consommés par `MovementQualificationStrategy` (Lot 2, voir plus bas) ; `LIQUIDATIONS` n'a
  aujourd'hui **aucun** consommateur (ni Strategy ni Opinion) — disponible en lecture brute
  seulement.

- **ORDER_BOOK** (carnet d'ordres Binance, §6a) — codé et testé, pas de clé requise. **Pas
  branché** : aucun consommateur. Le besoin plus large "zones de liquidité à partir du carnet"
  (§6, distinct de `LIQUIDATIONS` qui vient de Coinalyze) reste non couvert par un algorithme dédié —
  seule la lecture brute (bid/ask, déséquilibre) existe.

## 3. Détail par indicateur/stratégie — Lot 2 (TradFi + première Strategy dérivée)

- **DXY / SP500 / NASDAQ** (Twelve Data) — codés et testés. **Bloqués par la clé Twelve Data
  manquante** (§1). Une fois débloqués : **toujours pas branchés** dans une Opinion — la distinction
  `GLOBAL` (sentiment crypto) vs `MACRO` (économie/taux) évoquée dans les études reste fusionnée en un
  seul scope `GLOBAL` (qui ne lit que FEAR_GREED aujourd'hui). Reste à faire : soit enrichir
  `GlobalMarketOpinion` avec ces 3 signaux, soit créer un scope `MACRO` séparé comme envisagé dans
  l'étude §3.3/§13 — décision de conception à trancher avant de coder.

- **Calendrier macro** (`MacroEventCalendarService`, Finnhub + ForexFactory) — codé et testé
  (dédoublonnage entre les deux sources, y compris le bug de correspondance de titres corrigé
  aujourd'hui). Finnhub bloqué par la clé manquante (ForexFactory fonctionne déjà sans clé).
  **Explicitement pas branché** dans `DecisionEngine`/`Scenario` — le code le dit noir sur blanc
  ("décision explicitement reportée"). Reste à faire : décider où vit une "fenêtre de risque
  événementiel" dans le pipeline actuel (avant un FOMC/NFP, réduire l'exposition ou suspendre les
  décisions ?) — c'est un choix d'architecture, pas juste du câblage.

- **MovementQualificationStrategy** (OI + Funding + OBV, §12) — codée et testée (le bug de message
  "cascade" corrigé le 2026-07-09). Nécessite Coinalyze (§1, déjà fournie et vérifiée fonctionnelle).

  **Branchée le 2026-07-09** : `MarketOpinionParametersFactory.buildLocalOpinionParamWithMovementQualification`
  (même patron que `buildLocalOpinionParamWithTrendConfirmation`) construit maintenant les 3
  `IndicatorKey`/`IndicatorParameters` (OPEN_INTEREST/FUNDING_RATE avec credential Coinalyze injectée
  par l'appelant, OBV) et les 8 seuils de la Strategy — plus besoin de construire les
  `StrategyParameters` à la main via `evaluate_strategy` (MCP). Nouveautés associées :
  `IndicatorParametersFactory.buildObvParams/buildOpenInterestParams/buildFundingRateParams`,
  `StrategyParametersFactory.buildMovementQualificationStrategyParam` +
  `MovementQualificationParam.defaults(...)`. Testé (construction pure, sans réseau) dans
  `MarketOpinionParametersFactoryMovementQualificationTest` (3 tests) ; suite complète repassée au
  vert (213 tests, 0 échec).

  Limite déjà documentée dans le code, **toujours vraie après ce branchement** : elle est agrégée
  comme une Strategy `ENTRY` classique alors qu'elle joue plutôt un rôle de modulateur de confiance —
  le mécanisme de modulation dédié reste à écrire (simplification assumée). Reste à faire pour aller
  plus loin : appeler cette fabrique depuis un vrai point d'entrée (aujourd'hui seulement invocable
  depuis Java/tests ou via `get_opinion` en construisant le JSON à la main côté appelant MCP) — un
  futur scheduler (§0) en serait le consommateur naturel.

## 4. Détail — Lot 3 (scraping fragile / définition à inventer)

- **ETF_FLOW** (Farside, scraping HTML) — codé et testé (fallback `invalid()` systématique en cas de
  structure de page inattendue, vérifié par les tests). Credential Farside déjà en base (pas de clé).
  **Pas branché** dans une Opinion. Point d'attention permanent (pas un "reste à faire" ponctuel) :
  c'est une page HTML publique non versionnée qui peut casser sans préavis — à traiter comme
  best-effort, jamais comme un signal sur lequel une décision automatique s'appuie sans supervision.

- **REJECTION_ZONE** — codé et testé. **Ne pas brancher tel quel** : le protocole de calibration
  (`docs/calibration-rejection-zone.md`) n'a été exécuté que sur des **données synthétiques**
  (pas d'accès réseau exchange depuis l'environnement où le script a tourné). Résultat encourageant
  (83% de taux de réaction vs 64.8% pour des niveaux aléatoires, paramètres stables sur une grille de
  81 combinaisons) mais **pas une validation réelle**. Reste à faire, dans l'ordre :
  1. Exporter un historique H1 réel (BTC/USDT, ETH/USDT — quelques centaines/milliers de bougies)
     depuis TradeIO5 lui-même (accès réseau normal en prod, contrairement à l'environnement de build).
  2. Rejouer `tools/calibration/rejection_zone_calibration.py --csv <export>.csv`.
  3. Si le taux de réaction s'effondre près de 50% ou si la sensibilité aux paramètres explose,
     revoir la formule avant tout branchement dans une `Strategy`/`Opinion`.

## 5. Autres opinions / advisors

- **DefaultMarketOpinion** (scope `LOCAL`, EMA+ADX+RSI via `TrendConfirmationStrategy`) — DONE,
  entièrement opérationnelle (c'est elle que le bug `getRequiredCandles` corrigé aujourd'hui
  affectait).
- **ExternalMarketOpinion** / **OpenAIAdvisor** (scope `EXTERNAL`) — opérationnelle : clé OpenAI déjà
  renseignée dans `application-dev.properties`. Arbitrage `DecisionEngine` par unanimité inter-scopes
  déjà implémenté (une Decision n'est créée que si LOCAL/EXTERNAL/... sont d'accord).
- **DCA** (`calculate_dca`, hors triptyque Indicator/Strategy/Opinion mais dans le même périmètre
  "outils exploitables") — DONE et vérifié en conditions réelles (2026-07-07). Le seul reste-à-faire
  documenté est explicitement hors scope initial : ajustement dynamique du montant selon RSI/Rainbow
  (backlog item 3.2.3/3.2.4, toujours TODO).

## 6. Reporté (hors roadmap active, décision déjà prise avec l'utilisateur)

- **Telegram** (§10) — chantier à part (client MTProto, ingestion asynchrone, extraction LLM),
  postposé explicitement.
- **GLI** (§11, flux de liquidité mondiale) — bloqué par une composante manquante (bilan banque
  centrale chinoise) et l'absence de formule de référence publique ; postposé, à reprendre après DXY
  (dont il dépend).

## 7. Priorisation suggérée

1. Créer les 3 comptes gratuits (Coinalyze, Twelve Data, Finnhub) et renseigner les clés — débloque
   6 indicateurs pour un coût quasi nul.
2. ~~Sortir les clés Binance/Kraken en dur du code~~ — fait le 2026-07-09 (voir §1, "Point de
   sécurité").
3. ~~Brancher `MovementQualificationStrategy` sur une `MarketOpinionParameters` réutilisable~~ — fait
   le 2026-07-09 (voir §3).
4. Rejouer la calibration `REJECTION_ZONE` sur données réelles avant tout branchement.
5. ~~Trancher l'architecture ... et de la séparation `GLOBAL`/`MACRO`~~ — fait le 2026-07-15, voir
   §8 (`docs/etude-nouvelles-opinions-indicateurs-non-branches.md`). Calendrier macro (fenêtre de
   risque événementiel) reste ouvert, hors périmètre de ce lot.
6. Si une décision automatique (pas seulement à la demande via MCP) est souhaitée : ajouter un
   scheduler qui appelle `get_opinion`/`evaluate_strategy` périodiquement pour les symboles suivis.

## 8. Branchement du 2026-07-15 — STABLECOIN_MARKET_CAP, DXY/SP500/NASDAQ, ORDER_BOOK+LIQUIDATIONS

Suite à `docs/etude-nouvelles-opinions-indicateurs-non-branches.md` (design validé par Clem le
2026-07-15, "implémente tout ça") : 3 des 5 indicateurs listés en §2-4 ci-dessus sont maintenant
branchés, testés (313 tests, 0 échec, `mvn test` sur la machine réelle), et invocables via
`get_opinion`/MCP.

- **STABLECOIN_MARKET_CAP** — `GlobalMarketOpinion` (scope `GLOBAL`) combine désormais Fear&Greed
  (poids 60%) et croissance hebdomadaire stablecoin (poids 40%, `computeStablecoinScore`), avec
  repli propre sur 100% Fear&Greed si l'indicateur est invalide. **DONE et branché.**
- **DXY/SP500/NASDAQ** — nouvelle `MacroMarketOpinion`, premier consommateur du scope `MACRO`
  (déclaré dans l'enum depuis le début, resté vide jusqu'ici). Risk appetite score (dollar fort =
  risk-off, actions en hausse = risk-on, NASDAQ pondéré plus haut que SP500). Contrat de
  `DxyIndicator`/`Sp500Indicator`/`NasdaqIndicator` étendu (`values.previous`) pour permettre le
  calcul de delta j/j. Publie un
  `OpinionEvent` **sans symbole** (signal d'ambiance, hors arbitrage `DecisionEngine` pour
  l'instant — décision assumée, réversible, voir étude §2.4). **DONE et branché.**
- **ORDER_BOOK + LIQUIDATIONS** — nouvelle `OrderFlowStrategy` (scope `LOCAL`, `StrategyType.ENTRY`),
  branchée dans `DefaultMarketOpinion` aux côtés de `TrendConfirmationStrategy`/
  `MovementQualificationStrategy`. Combine déséquilibre du carnet et cascade de liquidations
  récente (seuil de significativité en ratio du volume-devise récent, pas en absolu — cf. étude
  §4.2). Même dette assumée que `MovementQualificationStrategy` (modulateur de confiance traité
  comme `ENTRY` classique, mécanisme dédié toujours pas écrit). **DONE et branché.**
- **ETF_FLOW** — toujours volontairement laissé de côté (étude §5) : scraping Farside jugé trop
  fragile pour être plus qu'un modulateur, qui n'a pas encore de mécanisme dédié. Reste accessible
  en ad hoc via `get_indicator` seulement.

Tests ajoutés : `MarketOpinionHelperTest` (3 nouvelles méthodes), `GlobalMarketOpinionTest`
(nouveau), `MacroMarketOpinionTest` (nouveau, logique pure + intégration), `OrderFlowStrategyTest`
(nouveau), `MarketOpinionParametersFactoryOrderFlowTest` (nouveau), plus les tests existants
(`DxyIndicatorTest`, `Sp500IndicatorTest`, `NasdaqIndicatorTest`, `YahooFinanceQuoteClientTest`,
`TwelveDataQuoteClientTest`) étendus pour `values.previous`. Un test préexistant
(`TreeAnalysisFacadeTest`) a dû être adapté : son scénario "aucune MarketOpinion pour ce scope"
utilisait `OpinionScope.MACRO` comme scope volontairement non couvert — ce n'est plus vrai
maintenant que `MacroMarketOpinion` existe, le test vérifie désormais un autre mode d'échec propre
(absence de credential Twelve Data en profil de test → aucun `OpinionEvent` émis).

**Bug trouvé et corrigé lors de la vérification live (2026-07-15)** : la première version de
`DxyIndicator` faisait 2 appels réseau Twelve Data par évaluation (`/price` pour la valeur courante
+ `/quote` pour `previous`), soit 12 crédits — au-dessus du palier gratuit (8 crédits/minute) à elle
seule. Résultat : `DXY` (et donc `MacroMarketOpinion`) échouait quasi systématiquement en conditions
réelles (429 confirmé dans `logs/TradeIO5.log`), alors que `mvn test` restait vert (le mock réseau
des tests ne pouvait pas révéler ce problème). Corrigé en dérivant courant **et** précédent d'un
seul appel `/quote` (6 crédits/évaluation, sous le palier). `DxyIndicatorTest` réécrit en
conséquence, 313 tests toujours au vert après correction.

Vérification live via `get_opinion`/`get_indicator`/`evaluate_strategy` (MCP) **faite** le
2026-07-15 : `DXY` (`values.previous` peuplé), `get_opinion MACRO` (BULLISH, confidence 0.49,
sources DXY/SP500/NASDAQ), `get_opinion GLOBAL` (blend Fear&Greed + stablecoin visible dans
`reason`), `evaluate_strategy OrderFlowStrategy` (NEUTRAL, raisonnement liquidations affiché) —
tous testés avec succès contre l'app réelle.
