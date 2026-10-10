# Étude — Vrai wallet (lecture seule) branché au Rainbow DCA ATR

Statut : analyse du 2026-10-09, **aucun code**. Prompt : [`../prompts/prompt-analyse-wallet-reel-rainbow.md`](../prompts/prompt-analyse-wallet-reel-rainbow.md). Contexte : [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md), [`../known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §3/§5, [`../known-gaps/decision-to-order-gap.md`](../known-gaps/decision-to-order-gap.md).

## 1. Reformulation

Le dimensionnement quotidien (plafond achat ≤ cash, vente ≤ position, aujourd'hui sur `RainbowLiveMockWallet`) doit pouvoir se faire sur les **soldes réels** (OKX pour BTC/ETH, Kraken pour PAXG), pour **un seul preset « live » par actif** ; les autres restent sur wallet mock. Cash = USDC, partagé entre actifs (début du capital commun). Cash insuffisant ⇒ achat bloqué (jamais d'ordre, jamais de saut silencieux). Moteur inchangé, plafond dans le service, lecture seule stricte.

## 2. État des lieux vérifié (code du 2026-10-09)

**Existe**
- `ProviderApiClient` (Binance, BinanceTestnet, Kraken) : `getAllBalances`, `getBalance`, `getMarketPrice`, trades. **Aucune méthode d'ordre dans l'interface.** `BalanceCacheManager` : TTL 60 s en mémoire, clé = `credential.id`, une instance par client.
- `Wallet` (user, `webProviderCode`, `credential`, `enabled`) ; `ApiCredential` (unique user × provider ; `apiKey`, `secretKey`) ; `WebProvider`.
- `WalletInitializer` crée les wallets Binance (« Real Binance account! », **enabled**), Binance Test (disabled), Kraken, pour l'user `"OKlm"` codé en dur. Pas de wallet OKX.

**Manque / pièges**
1. **OKX privé inexistant** : `WebProviderCode` n'a pas `OKX` (OKX n'existe que comme `MarketDataSource` public, `OkxMarketDataApiClient`). `ApiCredential` n'a **pas de passphrase** (obligatoire chez OKX).
2. **`WalletSnapshotService` n'est appelé par aucun code de prod** (tests seulement ; `TreeAnalysisFacade` construit un snapshot vide). Inadapté : agrège **tous** les wallets `enabled` (Binance inclus ⇒ double compte potentiel), appelle `getMarketPrice` par actif (N appels réseau, devise EUR, `double`). Pour Rainbow il faut seulement des quantités.
3. **Échec silencieux Kraken** : `fetchAllBalances` avale l'exception et renvoie `{}` (mis en cache 60 s) ⇒ « position 0 / cash 0 » indiscernable d'une panne. Binance, lui, propage l'exception.
4. **Noms d'assets Kraken** : `normalizeAsset` mappe `XXBT→XBT` ; or `getBalance("BTC")` cherche `BTC` ⇒ 0 pour BTC ; `XETH` non mappé ⇒ 0 pour ETH ; suffixes `.F/.S/.B` (staking/earn) partiellement gérés. PAXG/USDC probablement OK mais **à vérifier en réel**. Binance : seul `free` est lu, préfixe `LD*` (Simple Earn) ignoré (sans objet : Binance n'est pas le wallet cible).
5. **`ProviderApiService#buy/#sell`** : stubs `return true`, **0 appelant en prod** (grep). Dangereux par nature (un futur appelant croirait avoir passé un ordre).
6. **Sécurité des clés** : `apiKey`/`secretKey` en clair en base ; `@Data` ⇒ `toString()` de `ApiCredential` et de `Wallet` (champ `credential`) **inclut les secrets** ⇒ risque de fuite en logs. Les initializers lisent les clés via `Environment` (propriétés gitignorées, cf. [`07-security`](../architecture/07-security.md)) ; rien n'impose aujourd'hui des clés en lecture seule.
7. **Test de garde** `RainbowLiveNoExchangeDependencyTest` interdit `ProviderApiService`, `WalletService`, `Wallet`, `service.connector.*` aux beans du bench ⇒ il faudra le **faire évoluer** (autoriser un unique port de lecture, interdire tout nom `buy|sell|order|trade`).
8. `asset_provider`/`AssetProvider` = **market data uniquement** (appellation par exchange, priorité fallback) ; aucun lien token ↔ wallet/credential.
9. `BalanceCacheManager` utilise `System.currentTimeMillis` (pas `DomainClock`) ⇒ non testable à horloge injectée.

**Côté bench** : `PortfolioView` (cash, quantity) est la bonne couture. Dimensionnement : `RainbowLiveExecutionService#sizeAction` (FIXED) et `RainbowTrendLiveService#applyCaps` (TREND_MIX, la `position` du wallet alimente **aussi** `ReferenceSizer` : vente = fraction × position). `RainbowLiveRunService#applyToWallet` mute le mock au 23:55 ; 00:05 reconstitue cash/position en retirant l'action 23:55.

## 3. Architecture proposée

### 3.1 Lecture seule : un port, pas le service exchange
```
RainbowPortfolioSource (interface, package bench)       <- seul type connu du bench
  ├─ MockPortfolioSource      (wallet mock du preset)
  └─ RealPortfolioSource      -> ReadOnlyBalanceReader (nouveau, package connector)
                                  ├─ OkxBalanceReader (nouveau)
                                  ├─ KrakenBalanceReader (existant, fiabilisé)
                                  └─ BinanceBalanceReader (existant)
```
- `ReadOnlyBalanceReader#fetch(ApiCredential) -> Map<String,BigDecimal>` **lève `BalanceUnavailableException`** (jamais de map vide en cas d'erreur). Aucune interface d'ordre dans ce chemin.
- Retour du port : `PortfolioReading(cash, positionByAsset, fetchedAt, status OK|STALE|UNAVAILABLE, walletId)` — `PortfolioView` en est une vue.
- Garde-fous : supprimer `ProviderApiService#buy/#sell` (0 appelant) ; test d'architecture : aucune classe de `service.connector` n'expose de méthode `buy|sell|placeOrder|newOrder` ; test de garde du bench mis à jour (n'autorise que `RainbowPortfolioSource`). La vraie protection reste **clé API « Read only » + whitelist IP du VPS** côté exchange.

### 3.2 Mapping token ↔ wallet : entité dédiée
`RainbowLiveBinding` (`rainbow_live_binding`) : `user`, `assetSymbol`, `preset` (FK, **le** preset live), `wallet` (FK `Wallet`, donne exchange + credential), `priority` (ordre de passage du cash). **Unique (user, asset_symbol)** ⇒ « un seul live par actif » garanti en base (MariaDB sans index partiel : une table vaut mieux qu'un booléen `live` sur le preset). Bascule = update du binding (le preset précédent redevient simulation, aucun historique perdu). Pas de colonne sur `asset_provider` (market data ≠ wallet). Créé par l'user via API (`/api/rainbow-live/bindings`), pas par initializer (donnée utilisateur, pas un défaut).
Le wallet n'est **jamais** « tous les wallets enabled » : seul le wallet du binding est lu (évite Binance en double).

### 3.3 Cash commun
Pool de cash = **(wallet, USDC)** : USDC OKX et USDC Kraken ne sont pas fongibles sans transfert. BTC et ETH (OKX) partagent un pool ; PAXG (Kraken) a le sien. « Capital commun » global = somme pour l'affichage/l'exposition future, **blocage décidé par pool**.
Ordre de passage : le job travaille aujourd'hui par actif (`presetsByAsset`, ordre = insertion ⇒ non déterministe). Il faut un `CashLedger` par (user, pool) traversé dans l'ordre `binding.priority` (défaut BTC, ETH, PAXG) : chaque achat accepté **réserve** son montant ; le suivant voit le reste. Ventes : plafonnées à la position réelle, produit **non** crédité le même jour (prudent). Rejeu 23:55 : l'action d'origine est conservée (comportement actuel) et réserve son montant dans le ledger.

### 3.4 Lecture seule ⇒ le solde ne bouge pas quand le moteur « agit »
Aucun ordre ⇒ le réel ne reflète pas les actions recommandées (sauf achats manuels de Clem). Le moteur (machine d'états, cooldown, armements) avance comme si l'action avait eu lieu, y compris quand l'achat est bloqué (comportement identique à `NONE` aujourd'hui). Conséquences à assumer et tracer : (a) lecture pure des soldes (pas de wallet « virtuel réel ») ; (b) `cashAfter/positionAfter` n'ont plus de sens pour le live ⇒ remplacés par le snapshot ; (c) 00:05 ne peut plus « retirer l'action 23:55 » du wallet ⇒ il relit (ou réutilise) son propre snapshot.
**Position réelle ≠ position du moteur** (rejeu depuis position nulle) : le bag préexistant de Clem entre dans `fraction × position` du `ReferenceSizer` ⇒ ventes bien plus grosses que sur le mock. Tranché : `bagPercent × position réelle` (§5).

### 3.5 Fraîcheur, panne, traçabilité
- Lecture au début de chaque passe (une par wallet, partagée par les actifs du pool) ; cache 60 s existant OK mais à passer sur `DomainClock`. Respect de `CODING_RULES` (pas de rappel réseau en boucle) : la page lit le **dernier snapshot en base**, pas l'exchange.
- Échec/cache périmé ⇒ `status=UNAVAILABLE` ⇒ **aucun achat ni vente** pour le live (position inconnue), `action=NONE` + raison, WARN. Le preset reste calculé (indicateurs, état) ; les autres presets (mock) ne sont pas touchés.
- Snapshot persisté **dans chaque bloc de passe** du preset live : `live_status`, `live_wallet_id`, `live_fetched_at`, `live_cash_usdc`, `live_position_qty`, `live_cash_reserved` (déjà consommé par d'autres actifs), `live_block_reason` (`NONE`/`INSUFFICIENT_CASH`/`UNAVAILABLE`). Nouveau type d'action `BLOCKED` (ou `NONE` + raison) pour l'affichage.
- Arrondis/pas de quantité : le bench ne les applique pas aujourd'hui ; pour du « recommandé » on garde des `double`, l'arrondi exchange appartient au futur composant d'exécution (hors lot).

### 3.6 Page `userPage`
Badge « LIVE » sur le preset lié, colonne wallet = réel (exchange, `fetchedAt`, statut) vs fictif ; encart « Achat bloqué : liquidité insuffisante (cash X < Y) » ; sélecteur de preset live par actif (binding). Données via API (`/bindings`, champs live dans `RunDto`), JS inchangé dans son principe (textContent). Performance du preset live : calculée sur le mock conservé en parallèle ; l'action réelle plafonnée est affichée à côté (libellé « recommandée, non exécutée »).

### 3.7 Tests
`DomainClock` fixe, `ReadOnlyBalanceReader` fake (OK / panne / vide), `MockWebServer` pour la signature OKX (en-têtes, timestamp ISO, passphrase) et la normalisation Kraken, ledger multi-actifs (ordre, réservation, rejeu), test d'architecture « aucune méthode d'ordre », garde du bench mise à jour, unicité (user, actif) du binding.

## 4. Feuille de route (lots petits)

| Lot | Contenu | Critères d'acceptation |
|---|---|---|
| L0 | Garde-fous : suppression `buy/sell`, `@ToString.Exclude` sur secrets, test d'archi « pas d'ordre » | build vert ; aucun secret dans `toString` ; test échoue si une méthode d'ordre apparaît |
| L1 | **Client OKX lecture seule** : `WebProviderCode.OKX` + `WebProvider` (initializer), `passphrase` sur `ApiCredential`, `OkxBalanceReader` (`GET /api/v5/account/balance` compte trading ; compte de financement `/api/v5/asset/balances` si Q), signature HMAC-SHA256 | tests MockWebServer ; erreur API ⇒ exception ; limites de débit respectées (1 appel/passe) |
| L2 | Fiabiliser `ReadOnlyBalanceReader` Kraken/Binance : exceptions propagées, normalisation (XBT→BTC, XETH→ETH, `.F/.S`), solde disponible, `DomainClock` | BTC/ETH/PAXG/USDC lus correctement sur comptes réels (vérif Clem) |
| L3 | **Mapping token → wallet** : `RainbowLiveBinding`, API CRUD, unicité, bascule | un seul live par actif imposé en base ; preset d'un autre user ⇒ 404 |
| L4 | **Moteur sur vrai cash** : `RainbowPortfolioSource`, `CashLedger`, snapshot par bloc, blocage achat, UNAVAILABLE, câblage FIXED + TREND_MIX | achat > cash ⇒ `BLOCKED` ; panne ⇒ aucune action ; ordre des actifs déterministe ; 0 appel d'ordre |
| L5 | Page web (badge, wallet réel, blocage, choix du live) | rendu testé (`MainControllerUserPageTest`) |
| Plus tard | alerte part de liquidité ≥ X % (point d'accroche : lecture du snapshot par `PortfolioReading`) ; découverte du wallet à la connexion d'un exchange ; USDC seul ⇒ stablecoins multiples | — |

Docs à mettre à jour (sans historique) : `architecture/08` (sources, binding, snapshot), `07-security` (clés read-only, whitelist IP, passphrase, `toString`), `known-gaps/decision-to-order-gap` et `rainbow-dca-chantiers-ouverts` (§3/§5), `api/rest-endpoints`, `operations/flyway` (nouvelles tables dans `V1` si activé), `glossary`.

## 5. Décisions du 2026-10-09 (réponses de Clem)

- **Cash** : pool par wallet (somme seulement en affichage).
- **Position** (révisé le soir même) : le total réel détenu est la base, mais un **bag disponible** `bagPercent` (0-100 %, **défaut 100 % à l'initialisation**) limite la part de l'existant offerte à la stratégie : `position tradable = bagPercent × position réelle` ; c'est elle qui alimente `ReferenceSizer` (vente = fraction × position tradable) et le plafond de vente. Porté par le `RainbowLiveBinding`. **Tranché : pourcentage dynamique** (suit les fluctuations du wallet ; un montant figé plafonnerait les gains à long terme).
- **Achat > cash** : tout-ou-rien, action `BLOCKED` + raison `INSUFFICIENT_CASH`.
- **Wallet mock du preset live** : conservé en parallèle (comparaison fictif / réel, courbes de performance inchangées). Le bloc de passe porte donc l'action fictive (mock) **et** l'action réelle plafonnée + snapshot `live_*`.

- **Quote** : USDC sur OKX et Kraken (confirmé). Périmètre : BTC, ETH, PAXG + USDC uniquement ; la découverte du wallet client reste pour plus tard.
- **Vérification de disponibilité (dans ce lot)** : à la création du binding et à chaque passe, contrôle `BindingCheck` = credential valide, lecture des soldes OK, instrument `<actif>/USDC` existant sur l'exchange (endpoint public), USDC lisible (solde 0 accepté : les soldes nuls sont absents des maps, donc ne pas conclure « indisponible » sur une clé absente). Échec ⇒ statut explicite, pas d'action live.
- **Binance** : wallet retirable à condition de ne pas bloquer le fetch H1 historique. Le fetch H1 passe par `MarketDataApiClient` (klines publics, sans `Wallet` ni `ApiCredential`) et `BinanceDailyCandleFetcher` ⇒ a priori indépendant ; **à vérifier** (grep des dépendances à `Wallet`/`ApiCredential` Binance dans `service/market`) avant suppression de `WalletInitializer` Binance.
- **Rejeu 23:55** : l'action d'origine est conservée (comportement actuel).
- **Fee Test** (hors lot, à documenter) : quand l'user choisit une paire sur une connexion/exchange, un « Fee Test » estime le coût (frais + spread) ; au-dessus d'un seuil ⇒ warning user. Extensible aux « Bench online », avec à terme un système qui juge les benchs utilisateur, surtout à PnL négatif. Voir [`etude-trading-stablecoin-frais-btc-eth-paxg.md`](etude-trading-stablecoin-frais-btc-eth-paxg.md).

- **Soldes hors BTC/ETH/PAXG/USDC** : ignorés du sizing et **non affichés** pour l'instant.
- **OKX, comptes** : le compte *trading* (`/api/v5/account/balance`) porte les soldes qui servent à trader en spot ; le compte *funding* (`/api/v5/asset/balances`) porte dépôts/retraits et autres produits, et un transfert interne est nécessaire pour trader. Par défaut : lecture du compte trading seul ; le funding en option si tu y gardes des fonds. Sous-compte = identifiant séparé avec sa propre clé API (inutile si tu n'en as pas).

- **OKX, comptes** : BTC/ETH/USDC dans *Trading* (confirmé) ⇒ lecture du compte trading seul ; compte *funding* possible plus tard (même interface de lecteur, second endpoint).
- **Clé API OKX** (à créer avant L1) : Profil → API → « Create V5 API key » ; type *API trading* ; nom libre ; **passphrase** à choisir (stockée avec la clé : sera demandée à chaque requête) ; permission **Read uniquement** (jamais Trade ni Withdraw) ; **IP allowlist = IP publique du VPS** ; validation 2FA ; clé et secret affichés une seule fois. Stockage : propriétés gitignorées (`application-*.properties`) lues via `Environment`, comme les autres clés.
- **Frais OKX** : spot de base 0,08 % maker / 0,10 % taker (palier < 500 OKB, source Bitsgap, à confirmer sur ton palier réel dans OKX). Devise du prélèvement non confirmée par les sources consultées (en général l'actif reçu) : sans effet en lecture seule, à vérifier sur un relevé d'ordre avant le Fee Test.

- **Seuils du Fee Test** (proposition de Clem, en %, par ordre d'un seul côté, frais + spread estimé) : < 0,2 % vert ; 0,2-0,8 % warning ; > 0,8 % zone rouge. Seuils configurables, valeurs par défaut ci-dessus.

- **Fee Test, sources live** : (a) *frais* = taux réel du compte, lisible en lecture seule (OKX `GET /api/v5/account/trade-fee`, Kraken `TradeVolume` avec fee-info) plutôt que codé en dur : le taux dépend du palier (volume 30 j, OKB…), maker/taker et promos ; (b) *slippage / spread* = estimation par parcours du carnet public (OKX `/market/books`, Kraken `Depth`) : prix moyen d'exécution simulé d'un ordre au montant réel vs prix milieu. Le slippage **réellement subi** n'est mesurable qu'avec des ordres exécutés (hors périmètre lecture seule). Existant : `BinanceOrderBookApiClient` (à généraliser). Endpoints à confirmer dans la doc de chaque exchange au moment du lot.
- Surveillance : le Fee Test est rejoué à chaque passe du live (carnet = 1 appel public par actif) et stocké dans le snapshot, pour voir dériver le coût.

- **Token de l'exchange** (hors lot) : remise de frais possible en détenant le token natif ; à souligner à l'utilisateur, avec achat automatique optionnel d'une petite quantité plus tard. Cf. [`../known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md) §8.

## 6. Questions encore ouvertes

Aucune question bloquante pour démarrer L0 → L3.
