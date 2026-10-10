# Sécurité

Vérifié le : 2026-10-10 (`security/WebSecurityConfig`, `security/apikey/ApiKeyAuthFilter`, `security/jwt/*`, controllers, `service/connector/**`, `model/entity/exchange/ApiCredential`).

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
- `ApiCredential` porte `apiKey`, `secretKey` et `passphrase` (nullable ; exigée par OKX à chaque requête), stockés en clair en base pour la portée `READ` (chiffrement optionnel à décider). Ils sont exclus de `toString()` (`@ToString.Exclude`, idem `Wallet#credential` ; `ApiCredentialDTO#toString` masque clé et secret). Aucun secret n'est loggué ni mis dans un message d'exception.
- Les clés d'OKlm sont semées par `ApiCredentialInitializer` depuis les propriétés gitignorées `tradeio.<binance|kraken|okx>.apiKey|secretKey` (+ `tradeio.okx.passphrase`) lues via `Environment` ; propriété absente => WARN, aucune credential, pas d'échec au démarrage.

## Clés d'exchange TRADE (exécution réelle)

- Portée `ApiCredential.scope = TRADE`, distincte de `READ` (unique par user, provider, portée). Côté exchange : droit **Trade uniquement, jamais Withdraw**, IP du VPS allowlistée, passphrase.
- **Chiffrées au repos** (AES-256-GCM, `enc:v1:…`) par `SecretCipher` ; clé maître dans la variable d'environnement `TRADEIO_MASTER_KEY` du VPS (jamais en base, jamais dans Flyway, jamais en clair dans un log). Sans clé maître : création impossible et résolution vide, aucun repli en clair. Lecture uniquement via `TradeCredentialResolver` (copie détachée déchiffrée).
- Création / rotation : API admin seule (`POST /api/admin/execution/credentials`), réponse sans secret ; aucun secret dans les logs, exceptions, DTO ni événements d'audit.
- `ConnectorNoOrderMethodTest` inchangé : `service.connector` reste sans méthode d'ordre ; les ordres n'existent que dans `service.execution.exchange`, appelé par le seul `service.execution.run` (`ExecutionOrderArchitectureTest`). Le bean client d'ordres n'existe qu'en `LIVE` déverrouillé. Détail : [`10-execution-reelle.md`](10-execution-reelle.md).
- Endpoints de pilotage (`/api/admin/execution/**`) : `ROLE_ADMIN` strict (test `ExecutionAdminSecurityTest`) ; interrupteur et confirmation du 1ᵉʳ ordre : propriétaire du binding seulement (autre user ⇒ 404).

## Accès propriétaire (bench Rainbow)

`RainbowLiveController` (`/api/rainbow-live`) : `@PreAuthorize("isAuthenticated()")` au niveau classe (test `RainbowLiveControllerSecurityTest`, qui active réellement la sécurité par méthode), pas de `ROLE_API_AGENT`. Isolation par propriétaire : un preset d'un autre utilisateur est indiscernable d'un preset inexistant (404, pas de fuite d'existence).
