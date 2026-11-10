# Sécurité

Vérifié le : 2026-11-07 (`service/expert/ExpertAccessGuard`, `security/WebSecurityConfig`, `security/apikey/ApiKeyAuthFilter`, `security/jwt/*`, controllers, `service/connector/**`, `model/entity/exchange/ApiCredential`).

## Deux mécanismes d'authentification, cumulables

1. **Cookie JWT** (`security/jwt/AuthTokenFilter` + `JwtUtils`) — utilisateur interactif, posé à `POST /api/auth/signinForm`, vérifié sur les requêtes suivantes.
2. **Clé API statique** (`security/apikey/ApiKeyAuthFilter`, header `X-Api-Key`) — pensé pour un agent machine-à-machine sans session interactive (ajouté le 2026-09-03). **Désactivé par défaut** : `tradeio.security.agent-api-key` vide/absent ⇒ le filtre ne fait rien. Un client authentifié par ce biais reçoit **uniquement** `ROLE_API_AGENT`, jamais `ROLE_ADMIN` — volontairement, pour ne pas exposer les endpoints de déclenchement (`orchestrate`/`snapshot`/`archive`) derrière une simple clé statique dans un header. Comparaison en temps constant (`MessageDigest.isEqual`). N'écrase jamais une authentification déjà présente dans le contexte (ex. cookie JWT valide prioritaire).

`ROLE_API_AGENT` donne accès uniquement aux deux endpoints de **lecture** (`GET /api/admin/decision/scenarios`, `/decisions`, via `@PreAuthorize("hasAnyRole('ADMIN','API_AGENT')")` posé à la méthode). Tous les autres endpoints admin restent `ROLE_ADMIN` strict.

## Le filtre au niveau `SecurityFilterChain` est permissif — la vraie protection est au niveau méthode

`WebSecurityConfig#filterChain` contient :

```java
.authorizeHttpRequests()
.requestMatchers("/api/auth/**").permitAll()
.requestMatchers("/**").permitAll()
.anyRequest().authenticated()
```

`.requestMatchers("/**").permitAll()` matche déjà toutes les routes avant que `.anyRequest().authenticated()` ne s'applique — cette dernière ligne est donc **sans effet pratique** sur la chaîne de filtres HTTP. Ce n'est pas un correctif oublié : le code confirme lui-même cette lecture (javadoc de `CalibrationController`, qui s'appuie explicitement sur ce fait pour justifier l'absence de `@PreAuthorize` sur un endpoint public délibéré). **La protection réelle est assurée par `@EnableMethodSecurity(prePostEnabled = true)` + les annotations `@PreAuthorize` posées sur chaque contrôleur/méthode** — pas par la chaîne de filtres URL. Un nouveau contrôleur sans `@PreAuthorize` est de facto public, quel que soit son chemin.

## Session

