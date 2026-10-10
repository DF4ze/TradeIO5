# Roadmap — Vrai wallet (lecture seule) branché au Rainbow DCA ATR

Séquence le chantier ; les décisions et l'architecture vivent dans [`../etudes/etude-wallet-reel-rainbow.md`](../etudes/etude-wallet-reel-rainbow.md) (§3 architecture, §4 lots, §5 décisions du 2026-10-09 — source de vérité). **Un point avec Clem à la fin de chaque étape** avant de rédiger/lancer la suivante. Une étape = compilée + testée + doc `docs/` à jour (sans historique) + cette roadmap mise à jour. **Aucun ordre réel, jamais** : lecture seule de bout en bout.

| # | Étape | Lots étude | Statut |
|---|---|---|---|
| 0 | Étude (lecture seule) | — | **fait (2026-10-09)** |
| 1 | Connecteurs : garde-fous, client OKX lecture seule, fiabilisation Kraken/Binance | L0, L1, L2 | **codé et testé (2026-10-09)** ; lecture réelle OKX/Kraken à valider avec Clem (clé OKX à créer) |
| 2 | Binding token → wallet + vérification de disponibilité | L3 | **codé et testé (2026-10-09)** ; vérification réelle OKX/Kraken à faire avec Clem |
| 3 | Moteur sur vrai cash (port, ledger, snapshot, blocage) | L4 | **codé (2026-10-10)** ; vérification réelle avec Clem à faire |
| 4 | Page web `userPage` | L5 | **codé et testé (2026-10-10)** ; vérification réelle avec Clem à faire |
| — | Hors lot : Fee Test, token d'exchange, découverte du wallet, alerte liquidité, compte funding OKX | — | plus tard |

