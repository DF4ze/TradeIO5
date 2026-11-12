---
statut: courant
vérifié_le: 2026-10-27
modifié_le: 2026-11-10
empreinte: c539bc125b
---
# Providers de données externes

Vérifié le : 2026-10-27 (symboles Coinalyze/carnet/ETF, Finnhub 403 ; 2026-09-12 : croisement `docs/archive/suivi/etat-des-lieux-indicateurs-strategies-opinions.md` archivé + `configuration/EtfFlowCachingConfig` + `service/scheduler/EtfFlowHistorizationJob` + `service/tree/macro/MacroEventCalendarService`).

| Donnée | Provider actuel | Provider historique/écarté | Notes |
|---|---|---|---|
| Open Interest / Funding / Liquidations | Coinalyze | — | `context.symbol()` est le symbole nu de l'actif (`BTC`) : `CoinalyzeSymbolResolver`, `OrderBookIndicator` (carnet Binance) et `EtfFlowConfidenceStrategy` le convertissent en paire Binance via `ProviderSymbolMapper.binancePair` (`BTC` -> `BTCUSDT`, paire déjà suffixée conservée). Un actif sans marché future Binance (ex. PAXG) donne un indicateur invalide, modulateur ignoré |
| DXY (indice dollar) | Twelve Data | — | Seul indicateur encore sur Twelve Data (voir ligne suivante) |
| SP500 / NASDAQ | Yahoo Finance | Twelve Data | Migration effectuée — ne pas documenter SP500/NASDAQ comme venant de Twelve Data |
| Fear & Greed | CoinStats | — | Endpoint codé en dur (`/insights/fear-and-greed`) dans `CoinstatsFearAndGreedClient` — TODO connu, non bloquant |
| Plafond stablecoin | DefiLlama | — | — |
| ETF flow (BTC/ETH) | SoSoValue (`SosoValueEtfFlowClient`, via `CachingEtfFlowClient`, bean `@Primary`) | Farside (scraping HTML `jsoup`, `FarsideEtfFlowClient`) | Farside n'est plus la source du flux courant mais reste injecté **uniquement** dans `EtfFlowBackfillService` pour le backfill historique — 3ᵉ candidat `EtfFlowProvider` dans le contexte Spring, sans casser le `@Primary` posé sur SoSoValue |
| Calendrier macro | Finnhub + ForexFactory | — | Dédoublonné entre les deux sources (`MacroEventCalendarService`). Finnhub `/calendar/economic` répond 403 « no access » avec la clé actuelle (endpoint non inclus dans le plan) : seul ForexFactory alimente alors le calendrier |

## Historisation ETF flow

`EtfFlowHistorizationJob` (cron par défaut `0 0 7 * * *`, **actif** — contrairement à la plupart des autres jobs du projet) rafraîchit BTC + ETH une fois par jour via `CachingEtfFlowClient#refresh` (bypass volontaire du gate quotidien normalement appliqué à `fetch`), pour garantir une ligne `etf_flow_snapshot` par asset et par jour même si personne n'évalue `ETF_FLOW`/`CONFIDENCE_MODULATOR` ce jour-là. Isolation par asset : un échec sur BTC n'empêche pas la tentative ETH. Créneau (07h00 heure JVM) documenté dans le code comme **non vérifié empiriquement** (contrairement au calage veille média, basé sur des horaires observés).

**Bug corrigé (2026-08-10)** : mélange d'échelles entre Farside (millions USD) et SoSoValue (USD brut) dans `etf_flow_snapshot.total_net_inflow`. Corrigé par conversion systématique en USD brut à l'écriture (`EtfFlowBackfillService#toRawUsd`). Les lignes déjà en base avant le correctif restent à l'ancienne échelle tant que `POST /api/admin/etf-flow/backfill` n'est pas rejoué (upsert idempotent par `(asset, date)`).

## Calendrier macro : lu à la demande, pas branché dans le pipeline de décision

