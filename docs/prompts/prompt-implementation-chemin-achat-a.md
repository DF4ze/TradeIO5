# Prompt A — Implémentation : catalogue d'instruments, frais réels, graphe de chemin, Fee Test (SANS ORDRE)

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). Étape (a) de `docs/prompts/prompt-roadmap-chemin-achat-execution.md`. **Lecture seule : aucun ordre, aucune méthode d'ordre, aucun appel authentifié autre que `GET /api/v5/account/trade-fee` avec la clé Read.** Point avec Clem à la fin.

## 0. Démarrage
1. Lis `docs/README.md`, `docs/CODING_RULES.md`, `docs/etudes/etude-chemin-achat-execution.md` (§2 état des lieux, §3.1-3.2, §5 décisions), la roadmap, `architecture/08-rainbow-bench-grandeur-nature.md` (§Bindings, §Groupes d'actifs), `architecture/07-security.md`, `operations/flyway.md` (règle des défauts dans les Initializers).
2. Lis le code : `service/connector/apiclient/{OkxBalanceReader,OkxInstrumentChecker,KrakenInstrumentChecker,OkxApiClient}`, `service/connector/instrument/SpotInstrumentChecker`, `service/dca/atr/binding/BindingCheck*`, `service/currency/AssetGroupService`, `model/dto/market/OrderBookSnapshot`, `BinanceOrderBookApiClient` (modèle de lecture de carnet), `ApiCredential`/`Wallet`, tests `InstrumentCheckersTest`, `ConnectorNoOrderMethodTest`, `RainbowLiveNoExchangeDependencyTest`.
3. Réponds en français, court et technique. Point ambigu ⇒ pose la question à Clem avant de coder.

## 1. Décisions à respecter (étude §5, roadmap)
- Fiat **interdit** dans tout chemin ; nœuds exclus configurables ; sans chemin ⇒ `NO_PATH` + warning (jamais d'ordre).
- Chemin = sources (membres du groupe USD détenus) → actif ; coût minimal ; **profondeur ≤ 2 sauts**.
- Coût d'une jambe = frais effectif (bps, du compte, jamais supposé) + demi-spread + slippage au montant ; chemin multi-jambes = **somme brute**.
- Fee Test : < 0,2 % GREEN ; 0,2-0,8 % WARNING ; > 0,8 % RED (seuils configurables ; la borne 0,2 % est **incluse dans WARNING**, 0,8 % dans WARNING — au-delà RED). Frais en %, jamais exposés en bps à l'utilisateur.
- Cache DB des instruments (politique cache DB de Clem : tout fetch exchange stocké pour limiter les requêtes) ; pas de boucle réseau vers le provider (règle CODING_RULES).
- Credentials toujours via le `User` en base. Aucun secret loggué ni dans une exception.

## 2. À livrer
1. **`InstrumentCatalog`** (package `service/market/instrument` ou équivalent, hors `service.connector` si pas lecture exchange pure) :
   - Entité `ExchangeInstrument` (`exchange_instrument`) : provider (`WebProviderCode`), `instId`, `base`, `quote`, `state`, `minSz`, `lotSz`, `tickSz`, `fetchedAt` ; unique (provider, instId). Schéma par `ddl-auto=update` + `V1__init.sql` Flyway préparé à mettre à jour (cf. `operations/flyway.md`).
   - Client public OKX : **un seul** `GET /api/v5/public/instruments?instType=SPOT` (toutes paires), parsing défensif (réutiliser le style de `OkxInstrumentChecker`) ; refresh ≤ 1×/jour (`DomainClock`, durée configurable) + refresh explicite ; panne ⇒ conserver le catalogue en base, WARN, jamais de catalogue vidé.
   - Kraken : même contrat via `AssetPairs` si trivial (`ordermin`, `lot_decimals`, `tick_size`, noms normalisés par `KrakenAssetNames`) ; sinon laisser Kraken sur son checker actuel et le dire dans le point.
2. **`BindingCheck`** lit le catalogue (plus d'appel `instruments` unitaire par passe). Adapter `OkxInstrumentChecker`/`SpotInstrumentChecker` (ou les remplacer par le catalogue) en conservant les statuts `OK`, `TRADABLE_VIA_BRIDGE`, `NOT_TRADABLE_WITHOUT_FIAT`, `INSTRUMENT_UNAVAILABLE` (catalogue absent et provider injoignable).
3. **`TradingFeeProvider`** OKX : `GET /api/v5/account/trade-fee?instType=SPOT&instId=…`, clé Read du wallet, signature HMAC de `OkxBalanceReader` **mutualisée** (extraire un helper de signature partagé, pas de copie). Valeurs maker/taker signées (négatif = frais payé, positif = rebate) normalisées en `FeeRate(makerPct, takerPct)` ; cache court en mémoire sur `DomainClock` (24 h, échecs non cachés) ; mêmes exceptions typées que le lecteur de soldes (`CredentialRejectedException` / indisponible). Respecter la limite de débit OKX (throttle simple).
4. **Carnet public** : `OrderBookClient` OKX (`GET /api/v5/market/books?instId=…&sz=…`) → `OrderBookSnapshot` existant ; repli `ticker` (bid/ask) marqué `estimation=TICKER`. Pas de cache long (1 appel par devis).
5. **`PathFinder`** (service pur, testable sans réseau) :
   - Entrées : instruments du provider, nœuds interdits, devises sources avec soldes, cible, profondeur max (2, constante mutualisée).
   - Arêtes orientées (acheter : quote→base à l'ask ; vendre : base→quote au bid) ; Dijkstra sur coût bps ; résultat `PathQuote(legs[], totalCostPct, feeTestLevel, status OK|NO_PATH|BELOW_MIN, estimation)` ; chaque jambe : `instId`, side, frais, spread, slippage, `sz` arrondie au `lotSz` (⌊⌋ achat, ⌈⌉ pour couvrir un manque), contrôle `minSz`.
   - Nœuds interdits : constante mutualisée (devises fiat) lisible en config ; **ne pas** ajouter de champ sur `Asset` dans cette étape.
   - Option `preferredQuote` pour pouvoir préférer `X-USDT` (consommer le stock USDT) : paramètre du chemin, pas de comportement par défaut changé.
6. **`FeeTest`** (pur) : niveau GREEN/WARNING/RED depuis un pourcentage, seuils `tradeio.execution.fee-test.warn-pct` (0,2) / `red-pct` (0,8), défauts créés en constantes (règle des défauts dans les Initializers : pas de config DB nouvelle).
7. **Lecture exposée** : `GET /api/rainbow-live/bindings/{id}/path-quote?amount=` (propriétaire seulement, `@PreAuthorize("isAuthenticated()")`, 404 si binding d'un autre user) renvoyant le `PathQuote` + niveau + warning textuel (`NO_PATH` ⇒ message « non tradable sans monnaie fiat »). Un seul calcul à la demande, pas de job, pas d'écriture hors cache catalogue.

## 3. Garde-fous (tests obligatoires)
- `ConnectorNoOrderMethodTest` et `RainbowLiveNoExchangeDependencyTest` **toujours verts et non assouplis** ; aucune méthode `buy|sell|placeOrder|newOrder|createOrder|cancelOrder` introduite (nommer `acquire/convert` si besoin pour des jambes de plan — ou rester en DTO).
- `PathFinder` : PAXG/OKX avec `USDT` seul ⇒ 1 saut ; avec `USDC` seul ⇒ `USDC-USDT` + `PAXG-USDT` (2 sauts) ; PAXG/Kraken (USD/EUR/XBT/ETH seulement, fiat interdit) ⇒ `NO_PATH` ; BTC/OKX avec `BTC-USDC` ⇒ 1 saut ; profondeur 3 jamais explorée ; `sz` < `minSz` ⇒ `BELOW_MIN` ; arrondis lot/tick vérifiés ; somme brute des jambes.
- `FeeTest` : bornes (0,19 / 0,2 / 0,8 / 0,81), seuils configurables.
- Parsing OKX (`instruments`, `trade-fee` signé, `books`) sur fixtures JSON via `MockWebServer`, erreur API, réponse vide, catalogue conservé en cas de panne, aucun secret dans les messages.
- `BindingCheck` : mêmes statuts qu'avant avec catalogue en base ; plus d'appel réseau par `check()` quand le catalogue est frais.
- Sécurité contrôleur : preset/binding d'un autre user ⇒ 404 (test avec sécurité par méthode active, comme `RainbowLiveControllerSecurityTest`).

## 4. Documentation (sans historique)
Mettre à jour : `architecture/08` (Bindings : catalogue au lieu des checkers unitaires), `05-external-providers`/`07-security` si pertinent, `api/rest-endpoints.md` (nouvel endpoint), `glossary.md` (PathQuote, Fee Test, catalogue), `known-gaps/rainbow-dca-chantiers-ouverts.md` §5c/§8 (état), `operations/flyway.md` (nouvelle table), la roadmap (statut étape a). Supprimer ce qui n'est plus vrai.

## 5. Règles
- `docs/CODING_RULES.md` + style de Clem : isEmpty()/getFirst(), constantes métier mutualisées (fiat, profondeur, seuils, durées), pas de variables redondantes, `assertTrue/False` directs, `DomainClock` (jamais `Instant.now()`), Lombok, logs INFO sur changements d'état / DEBUG sur valeurs.
- Build/tests : `mvn` via le ssh-gateway local (JDK 21 explicite, sortie redirigée vers fichier) si la session le permet ; sinon le dire à Clem.
- Aucune modification de `Wallet`/`ApiCredential` dans cette étape (le `scope` vient à l'étape c).
- Livrable final : build vert, tests listés, docs à jour, puis **Point** : résultats d'un devis réel PAXG/OKX (frais réels du compte, coût en %, niveau Fee Test) à valider avec Clem.