## Décisions de Clem à respecter (2026-10-09)
- Wallet agrégé multi-exchanges : OKX (BTC/ETH, compte **Trading**), Kraken (PAXG). Binance : prix/historique seulement ; wallet Binance retirable à condition de ne pas bloquer le fetch H1 (les providers market data n'utilisent ni `Wallet` ni `ApiCredential`).
- **Un seul preset live par actif** (les autres restent simulation sur wallet mock) ; le mock du preset live est **conservé en parallèle**.
- **Cash** : pool par wallet, USDC seul (sur OKX et Kraken) ; achat > cash ⇒ tout-ou-rien, `BLOCKED` + raison, jamais de saut silencieux.
- **Position** : `bagPercent` (0-100 %, défaut 100 %, **dynamique**) × position réelle ⇒ position tradable (alimente `ReferenceSizer` et le plafond de vente).
- Périmètre : BTC, ETH, PAXG, USDC ; autres soldes ignorés et non affichés ; vérification de disponibilité des actifs sur le portefeuille dès ce lot.
- Panne/cache périmé ⇒ aucune action live, trace explicite. Rejeu 23:55 : l'action d'origine est conservée.
- Clé API OKX : « Read » uniquement, IP du VPS whitelistée, passphrase, stockée dans `application-*.properties` gitignorés (Clem la crée avant la vérification réelle de l'étape 1).
- Style : `docs/CODING_RULES.md`, isEmpty()/getFirst(), constantes métier mutualisées, pas de variables redondantes, assertTrue/False directs, `DomainClock` (jamais `Instant.now()`).

## Étape 1 — Connecteurs (L0, L1, L2)
- **L0** : supprimer `ProviderApiService#buy/#sell` (0 appelant) ; `@ToString.Exclude` sur `apiKey`/`secretKey`/`passphrase` (`ApiCredential`, et `Wallet#credential`) ; test d'architecture « aucune méthode `buy|sell|placeOrder|newOrder` exposée par `service.connector` ».
- **L1** : `WebProviderCode.OKX` + `WebProvider` (initializer, `https://eea.okx.com`), colonne `passphrase` sur `ApiCredential`, `ReadOnlyBalanceReader` (interface, lève `BalanceUnavailableException`, jamais de map vide en cas d'erreur) et `OkxBalanceReader` (`GET /api/v5/account/balance`, compte trading, solde **disponible** ; signature HMAC-SHA256, en-têtes `OK-ACCESS-*`, passphrase). Tests `MockWebServer` (signature, parsing, erreur API).
- **L2** : Kraken/Binance en `ReadOnlyBalanceReader` : exceptions propagées (plus de `{}` mis en cache), normalisation des assets (XBT→BTC, XETH→ETH, `.F/.S`), solde disponible, `BalanceCacheManager` sur `DomainClock`. Vérifier PAXG/USDC côté Kraken en réel.
- Critères : build vert, aucun secret dans `toString`, lecture réelle OKX/Kraken validée par Clem (BTC/ETH/PAXG/USDC), 0 appel d'ordre possible.
- **Point**.

### Réalisé (2026-10-09) — classes réelles
- `service/connector/balance/` : `ReadOnlyBalanceReader` (`getProviderCode`, `getAvailableBalances` = avec cache, `fetchAvailableBalances` = réseau), `BalanceUnavailableException` (RuntimeException), `BalanceCacheManager(DomainClock)` (TTL 60 s, prend un `Function<ApiCredential, Map>`, échec jamais mis en cache) ; `BalanceProvider` supprimé.
- `OkxBalanceReader` (`service/connector/apiclient`, `@Component`) : `GET /api/v5/account/balance`, `availBal` par devise (soldes nuls absents), timestamp ISO à la milliseconde via `DomainClock`, HTTP 4xx/5xx avec corps OKX => code API dans l'exception, credential incomplète (clé/secret/passphrase/URL) => exception sans appel réseau.
- `KrakenApiClient` passe de `Balance` à `BalanceEx` (disponible = balance + credit - credit_used - hold_trade) ; `KrakenAssetNames` : `XXBT/XBT -> BTC`, `XETH -> ETH`, `Z*` fiat ; `.F` (Auto Earn) agrégé à l'actif nu (la doc Kraken dit de trader via l'actif nu), `.S/.M/.B/.P/.T` ignorés. `BinanceApiClient` (et `BinanceTestnetApiClient`) : `free`, jetons Simple Earn `LD<ACTIF>` agrégés à l'actif nu (`LDO` = Lido DAO conservé), exceptions enveloppées. Earn OKX (compte funding) : hors lot, avec le compte funding.
- `getAllBalances` des clients délègue à `getAvailableBalances` : une panne Kraken se propage désormais comme celle de Binance (appelants legacy `AssetOverviewService`, `WalletSnapshotService`, `TransactionService` inchangés).
- `ApiCredential#passphrase` (nullable, + `V1__init.sql` régénéré), `WebProviderCode.OKX`, `WebProvider` OKX (initializer), `ApiCredentialInitializer` sème OKX depuis `tradeio.okx.apiKey|secretKey|passphrase` (absent => WARN). Pas de `Wallet` OKX créé : c'est le rôle du binding (étape 2).
- Garde-fous : `ProviderApiService#buy/#sell` supprimés, `@ToString.Exclude` (+ `ApiCredentialDTO#toString`), `ConnectorNoOrderMethodTest`.
- Tests : pas de MockWebServer au projet => `com.sun.net.httpserver.HttpServer` (comme `SosoValueEtfFlowClientTest`) pour OKX/Kraken/Binance ; suite complète verte.

### Utile pour l'étape 2
- Un `Wallet` OKX (`webProviderCode=OKX`, credential OKX) reste à créer ; le choix du lecteur se fait par `ReadOnlyBalanceReader#getProviderCode` (liste de beans, `BINANCE_TESTNET` inclus).
- `getAvailableBalances` renvoie des symboles standard sans nuls : « clé absente » = solde 0, pas indisponible (cf. `BindingCheck`).
- Le cache est par instance de lecteur (clé = `credential.id`), 60 s : deux presets d'un même pool lisent le même résultat.

### Mesures réelles
Non faites : clé OKX Read pas encore créée/placée. Restent à lire avec Clem : BTC/ETH/USDC sur OKX, PAXG/USDC sur Kraken (vérifier notamment les actifs `.F` éventuels sur Kraken).

## Étape 2 — Binding (L3)
- `RainbowLiveBinding` (`rainbow_live_binding`) : user, actif, preset live (FK), wallet (FK), `bagPercent`, `priority` ; **unique (user, asset_symbol)** ; bascule = update ; API `/api/rainbow-live/bindings` (CRUD, scopée à l'user, autre user ⇒ 404).
- `BindingCheck` (création + à chaque passe) : credential valide, soldes lisibles, instrument `<actif>/USDC` existant (endpoint public), USDC lisible (solde nul accepté ; clé absente ≠ indisponible). Statut explicite.
- Intégrer les nouvelles tables à Flyway V1 si activé ; désactivation/ignorance du wallet Binance dans les services concernés après vérification des dépendances H1.
- Critères : unicité en base, isolation par user, tests de vérification (OK/KO), doc `architecture/08`, `api/rest-endpoints`.
- **Point**.

## Étape 3 — Moteur sur vrai cash (L4)
- `RainbowPortfolioSource` (Mock | Real) → `PortfolioReading(cash, positions, fetchedAt, status, walletId)` ; `CashLedger` par (user, pool wallet) parcouru dans l'ordre `priority` (réservation par achat accepté ; ventes non créditées le jour même) ; plafonds portés par le service, moteur inchangé.
- Câblage FIXED (`RainbowLiveExecutionService#sizeAction`) et TREND_MIX (`RainbowTrendLiveService#applyCaps` + `ReferenceSizer` sur la position tradable) ; mock du preset live conservé en parallèle (chaîne mock inchangée).
- Snapshot `live_*` dans chaque bloc (statut, wallet, `fetchedAt`, cash, position, cash réservé, raison de blocage) ; action `BLOCKED` ; `UNAVAILABLE` ⇒ aucune action ; 00:05 relit son propre snapshot ; rejeu 23:55 conserve l'action d'origine.
- Test de garde `RainbowLiveNoExchangeDependencyTest` mis à jour (seul `RainbowPortfolioSource` autorisé) ; tests : horloge fixe, lecteur fake (OK/panne/vide), ordre des actifs, rejeu, 0 ordre.
- Critères : achat > cash ⇒ `BLOCKED` ; panne ⇒ aucune action ; ordre des actifs déterministe ; mêmes résultats qu'avant pour les presets non live.
- **Point**.

## Étape 4 — Page web (L5)
- Badge LIVE, wallet réel (exchange, `fetchedAt`, statut) vs fictif, encart « achat bloqué : liquidité insuffisante », sélecteur du preset live par actif et `bagPercent` ; lecture du dernier snapshot en base (pas d'appel exchange à l'affichage). Action réelle plafonnée affichée comme « recommandée, non exécutée ».
- Critères : rendu testé (`MainControllerUserPageTest`), textContent uniquement, docs à jour.
- **Point**.

### Réalisé étape 2 (2026-10-09)
- `RainbowLiveBinding` (+ repo), `RainbowLiveBindingService`, `BindingCheck`/`BindingCheckStatus`/`BindingCheckResult` (`service/dca/atr/binding/`), `RainbowLiveBindingController` (`/api/rainbow-live/bindings`, erreurs via `RainbowLiveControllerAdvice` étendu), `SpotInstrumentChecker` + `OkxInstrumentChecker` / `KrakenInstrumentChecker` (endpoints publics), `CredentialRejectedException` (OKX 50100-50114, Kraken `Invalid key|Invalid signature|Permission denied`) pour distinguer clé rejetée de panne.
- Pas de statut `USDC_MISSING` : « clé absente = solde 0 » rend un USDC illisible inobservable ; l'existence de la paire `<actif>/USDC` (publique) couvre le besoin. Binance : pas de checker ni de wallet.
- Wallet Binance retiré de `WalletInitializer` (fetch H1 vérifié indépendant de `Wallet`/`ApiCredential`) ; wallet « OKX » ajouté. Suppression d'un preset lié refusée (400). `V1__init.sql` régénéré (`rainbow_live_binding`).
- Tests : `BindingCheckTest`, `InstrumentCheckersTest`, `RainbowLiveBindingControllerTest` ; suite complète verte.
- Reste : vérification réelle avec Clem (OKX BTC/ETH, Kraken PAXG ; nom de paire Kraken `PAXGUSDC` à confirmer).

### Réalisé étape 3 (2026-10-10)
- `RainbowPortfolioSource` (`MockPortfolioSource` / `RealPortfolioSource`), `PortfolioReading`, `CashLedger`, `LiveSlot`, `LiveSizing` dans `service/dca/atr/bench/` ; seuil `STALE` `tradeio.rainbow-live.reading-stale-after` (défaut `PT30M`).
- Câblage `RainbowLiveExecutionService` (FIXED) et `RainbowTrendLiveService` (TREND_MIX) ; colonnes `t2355_live_*` / `t0005_live_*` dans `RainbowLivePassBlock` ; action `BLOCKED` ; `V1__init.sql` régénéré ; `RainbowLiveNoExchangeDependencyTest` limité aux implémentations du port.
- Détail dans `architecture/08` (« Preset live sur vrai cash »).
- Reste : vérification réelle avec Clem (pass à blanc avec la clé OKX).

### Réalisé étape 4 (2026-10-10)
- `GET /api/rainbow-live/live-wallet` (`RainbowLiveWalletController`, `RainbowLiveWalletQueryService`, `RainbowLiveWalletDtos`) : lecture base seule (repositories uniquement), snapshot du dernier run (00:05 prioritaire).
- Page (`rainbow-live.js`, `rainbowLive.html`, css) : badge LIVE, bloc « Wallet réel », encarts BLOCKED / UNAVAILABLE / STALE, action « recommandée, non exécutée », sélecteur preset live + `bagPercent` (PUT bindings), statut `BindingCheck` après enregistrement ou « Vérifier » (seul appel exchange, à la demande). Sans binding : bloc masqué.
- Tests : `RainbowLiveWalletControllerTest`, `MainControllerUserPageTest` (conteneur, JS sans innerHTML). Rendu JS non testé en navigateur => à vérifier avec Clem (§4 du prompt).
- Reste : vérification réelle OKX, puis clôture du lot (retrait prompts/roadmap, hygiène docs) après le « Point ».