`MacroEventCalendarService` + `MacroCalendarMcpTools` (tools MCP `get_macro_calendar`, `check_macro_risk_window`) permettent de lire le calendrier macro et de savoir si une fenêtre à risque (FOMC/NFP/CPI...) est active. **Ce calendrier est également consommé automatiquement** via `MacroRiskWindowModulator`, branché dans `GlobalMarketOpinion`/`MacroMarketOpinion` — modulation de confidence (pas de suspension/exécution) en amont d'une `Decision`. Vérifier `MacroRiskWindowModulator` directement si un comportement précis de seuil est nécessaire (facteur de réduction observé dans le plan de test manuel : ~0.5, non calibré).

## Credentials manquantes = dégradation gracieuse, pas erreur

Convention répétée dans plusieurs jobs (`EtfFlowHistorizationJob`, `MediaWatchIngestionJob`) : si `IndicatorCredentialResolver`/`MediaCredentialResolver` ne résout aucune credential pour le provider requis, le job logue un warning et s'arrête proprement (pas d'exception, pas de crash). À garder en tête en diagnostic : "aucune donnée produite" peut simplement signifier "aucune clé API configurée pour ce provider", pas un bug.

## One Trading (exchange, clé API seule)

Vérifié le : 2026-11-04 (`service/connector/apiclient/OneTrading*`, `service/execution/exchange/OneTrading*`, `TradeCredentialService`, `ReadCredentialService`). **Code écrit, jamais appelé en réel** (aucun appel authentifié ni ordre à ce jour) ; les tests unitaires utilisent un serveur HTTP local.

