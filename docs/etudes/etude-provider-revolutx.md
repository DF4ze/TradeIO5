# Étude — Provider Revolut X : clés générées par utilisateur, candles, exécution

Statut : **étude, aucun code**. Vérifié le : 2026-11-10 (`RevolutXRequests`, `RevolutX*` sous `service/connector/apiclient/`, `RevolutXSpotOrderClient`, `ApiCredential`, `SecretCipher`, `ReadSecretConverter`, `ExchangeKeysService`, `MarketDataApiClient`) et doc officielle Revolut X (OpenAPI 1.0).

## 1. Point de départ (déjà dans le code)

- Connecteur **lecture + ordres déjà écrit**, jamais appelé en réel : signature Ed25519 (`RevolutXRequests`), soldes, remplissages, catalogue de paires, carnet, frais (propriétés), client d'ordres IOC + validateur local. Cf. `architecture/05-external-providers.md`, `architecture/10-execution-reelle.md`.
- Secrets : `ApiCredential(user, provider, scope READ|TRADE)` ; `apiKey` / `secretKey` chiffrés AES-256-GCM (clé maître `TRADEIO_MASTER_KEY`, aucun repli en clair). Pour Revolut X, `secretKey` = clé privée Ed25519 **PEM PKCS#8 collée à la main** (admin ou utilisateur).
- `ExchangeKeysService.supported()` dérive de `TRADABLE_PROVIDERS`, qui contient `REVOLUT_X` : l'onboarding le propose déjà (le front est à vérifier).
- **Manque** : génération des clés par le serveur, état « clé générée, clé API pas encore saisie », régénération sans coupure, candles, frais via endpoint, UX.

## 2. Ce que dit Revolut X