`SessionCreationPolicy.STATELESS` — pas de session serveur, tout repose sur le cookie JWT ou la clé API à chaque requête. CSRF désactivé (cohérent avec l'authentification par cookie JWT + API key plutôt que par session/formulaire classique).

## Rôles

`ROLE_USER` / `ROLE_MODERATOR` / `ROLE_ADMIN` (Spring Security classique, `Role`/`RoleRepository`), plus `ROLE_API_AGENT` (jamais persisté en base, attribué uniquement par `ApiKeyAuthFilter` à la volée).

## Clés d'exchange (lecture seule)

- Les connecteurs exchange (`KrakenApiClient`, `BinanceApiClient`, `OkxBalanceReader`, `OkxApiClient`) sont en **lecture seule** : aucune méthode d'ordre n'existe dans `service.connector` (test d'architecture `ConnectorNoOrderMethodTest`, qui échoue si une méthode `buy|sell|placeOrder|newOrder|createOrder|cancelOrder` apparaît). La vraie protection reste côté exchange : clé API **Read** pour les connecteurs de lecture, et **IP allowlistée** (IP publique du VPS).
- `ApiCredential` porte `apiKey`, `secretKey` et `passphrase` (nullable ; exigée par OKX à chaque requête), chiffrés au repos pour les deux portées : `TRADE` par `TradeCredentialService` (`enc:v1:…`), `READ` de façon transparente par `ReadSecretConverter` (`encr:v1:…`, AES-256-GCM, même clé maître `TRADEIO_MASTER_KEY`, contexte authentifié distinct) — côté entité la valeur est en clair, les lecteurs existants ne changent pas. Écriture : valeur vide ou déjà chiffrée inchangée ; **mode strict par défaut** (`tradeio.security.secrets-strict=true`, posé par `ReadSecretCipherBinder`) : sans clé maître toute écriture d'un secret est refusée (`MasterKeyMissingException`, 409 côté admin), jamais de repli en clair, et le démarrage logue une ERREUR (binder, migrateur, `ApiCredentialInitializer` qui ne sème alors rien). `secrets-strict=false` (propriété des tests uniquement) rétablit l'écriture en clair. Lecture : une valeur illisible (autre clé, altérée) est renvoyée brute avec une alerte, sans exception. `ReadCredentialEncryptionMigrator` (`@Order(45)`, après les initializers) chiffre au démarrage les secrets READ encore en clair (idempotent, ignore les valeurs vides et les clés TRADE) et logue en ERREUR le nombre de credentials restés en clair (clé maître absente, ou TRADE/valeur illisible). Supervision : `GET /api/admin/credentials/encryption` (`{masterKeyLoaded, writesBlocked, plaintextCredentials}`, sans secret) et `POST /api/admin/credentials/encryption/migrate` (relance la migration). Ils sont exclus de `toString()` (`@ToString.Exclude`, idem `Wallet#credential` ; `ApiCredentialDTO#toString` masque clé et secret). Aucun secret n'est loggué ni mis dans un message d'exception.
- Aucune clé n'est codée en dur dans les sources. Les clés d'OKlm sont semées par `ApiCredentialInitializer` depuis les propriétés gitignorées `tradeio.<binance|kraken>.apiKey|secretKey` et `tradeio.okx.read.apiKey|secretKey|passphrase` lues via `Environment` ; propriété absente => WARN, aucune credential, pas d'échec au démarrage. La clé OKX `TRADE` est semée de la même façon depuis `tradeio.okx.trade.apiKey|secretKey|passphrase`, **chiffrée** par `TradeCredentialService` (sans `TRADEIO_MASTER_KEY` : WARN, rien d'écrit) ; créée seulement si absente, la rotation passe par l'API admin.

## Clés d'exchange TRADE (exécution réelle)

- Portée `ApiCredential.scope = TRADE`, distincte de `READ` (unique par user, provider, portée). Côté exchange : droit **Trade au comptant uniquement** (spot), jamais Withdraw, IP de l'hôte allowlistée, passphrase (OKX seulement). Kraken : clé + secret sans passphrase ; droits « Create & modify orders » et « Cancel & close orders » uniquement, jamais « Withdraw funds » ; le nonce de chaque requête est strictement croissant par clé (`KrakenSignedRequests`). Clés System semées au démarrage depuis `tradeio.okx.trade.*` / `tradeio.kraken.trade.*` (jamais renseignées par le code). Les restrictions de conformité du compte (ex. paires USDT en EEA) s'appliquent quelle que soit la clé.
- **Chiffrées au repos** (AES-256-GCM, `enc:v1:…`) par `SecretCipher` ; clé maître dans la variable d'environnement `TRADEIO_MASTER_KEY` du VPS (jamais en base, jamais dans Flyway, jamais en clair dans un log). Sans clé maître **dans le processus de l'app** (lancement / config IntelliJ, pas seulement le shell) : création impossible et résolution vide, aucun repli en clair. Une fois la credential créée en base, retirer `tradeio.okx.trade.*` des propriétés. Lecture uniquement via `TradeCredentialResolver` (copie détachée déchiffrée).
- Clés READ saisies par l'utilisateur : `POST /api/credentials/read` (`ReadCredentialService`) : chiffrée, refusée sans clé maître (409), jamais renvoyée ; crée ou rattache le wallet de l'exchange (OKX, Kraken).
- Création / rotation : par le propriétaire pour sa propre clé (`POST /api/credentials/trade`, `isAuthenticated`, utilisateur = connecté, aucun `userId` accepté) ou par l'admin (`POST /api/admin/execution/credentials`, ou `POST /api/admin/credentials` depuis la page Admin pour tout utilisateur y compris System, portée Trade ou Lecture) ; réponse sans secret ; aucun secret dans les logs, exceptions, DTO ni événements d'audit.
- `ConnectorNoOrderMethodTest` inchangé : `service.connector` reste sans méthode d'ordre ; les ordres n'existent que dans `service.execution.exchange`, appelé par le seul `service.execution.run` (`ExecutionOrderArchitectureTest`). Le bean client d'ordres n'existe qu'en `LIVE` déverrouillé. Détail : [`10-execution-reelle.md`](10-execution-reelle.md).
- Journal d'audit `admin_action_log` (append-only, lecture `ROLE_ADMIN` via `GET /api/admin/actions`) : actions admin d'exécution et saisies de credentials, charge utile filtrée des secrets ([`10-execution-reelle.md`](10-execution-reelle.md)).
- Endpoints de pilotage (`/api/admin/execution/**`, `/api/admin/credentials`, `/api/admin/bindings`, `/api/admin/actions`, `/api/admin/catalog/**`, `/api/admin/execution/order-test/**`) : `ROLE_ADMIN` strict (test `ExecutionAdminSecurityTest`) ; interrupteur et confirmation du 1ᵉʳ ordre : propriétaire du binding seulement (autre user ⇒ 404).

## Génération de paires de clés par utilisateur

Providers dont l'auth repose sur une paire que l'utilisateur enregistre chez l'exchange (aujourd'hui Revolut X, Ed25519). Moteur générique : `ProviderKeyPairService` (`service/connector/keypair/`) + une implémentation de `ProviderKeyPairSupport` par provider (`RevolutXKeyPairSupport`). Ajouter un provider = un `@Component` implémentant `provider()`, `scopes()`, `algorithm()`/`keySize()`, `registrationHint()`, `verify(ApiCredential)` (et `fields(...)` si le mapping apiKey/secret/passphrase diffère).