- **Provider déclaré par l'admin** (onglet Providers), pas semé : URL de base attendue `https://api.onetrading.com/fast/v1`. Provider absent, sans URL ou désactivé ⇒ aucun appel réseau (`OneTradingRequests.usable`) : instruments / carnet ⇒ `InstrumentLookupException`, lecteurs ⇒ `BalanceUnavailableException`, port d'ordres ⇒ `TradeCredentialRejectedException`.
- **Authentification** : `Authorization: Bearer <clé API>` ; ni secret, ni signature, ni passphrase (`TradeCredentialService.KEY_ONLY_PROVIDERS` : `secretKey` et `passphrase` ignorés, stockés nuls, en lecture comme en Trade). `ExchangeKeysService.SupportedExchange.needsSecret` vaut `false` pour One Trading. Portées de clé One Trading : Read, Trade, Withdraw (jamais Withdraw).
- Transport commun `OneTradingRequests` (réponse brute `Reply(status, body)`, interprétée par l'appelant ; 401/403 ⇒ clé refusée en lecture).
- Lecture : `OneTradingInstrumentListClient` (`GET /instruments?type=SPOT`, `state=ACTIVE` ⇒ négociable, `lotSz=10^-amount_precision`, `tickSz=10^-market_precision`, `costMin=min_size` en cotation, `minSz=lotSz` faute de taille minimale en base dans la doc, `nativePair=BTC_USDC`) ; `OneTradingOrderBookClient` (`GET /order-book/{code}?level=1&depth=n`, pas de repli ticker) ; `OneTradingTradingFeeProvider` (`GET /account/fees`, palier `SPOT`, frais par groupe de produits et non par instrument, **lus comme des % du montant : unité à confirmer sur une réponse réelle**) ; `OneTradingBalanceReader` (`GET /account/balances`, `available`) ; `OneTradingFillReader` (`GET /account/trades`, fenêtres de 30 jours depuis 2024-01-01, `cursor`, pas de `clOrdId`, futures ignorés).
- Instruments : code interne `BASE-QUOTE`, code One Trading `BASE_QUOTE`.
- Non fait : bougies (pas de `MarketDataApiClient` / `MarketDataSource.ONE_TRADING`), repli ticker du carnet.

## Bitvavo (exchange, clé API + secret, API v2)

Vérifié le : 2026-11-04 (`BitvavoRequests`, `Bitvavo*` sous `service/connector/apiclient/`, `BitvavoSpotOrderClient` sous `service/execution/exchange/`). **Jamais appelé en réel** (ni lecture authentifiée, ni ordre) : tests sur serveur HTTP local seulement.

- **Déclaration** : `WebProvider` `BITVAVO` déclaré dans l'onglet Providers de `/admin` (URL de base `https://api.bitvavo.com/v2`). Provider absent, sans URL ou désactivé ⇒ aucun appel réseau (`BitvavoRequests.usable`).
- **Authentification** : `Bitvavo-Access-Key`, `Bitvavo-Access-Timestamp` (ms), `Bitvavo-Access-Window` (10 000 ms) et `Bitvavo-Access-Signature` = HMAC-SHA256 **hexadécimal** (clé = secret) de `timestamp + MÉTHODE + chemin complet avec /v2 et la query + corps`, sans séparateur. Clé + secret, **pas de passphrase** (secret chiffré en portée Trade comme Kraken/OKX). Droits de clé : « Voir » pour la lecture, « Trader » pour les ordres, jamais « Retirer ».
- Lecture : `GET /markets` (tick = `tickSize`, lot = 10^-`quantityDecimals`, minima base / cotation, négociable si `status = trading`) ; `GET /{market}/book?depth=` (paires `[prix, quantité]`) ; `GET /account/fees` (`maker`/`taker` lus comme **fractions décimales** puis convertis en %, **à confirmer sur une réponse réelle**) ; `GET /balance` (`available`) ; `GET /trades?market=` (historique : marchés = devises détenues × EUR/USDC/USDT du catalogue ; **les trades d'une devise entièrement vendue et absente du solde ne sont pas relus**).
- Marchés : code interne `BASE-QUOTE` = identifiant Bitvavo.
- Non fait : bougies (pas de `MarketDataApiClient` / `MarketDataSource.BITVAVO`), repli ticker du carnet.

## Revolut X (exchange, clé API + clé privée Ed25519, API 1.0)

Vérifié le : 2026-11-04 (`RevolutXRequests`, `RevolutX*` sous `service/connector/apiclient/`, `RevolutXSpotOrderClient` sous `service/execution/exchange/`). **Jamais appelé en réel** (ni lecture authentifiée, ni ordre) : tests sur serveur HTTP local seulement.

- **Déclaration** : `WebProvider` `REVOLUT_X` déclaré dans l'onglet Providers de `/admin` (URL de base `https://revx.revolut.com/api/1.0`). Provider absent, sans URL ou désactivé ⇒ aucun appel réseau (`RevolutXRequests.usable`). La racine (`/api`) est déduite de l'URL en retirant le suffixe `/N.M` ; chaque chemin porte sa version (`/1.0/...`, `/2.0/...`).
- **Authentification** : `X-Revx-API-Key`, `X-Revx-Timestamp` (ms) et `X-Revx-Signature` = Ed25519 en Base64 de `timestamp + MÉTHODE + chemin complet (/api/1.0/...) + query + corps`, sans séparateur. `apiKey` = clé API Revolut X ; `secretKey` = clé **privée Ed25519 au format PEM PKCS#8** (chiffrée en portée Trade, `SecretCipher`) ; pas de passphrase. Clé privée illisible ⇒ `CredentialRejectedException`.
- **Génération des clés** : le moteur générique (`ProviderKeyPairService`, `RevolutXKeyPairSupport`) crée par utilisateur une paire Ed25519 par portée (READ, TRADE) ; la clé publique est enregistrée par l'utilisateur chez Revolut X (droits lecture seule pour READ, trading pour TRADE, jamais de retrait, IP du serveur), qui renvoie la clé API. Vérification avant stockage : READ ⇒ lecture des soldes ; TRADE ⇒ `GET /1.0/orders/active` signé. Détail : [`07-security.md`](07-security.md#génération-de-paires-de-clés-par-utilisateur).
- **Candles** : provider de données de marché (`RevolutXMarketDataApiClient`, endpoint public, USDC) : cf. [`04-market-data.md`](04-market-data.md). Un wallet `REVOLUT_X` rattaché à un lien fait de Revolut X la source des candles du lien (`LiveCandleSource`), donc un choix de la page de paramétrage Rainbow.
- Paires (`GET /1.0/public/configuration/pairs?region=EEA`, public) : `base_step` ⇒ lot, `quote_step` ⇒ tick (hypothèse), `min_order_size` ⇒ minimum base, `min_order_size_quote` ⇒ minimum notionnel, `status=active` ⇒ vivant. Code interne `BASE-QUOTE` = symbole Revolut X.
- Carnet (`GET /2.0/public/order-book/{symbol}?limit=<=192&region=EEA`, public) : schéma v2 non documenté, lecture tolérante (`data.asks/bids` ou racine ; `p|price`, `q|quantity|amount`).
- Soldes : `GET /1.0/balances`, disponible par devise, valeurs > 0.
- Frais : `GET /1.0/configuration/fees/tiers` (signé, clé de lecture) : palier spot courant du compte (`current_tier`, volume 30 j), taux en fractions convertis en % du montant, identiques pour toutes les paires, cachés 5 min par clé API (`RevolutXTradingFeeProvider`) ; réponse sans palier spot ou illisible ⇒ `BalanceUnavailableException`, jamais supposés.
- Remplissages : `GET /1.0/orders/historical` (états `filled,cancelled`, fenêtres de 30 jours, 365 jours par défaut, pagination `cursor`), puis par ordre exécuté `GET /1.0/orders/{id}` (frais total) et `GET /1.0/orders/fills/{id}` ; frais total porté par le premier remplissage. Plafond d'appels par lecture (`MAX_CALLS`) ; `orders/historical` coûte 1 jeton par jour de plage (100 jetons/s, 1000/min) : pause de 12 ms par jour de plage entre deux fenêtres, et chaque lecture de l'historique est rejouée après un 429 (`RevolutXRequests.getWithRetry`, 4 tentatives, pause croissante).
- Limite de débit (429) : `getWithRetry` (lectures privées en rafale) et `publicGetWithRetry` (carnet public) réessaient 4 fois, pause 1 s x n° de tentative ; les autres appels échouent tout de suite.
- Non fait : bougies (pas de `MarketDataApiClient` / `MarketDataSource.REVOLUT_X`), repli ticker du carnet, ajout à `ExchangeKeysService.SupportedExchange` (onboarding utilisateur).
- Limites de débit : 1000 points / minute, `/trades` pèse 5 (espacement minimal entre appels).

## Gate EU (exchange, clé API + secret, API v4 spot)

Vérifié le : 2026-11-04 (`GateEuRequests`, `GateEu*` sous `service/connector/apiclient/`, `GateEuSpotOrderClient` sous `service/execution/exchange/`). **Jamais appelé en réel** : tests sur serveur HTTP local ; signature et formats non confirmés par la doc officielle (hôte EU relevé via ccxt).

- **Déclaration** : `WebProvider` `GATE_EU` déclaré dans l'onglet Providers de `/admin` (URL de base `https://api.gateeu.com/api/v4`, distincte de Gate.io global : comptes et clés séparés). Provider absent ou désactivé ⇒ aucun appel réseau.
- **Authentification** : en-têtes `KEY`, `Timestamp` (secondes), `SIGN` = HMAC-SHA512 hex de `MÉTHODE\nchemin complet (/api/v4/...)\nquery\nhex(SHA512(corps))\ntimestamp`. Clé + secret requis, pas de passphrase ; clé Trade chiffrée comme Kraken/OKX.
- Paires (`GET /spot/currency_pairs`, public) : `trade_status=tradable` ⇒ live, `amount_precision` ⇒ lot, `precision` ⇒ tick, `min_base_amount` / `min_quote_amount` ⇒ minimums. Paire native `BASE_QUOTE`.
- Carnet (`GET /spot/order_book?currency_pair=&limit=<=100`, public) : niveaux `[prix, quantité]`.
- Soldes : `GET /spot/accounts`, `available` par devise, valeurs > 0.
- Frais : `GET /wallet/fee?currency_pair=` (`maker_fee` / `taker_fee` en fraction ⇒ ×100), cache TTL.
- Remplissages : `GET /spot/my_trades` (fenêtres de 30 jours, depuis 2024-01-01 par défaut, pages de 1000, plafond d'appels).
- Non fait : bougies (pas de `MarketDataApiClient` / `MarketDataSource.GATE_EU`).