- Base `https://revx.revolut.com/api/` ; en-têtes `X-Revx-API-Key` (64 car.), `X-Revx-Timestamp` (ms), `X-Revx-Signature` (Ed25519). Une clé API = un compte (Retail ou Business). Une clé peut être **restreinte à des IP** (401 sinon).
- **Permissions read/trade par clé** : absentes de la doc publique (seuls les endpoints RFQ listent des scopes), mais **confirmées par Clem dans l'interface Revolut X** : la différenciation lecture seule / trading existe. Deux paires (READ, TRADE) sont donc justifiées.
- Aucune API de création / révocation de clé : tout passe par l'interface web de l'utilisateur. Pas de testnet.
- Limites : public 1 jeton/s ; privé 100/s et 1 000/min ; ordres 10/s et 1 000/jour ; candles authentifiées coût `max(5000, nb)`. 429 + `Retry-After` (ms).
- Candles : `GET /1.0/public/candles/{symbol}` (public, ≤ 1 000 sans `since`) et `GET /1.0/candles/{symbol}` (privé, ≤ 5 000) ; `interval` en minutes (1, 5, 15, 30, 60, 240, 1440…) ; `since`/`until` en ms. **Profondeur d'historique non documentée.**
- Frais : `GET /1.0/configuration/fees/tiers` (maker/taker par palier, selon le volume 30 j) : remplace les propriétés `tradeio.revolutx.fee.*`.
- Paires : `GET /1.0/configuration/pairs` (région de l'appelant) ; public : `?region=EEA|UK`. **Mesuré le 2026-11-10 (public, EEA)** : 375 paires ; **BTC/EUR, BTC/USD, BTC/USDC, ETH/EUR, ETH/USD, ETH/USDC** actives (`base_step` 1e-8, `quote_step` 0,01, minimum 0,1 en cotation) ; **PAXG absent** (ni base ni cotation).

## 3. Moteur de clés par utilisateur

### Flux
1. L'utilisateur clique « Ajouter Revolut X » (portée READ ou TRADE).
2. Le serveur génère une paire Ed25519 (`KeyPairGenerator "Ed25519"`, JDK 21) : privée PKCS#8 → chiffrée ; publique SPKI → affichée en PEM.
3. L'utilisateur colle la clé publique dans Revolut X, récupère la clé API (64 car.) et la saisit dans TradeIO.
4. Le serveur teste la paire (appel signé en lecture), puis **promeut** en `ApiCredential`. Échec ⇒ rien n'est promu.

### Stockage (recommandation)
Table dédiée **`provider_pending_key`** (user, portée, privée chiffrée, publique PEM, empreinte SHA-256, créée le, expire le), **sans toucher `ApiCredential`** :
- `ApiCredential.apiKey` est non nul et la contrainte `(user, provider, scope)` est unique : un état « sans clé API » y est impossible sans assouplir le modèle, et un lecteur pourrait tomber sur une credential incomplète ;
- la régénération sans coupure devient naturelle : l'ancienne `ApiCredential` reste active tant que la nouvelle n'est pas promue.

Chiffrement : `SecretCipher` (AES-GCM), contexte authentifié distinct (`revolutx_pending.privateKey`). Après promotion, `secretKey` de l'`ApiCredential` porte la privée : **même chemin de lecture que l'existant** (`RevolutXRequests.privateKey` lit déjà du PKCS#8). La publique n'est pas conservée après promotion (le JDK ne sait pas la dériver de la privée) : pour ré-enregistrer, on régénère.

### Cycle de vie
- **Régénération** : une nouvelle pending remplace la pending précédente du même (user, portée) ; la promotion écrase `secretKey` / `apiKey` de l'`ApiCredential`. L'ancienne clé reste valide côté Revolut jusqu'à ce que l'utilisateur la supprime : l'UI doit le dire.
- **Expiration** : une pending non promue est purgée après 24 h (job planifié).
- **Révocation** : `ExchangeKeysService` supprime déjà une clé (lecture ⇒ liens archivés ; écriture ⇒ exécution coupée). Aucune révocation côté Revolut possible depuis TradeIO.
- **Clé refusée** (401/403, `CredentialRejectedException`) : à relier au Docteur (alarme « clé Revolut X refusée ») et à la coupure d'exécution du lien concerné.
- **Audit** : `admin_action_log` existant ; ajouter les actions « clé générée » et « clé promue » (charge utile sans secret).

### Sécurité
Endpoints authentifiés, utilisateur = connecté (aucun `userId` accepté) ; privée jamais renvoyée, loguée ni sérialisée ; génération refusée sans clé maître (409, comme `MasterKeyMissingException`) ; limite de génération par utilisateur (ex. 5/h) ; l'UI affiche l'**IP sortante du VPS** à whitelister côté Revolut.

## 4. Provider de candles

- `RevolutXMarketDataApiClient implements MarketDataApiClient` (contrat : public, sans credential) sur `GET /1.0/public/candles/{symbol}` ; `MarketDataSource.REVOLUT_X` (CEX) à ajouter. Mapping H1 → 60, D1 → 1440 (comme Kraken). Symbole interne `BASE-QUOTE`.
- Public = **1 jeton/s** : espacement minimal entre appels obligatoire ; la politique « tout fetch stocké en base » (cache H1/D1 existant) limite la charge.
- ≤ 1 000 bougies par appel : pagination par `until` reculant. **Profondeur mesurée le 2026-11-10** (public, `BTC-EUR`, D1, sans `since`) : ~850 bougies, la première au **2024-07-02** (≈ 2,3 ans ; compte approximatif, quelques trous et décalages d'une heure à confirmer par comptage programmatique en L0). **Suffisant** : la fenêtre D1 du Rainbow live est de **720 j** (`etude-source-candles-provider-du-lien.md`) et les fenêtres de la page Rainbow (6 / 12 / 24 mois / Maxi) restent dans cet historique ; « Maxi » = l'historique disponible chez le provider.
- La doc indique que la vue s'appuie sur les trades récents s'il y a du volume : écart possible avec Binance/Kraken ⇒ le warn de divergence de bougies existant (fallback multi-provider, étape 4) s'applique.
- **Règle existante (2026-10-12), inchangée** : le signal Rainbow d'un lien est calculé sur les candles du **provider du lien**, sans repli silencieux ; le provider choisi ne bloque pas l'affectation du paramétrage à un autre couple actif / provider. Conséquences pour Revolut X :
  - `REVOLUT_X` doit **apparaître comme provider sélectionnable dans la page Rainbow** (liste issue de `MarketDataSource`) ;
  - ajout à la **liste blanche** des sources de confiance pour la création à la volée de la ligne `asset_provider` (Binance, Kraken, OKX + Revolut X) ;
  - table de mapping `providerSymbol` : `BTC` → `BTC-USDC`, `ETH` → `ETH-USDC` (règle de Clem : **toutes les cotations en USDC**) ; PAXG hors périmètre (absent chez Revolut X).

## 5. Exécution

Déjà codée. Périmètre d'actifs : **BTC et ETH** (cotation **USDC**) ; PAXG indisponible chez Revolut X. À confirmer **en réel** (pas de testnet) : `allow_taker` requis pour l'IOC, format UUID de `client_order_id`, visibilité immédiate dans `active`/`historical`. Premier ordre via la procédure existante (switch + confirmation du 1ᵉʳ ordre, petit montant). Frais : passer des propriétés à `fees/tiers`.

## 6. Lots proposés

| Lot | Contenu | Dépend de |
|---|---|---|
| L0 | Spike lecture réelle avec la paire déjà générée par Clem (saisie admin actuelle) : signature, soldes, paires, **profondeur candles**, permissions réelles | — |
| L1 | Provider de candles `REVOLUT_X` + présence dans la page Rainbow + liste blanche `asset_provider` | L0 |
| L2 | Moteur de clés (génération, pending, promotion, régénération, révocation, audit, Docteur) | L0 |
| L3 | UI Exchanges / onboarding (flux § 3, IP à whitelister) | L2 |
| L4 | Frais via `fees/tiers` | L0 |
| L5 | Premier ordre réel | L0, L4 |

Persistance : nouvelle table ⇒ migration Flyway (Flyway préparé, non activé : cf. `operations/flyway.md`) ; à trancher avec l'état d'activation au moment du lot L2.

## 7. Décisions et questions

Tranchées par Clem (2026-11-10) :
- Permissions par clé (lecture seule / trading) : **oui** ⇒ deux paires READ / TRADE.
- Actifs : **BTC et ETH** pour l'instant, PAXG si disponible (il ne l'est pas chez Revolut X).
- Saisie de la clé API : possible dans **Admin** et dans **Utilisateur / Exchanges** ⇒ L0 se fait par la page Exchanges existante (test de lecture), sans développement.

Ouvertes :
1. ~~Cotation~~ : **tranchée, USDC** pour tous les providers (candles, signal, exécution) : `BTC-USDC`, `ETH-USDC` chez Revolut X.
2. Le rythme public d'1 jeton/s suffit-il pour le backfill initial ? Sinon, candles authentifiées (5 000 par appel, coût élevé).
3. Une clé TRADE peut-elle aussi lire (`GET /1.0/orders/active`) pour le test de promotion ?

Réglée (2026-11-10) : l'historique court de Revolut X (~2,3 ans) n'est pas un problème, la règle « provider du lien, sans repli » et la fenêtre D1 de 720 j s'appliquent telles quelles.

## 8. Documentation à mettre à jour à l'implémentation

`architecture/05-external-providers.md` (section Revolut X), `architecture/07-security.md` (moteur de clés, table pending), `architecture/10-execution-reelle.md`, `architecture/04-market-data.md` (nouvelle source), `architecture/12-doctor.md` (alarme).
