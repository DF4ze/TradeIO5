# Sécurité — remarques à traiter (base de la phase d'audit)

Section de collecte : on y note toute remarque de sécurité au fil des chantiers ; la phase d'audit les traitera ensemble. État décrit : **actuel** (une remarque corrigée est supprimée, pas historisée). Description du fonctionnement sécurité : [`../architecture/07-security.md`](../architecture/07-security.md).

Dernière mise à jour : 2026-10-11 (lecture de code seule, rien n'a été exploité ni testé en réel).

Gravité : **C** critique · **H** haute · **M** moyenne · **B** basse. Statut : *à vérifier* = lu dans le code, non reproduit.

## 1. Mode d'authentification (vérifié dans le code le 2026-10-11)

| # | Gravité | Constat | Source | Statut |
|---|---|---|---|---|
| A1 | **C** | `POST /api/auth/signupForm` est public (`permitAll`) et prend les **rôles dans la requête** (`SignupRequest.roles`, cases à cocher alimentées par `allRoles` sur `/register`) ⇒ n'importe qui peut s'inscrire avec `ROLE_ADMIN` s'il atteint l'application. Donne accès aux endpoints de pilotage d'exécution (kill switch, `/run`, credentials). | `AuthController#registerUserForm`, `register.html`, `WebSecurityConfig` | à vérifier (sans créer de compte en prod) |
| A2 | **H** | Secret JWT (`bezkoder.app.jwtSecret`) de **18 caractères**, **identique en dev et prod** ; algorithme HS512 (attend ≥ 64 octets). Un secret court est cassable hors ligne depuis un cookie volé/observé ; un cookie forgé = identité (donc ADMIN) arbitraire. | `application-*.properties` (gitignorées), `JwtUtils` | vérifié (longueur, égalité) |
| A3 | **H** | Cookie JWT sans `Secure`, sans `SameSite` explicite, chemin incohérent (`/` posé par `signinForm`, `/api` dans `generateJwtResponseCookie`, `/` à la déconnexion) ; CSRF désactivé ; `@CrossOrigin(origins="*")` sur `MainController` + `cors(withDefaults)`. | `JwtUtils`, `WebSecurityConfig`, `MainController` | vérifié |
| A4 | **M** | Déconnexion = effacement du cookie côté client seulement : le JWT reste valide 24 h (`jwtExpirationMs` = 86 400 000), aucune révocation, aucune liste noire. | `AuthController#logoutUserForm`, `JwtUtils` | vérifié |
| A5 | **M** | Aucune limitation des tentatives de connexion ni verrouillage ; mot de passe 6 à 40 caractères, sans règle de complexité. | `AuthController#authenticateUserForm`, `SignupRequest` | vérifié |
| A6 | **M** | `User` (`@Data`) inclut `password` (hash BCrypt) dans `toString()` ; `logger.info("User Created: " + user)` l'écrit dans les logs. | `User`, `AuthController#registerUserForm` | vérifié |
| A7 | **M** | `SecurityFilterChain` : `requestMatchers("/**").permitAll()` ⇒ protection **uniquement** par `@PreAuthorize` ; un contrôleur oublié est public (déjà documenté). Pas d'en-têtes de sécurité (CSP, X-Frame-Options…) vérifiés. | `WebSecurityConfig` | vérifié |
| A8 | **B** | Message d'erreur de connexion journalisé avec le détail (`Bad credentials : …`) ; pas d'alerte sur échecs répétés ; JWT validé sur chaque requête avec rechargement du user (coût, pas de cache). | `AuthController`, `AuthTokenFilter` | vérifié |
| A9 | **B** | Page `/admin` charge jQuery depuis un CDN Google et Google Fonts (script tiers sur une page à actions sensibles). | `adminPage.html` | vérifié |
| A10 | **B** | Clé API machine `tradeio.security.agent-api-key` : comparée en temps constant ✔, désactivée si vide ✔, mais clé unique, sans rotation ni expiration ; à confirmer vide/non vide en prod. | `ApiKeyAuthFilter` | à vérifier |

Pistes (non décidées, pour l'audit) : fermer/restreindre l'inscription et **ignorer tout rôle fourni par le client** (rôle USER imposé côté serveur) ; régénérer un secret JWT ≥ 64 octets distinct par environnement (variable d'environnement, hors fichiers) ; cookie `Secure` + `SameSite=Strict` + chemin unique ; durée plus courte + révocation ; limitation des tentatives ; ne jamais logger l'entité `User`.

## 2. Journalisation des actions Admin et User (à terme)

Constat : journal `admin_action_log` en place pour les **actions admin d'exécution** (kill switch, armement, plan, `run`, réconciliation, réengagement automatique, saisie / activation de credential ; append-only, sans secret). Restent **non journalisées** : les actions utilisateur (bindings, presets, interrupteur d'exécution, confirmation du 1er ordre), les connexions / échecs de connexion, les modifications de stratégies Actif et les autres endpoints admin. Le journal ne porte ni rôle ni origine (cookie / clé API) de l'acteur, et n'a pas de rétention définie.

Cible (à préciser à l'audit) : étendre `admin_action_log` aux actions User sensibles : qui (user + rôle + origine : cookie/clé API), quoi, cible, charge utile **sans secret**, quand (`DomainClock`), résultat ; lecture admin seule ; aucune modification/suppression exposée ; rétention à définir.

## 3. Autres remarques collectées

- Page `/admin` (lot 1) : rendu en `textContent` uniquement (scan anti-`innerHTML` en test) ; `POST /api/credentials/trade` est ouvert à tout utilisateur authentifié (sa propre clé seulement) ; CSRF toujours désactivé (A3) : les actions admin (kill switch, `run`, saisie de clé) reposent sur le seul cookie JWT. Sécurité non durcie dans ce lot.

- Clé Trade Kraken : l'app ne peut pas vérifier les droits réels de la clé (retrait activé ou non) ; seul un contrôle humain à la création de la clé les garantit (rappel affiché dans l'onglet Credentials). Le testeur d'ordre (`validate`) déchiffre la clé Trade et appelle l'exchange avec elle : il reste derrière tous les verrous globaux et ne peut pas créer d'ordre (aucun appel à `submit`), mais il consomme le quota d'API de la clé.
- Nonce Kraken : compteur strictement croissant tenu **en mémoire du processus** (par empreinte SHA-256 de la clé) ; deux instances de l'app utilisant la même clé se disputeraient les nonces (`EAPI:Invalid nonce`, traité comme « issue inconnue », jamais renvoyé). Une clé Trade par instance.
- Réponse brute de `validate` renvoyée à l'admin : réduite à une liste blanche (`error`, `result.descr`), tronquée à 4 Ko ; journal `ORDER_VALIDATE` sans secret.
- Exécution : tests au plus près de la prod — **aucun passe-droit** en test (cf. règle dans l'étude UX Admin) ; tout verrou non levé = échec.
- Credentials READ stockées en clair en base (chiffrement optionnel non décidé) ; credentials TRADE chiffrées (AES-256-GCM, clé maître en variable d'environnement).
- Secrets de dev dans `ApiCredentialInitializer` (valeurs en dur, Binance testnet) : cf. note GitGardian, non bloquant mais à nettoyer.