- **Flux** : `generate` ⇒ paire créée, privée chiffrée (`SecretCipher`, contexte `pendingKey:userId:provider:scope`) dans `provider_pending_key` (unique user/provider/portée, TTL 24 h, régénérer remplace la paire en attente), clé publique + empreinte SHA-256 renvoyées ; `promote(apiKey)` ⇒ `support.verify` (appel réel signé, hors transaction) puis stockage par `TradeCredentialService`/`ReadCredentialService`, paire en attente supprimée ; clé refusée ⇒ 400, exchange injoignable ⇒ 503, rien n'est promu ; `cancel` et `ProviderPendingKeyPurgeJob` (`tradeio.keypair.purge-interval-ms`, défaut 1 h) suppriment les paires non promues.
- **Régénération / révocation** : régénérer = nouvelle paire + nouvelle promotion (la credential active n'est remplacée qu'à la promotion, aucune coupure) ; la révocation de l'ancienne clé se fait chez l'exchange.
- **Secrets** : clé privée chiffrée au repos, jamais dans un DTO, un log, une exception ni le journal (`CREDENTIAL_STORED`, payload `event` = `KEY_GENERATED|KEY_PROMOTED|KEY_CANCELLED|KEY_REFUSED|KEY_UNVERIFIED` + empreinte) ; clé publique non conservée après promotion ; sans `TRADEIO_MASTER_KEY` ⇒ 409.
- **Endpoints** (authentifié, utilisateur connecté) : cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

## Accès propriétaire (Rainbow live)

`RainbowLiveController` (`/api/rainbow-live`) : `@PreAuthorize("isAuthenticated()")` au niveau classe (test `RainbowLiveControllerSecurityTest`, qui active réellement la sécurité par méthode), pas de `ROLE_API_AGENT`. Isolation par propriétaire : un preset d'un autre utilisateur est indiscernable d'un preset inexistant (404, pas de fuite d'existence).

## Espace Expert (`/api/expert/**`)

`ExpertAccessGuard#require` en tête de chaque endpoint : authentifié + `displayMode` EXPERT (ADMIN compris), sinon 403 `EXPERT_MODE_REQUIRED` ; `@PreAuthorize("isAuthenticated()")` au niveau classe. Preset ou simulation d'un autre utilisateur ⇒ 404. Preset SYSTEM : modification réservée à `ROLE_ADMIN`, tracée dans `admin_action_log` (`AdminAction.PRESET`). Simulation bornée (une active par utilisateur, débit limité) : [`13-espace-expert.md`](13-espace-expert.md).
