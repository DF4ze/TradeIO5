# Prompt — Implémentation : page Admin d'exécution, lot 1 (socle + Console + Credentials + Bindings + journal)

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). Mission = **implémenter ce lot, rien d'autre**, puis s'arrêter pour un point avec Clem. Aucun ordre réel, aucun appel exchange authentifié pendant le développement.

## 0. Démarrage
1. Lis `docs/README.md`, `docs/CODING_RULES.md`, `docs/etudes/etude-ux-admin-execution.md` (décisions §8, écrans §3.1-3.3, architecture §5), `docs/prompts/prompt-roadmap-ux-admin-execution.md`, `architecture/10-execution-reelle.md`, `architecture/07-security.md`, `api/rest-endpoints.md`, `operations/flyway.md`, `securite/remarques-audit.md`.
2. Lis le code : `ExecutionAdminController`, `ExecutionPlanAdminController`, `RainbowLiveBindingController`/`RainbowLiveBindingService`, `RainbowLiveExecutionPlanController`/`OrderPlanQueryService`/`OrderPlanDtos`, `TradeCredentialService`, `ApiCredentialRepository`, `OrderEventLog` (patron d'écriture append-only en transaction indépendante), `MainController`, `adminPage.html`, `fragments/navbar.html`, `static/assets/js/rainbow-live.js` (patron `el()`/`api()`, `textContent`), `ExecutionAdminSecurityTest`, `MainControllerUserPageTest`.
3. Réponds en français, court et technique. Point flou → AskUserQuestion avant d'avancer. Tâches suivies avec le widget de tâches.

## 1. Décisions de Clem (ne pas remettre en question)
- Admin = tout compte `ROLE_ADMIN`. **Pas de durcissement sécurité des endpoints dans ce lot** (CSRF, SameSite, inscription… = audit, cf. `securite/remarques-audit.md`) : garder seulement `@PreAuthorize("hasRole('ADMIN')")` sur les nouveaux contrôleurs admin ; ne rien affaiblir.
- Page sobre, **sans charte**, onglets Bootstrap déjà chargé, Thymeleaf + JS vanilla, **`textContent` uniquement** (jamais `innerHTML`/`insertAdjacentHTML`/`outerHTML`/`document.write`/`th:utext`).
- Le **propriétaire saisit ses credentials** ; l'admin supervise (liste, désactive, arme). Les secrets ne sont jamais ré-affichés ni renvoyés.
- **Réengagement automatique du kill switch côté back** après `run`/`reconcile`.
- Garde-fous inchangés : `LIVE` verrouillé, kill switch engagé par défaut, double validation du 1er ordre, plafonds absolus, jamais de retrait/transfert, `ExecutionOrderArchitectureTest` inchangé dans son esprit. Aucun passe-droit « parce qu'on teste ».
- Hors lot : Kraken (client, `validate`, clé Trade Kraken), testeur d'ordre, smoke test, Plans & audit complet, routes PAXG, charte, pages utilisateur, test de validité des clés.

## 2. À livrer

### 2.1 Back-end
**Journal `admin_action_log`** (service `service/admin/AdminActionLogService`, entité, repository) :
- Champs : `id`, `actor` (username), `action` (enum : `KILL_SWITCH`, `ARM_BINDING`, `PLAN`, `RUN`, `RECONCILE`, `CREDENTIAL_STORED`, `CREDENTIAL_ENABLED`, `AUTO_REENGAGE`…), `target` (texte court : planId, bindingId, credentialId…), `payload` (JSON texte **sans secret**), `outcome` (`OK|REFUSED|ERROR`), `createdAt` (`DomainClock`).
- **Append-only** : aucune méthode `update`/`delete` exposée (repository restreint à `save` + lectures), écriture en transaction indépendante (patron `OrderEventLog`), un échec d'écriture du journal ne casse pas l'action mais est loggué en WARN.
- Branché sur : kill switch, arm, plan, run/reconcile, stockage et (dés)activation de credential, réengagement auto.
- `GET /api/admin/actions?page=0&size=20` (taille bornée 100, plus récent d'abord) → DTO `AdminActionDto`.

**Réengagement automatique** : `POST /api/admin/execution/run?planId=` (contrôleur existant) rengage le kill switch dans un `finally` dès que l'appel se termine (succès, blocage ou exception), si l'appel l'avait trouvé levé ; écrit `AUTO_REENGAGE` au journal avec `updatedBy = "auto-reengage"`. Le job cron `ExecutionJob` n'est pas modifié. Test : même si l'exécuteur lève, le kill switch est engagé.

**État global** : `GET /api/admin/execution/state` → `{mode, liveUnlocked, liveExecution, masterKeyConfigured, killSwitch, killSwitchUpdatedBy, killSwitchUpdatedAt, maxOrderMultiplier, maxDayMultiplier, absoluteMaxOrderUsd, absoluteMaxDayUsd, orderProviders[]}` (providers disposant d'un port d'ordres actif ; vide hors LIVE). Constantes absolues lues dans `ExecutionDefaults` (jamais dupliquées).

**Plans (admin, minimal)** : `GET /api/admin/execution/plans?from=&to=&userId=&status=` (tous users, révisions remplacées exclues, défaut 7 jours) → `AdminPlanDto` (id, user, jour, passe, révision, statut effectif, coût, Fee Test, blocages, `expiresAt`, actifs et étapes avec statut/fills/frais/slippage réels, réutiliser les mappings de `OrderPlanDtos`) ; `GET /api/admin/execution/plans/{id}/events?page&size` (cross-user, mêmes bornes que la version propriétaire). Nouveau service de lecture admin, base uniquement, aucun port d'ordre.

**Credentials** :
- `GET /api/admin/credentials` → `{id, userId, username, provider, scope, enabled, createdAt, apiKeyHint}` où `apiKeyHint` = 4 derniers caractères de la clé **en clair uniquement pour READ** ; pour TRADE (chiffrée) afficher « chiffrée » sans déchiffrer. Aucun secret, aucune passphrase.
- `PUT /api/admin/credentials/{id}/enabled` `{enabled}` (journalisé).
- Saisie par le propriétaire : `POST /api/credentials/trade` (`isAuthenticated`, user = utilisateur connecté, jamais un `userId` du corps) `{provider, apiKey, secretKey, passphrase, enabled?}` → `TradeCredentialService.store` (OKX seulement dans ce lot ; erreur claire pour un autre provider, clé maître absente ⇒ 409). `toString` du corps masqué. L'endpoint admin `POST /api/admin/execution/credentials` existant est conservé.
- `TradeCredentialService` : pas d'autre changement dans ce lot (Kraken = lot 2).

**Bindings** :
- `GET /api/admin/bindings` → tous users : `{id, userId, username, assetSymbol, exchange, walletId, walletName, presetId, presetName, bagPercent, priority, executionEnabled, tradability, firstLiveArmed, firstLiveConfirmed}`.
- Pour le formulaire du propriétaire : `GET /api/rainbow-live/wallets` (liste minimale `{id, name, exchange}` de l'utilisateur connecté, sans solde ni credential) ; les presets et la création/édition de binding existent déjà (`RainbowLiveBindingController`, scopé à l'utilisateur connecté).
- `arm` existant inchangé, journalisé.

Tous les DTO sont des `record` dédiés (`model/dto/admin/**`), jamais d'entité JPA. Pas de logique dans les contrôleurs (services `service/admin/**`). Mutualiser toute constante métier (libellés de blocage, tailles de page) dans un seul endroit.

### 2.2 Front (`/admin`)
- `adminPage.html` : onglets **Console**, **Credentials**, **Bindings**, **Journal** ; fragments `templates/fragments/admin/{console,credentials,bindings,journal}.html` ; JS `static/assets/js/admin/{admin-core,console,credentials,bindings,journal}.js` (un fichier par onglet, chargement paresseux, `admin-core.js` = `el()`, `api()` calqués sur `rainbow-live.js`, gestion d'erreur/alerte).
- **Bandeau permanent** (tous onglets) : mode · LIVE déverrouillé · clé maître · KILL SWITCH (engagé/levé + qui/quand).
- **Console** : choix du plan (liste admin) puis procédure en 7 étapes, chaque étape = bouton + état + préconditions grisées : 1 Planifier (`POST /plan`) · 2 Armer (`POST /bindings/{id}/arm`) · 3 Confirmation propriétaire (état affiché, pas de bouton admin) · 4 Lever le kill switch · 5 Exécuter (`POST /run`) · 6 Réconcilier (si `UNKNOWN`/`EXECUTING`) · 7 Réengagement (automatique, bouton « Réengager maintenant » en secours). Résultat `{status, blocked, sent}` affiché, motifs traduits en phrase + action suggérée via **une seule table de messages** (`MODE_NOT_LIVE`, `PORT_UNAVAILABLE`, `KILL_SWITCH`, `PLAN_EXPIRED`, `NO_BINDING`, `BINDING_DISABLED`, `NOT_TRADABLE`, `CREDENTIAL_INVALID`, `FIRST_LIVE_NOT_VALIDATED`, `NO_BASE_AMOUNT`, `CAP_*`, plus `51155`, `NOT_TRADABLE_WITHOUT_FIAT`). États vides et erreurs réseau/403 gérés.
- **Credentials** : tableau masqué + formulaire de saisie/rotation propriétaire (`type=password`, `autocomplete="off"`, champs vidés après envoi, jamais ré-affichés) + bouton activer/désactiver. Message dédié « `TRADEIO_MASTER_KEY` absente du processus ».
- **Bindings** : tableau admin cross-user + formulaire de création/édition propriétaire (actif, preset, wallet, bagPercent, priorité) + boutons Vérifier / Armer.
- **Journal** : liste paginée en lecture seule.
- Navbar : le lien « Administrator Page » existe déjà ; ne pas toucher à la charte.

### 2.3 Tests
- Sécurité par méthode pour chaque nouveau contrôleur (patron `ExecutionAdminSecurityTest`) : USER ⇒ refus, ADMIN ⇒ OK ; l'endpoint propriétaire `POST /api/credentials/trade` ignore tout `userId`.
- DTO sans secret : test par réflexion (aucun champ `secret|passphrase|apiKey` complet en lecture) ; `toString` des corps masqués.
- Journal : append-only (aucune méthode update/delete), payload sans secret, écrit pour chaque action listée.
- Réengagement : kill switch engagé après succès, blocage et exception de l'exécuteur ; cron non modifié.
- Rendu : `MainControllerAdminPageTest` (onglets, scripts attendus) ; scan statique **sans `innerHTML`** (et variantes) sur `static/assets/js/admin/**` et `templates/fragments/admin/**` ; aucun `th:utext`.
- Lecture admin des plans : tous users, filtres, révisions remplacées exclues, bornes de pagination.
- Architecture : `ExecutionOrderArchitectureTest`, `ExecutionPlanArchitectureTest`, `ConnectorNoOrderMethodTest`, `RainbowLiveNoExchangeDependencyTest` inchangés et verts.
- Build/tests : via le ssh-gateway sur la machine de Clem (`mvn` avec **JDK 21**, sortie redirigée vers un fichier). S'il est indisponible, **le dire** et ne pas prétendre que ça compile.

### 2.4 Documentation (sans historique, supprimer ce qui devient faux)
`api/rest-endpoints.md` (nouveaux endpoints admin/propriétaire, DTO), `architecture/10-execution-reelle.md` (page Admin, réengagement automatique, journal), `architecture/07-security.md` (credentials saisies par le propriétaire via l'UI, journal `admin_action_log`), `known-gaps/rainbow-dca-chantiers-ouverts.md` §5b (page de saisie des credentials : faite pour OKX ; le reste inchangé), `securite/remarques-audit.md` (retirer/ajuster ce qui change ; y ajouter toute nouvelle remarque rencontrée), `operations/flyway.md` si le schéma généré doit inclure la nouvelle table (règle des défauts : voir ce doc). Le fichier d'étude et cette roadmap ne sont pas modifiés sauf erreur constatée.

## 3. Critères d'acceptation
- La procédure OKX complète (plan → armer → confirmer → lever → exécuter → réconcilier → réengager) se déroule **sans curl ni SQL** depuis `/admin` (en `DRY_RUN`/kill switch engagé, chaque blocage est expliqué).
- Aucun secret dans aucune réponse, aucun log, aucun DTO ; saisie des clés par le propriétaire fonctionnelle (OKX).
- Chaque action admin est dans le journal avec auteur, cible, résultat.
- Kill switch toujours engagé à la fin d'un `run`/`reconcile`, même en erreur.
- Aucun `innerHTML` ; tous les tests (nouveaux + existants) verts ; garde-fous d'architecture inchangés.

## 4. Règles
Respecter `docs/CODING_RULES.md` et le style de Clem (`isEmpty()`/`getFirst()`/`getLast()`, constantes métier mutualisées, pas de variables locales redondantes, `assertTrue/False` directs, `DomainClock`, Lombok, logs INFO sur changements d'état). Ne pas coder ce dont on n'a pas besoin. Ne rien committer sans accord. À la fin : résumé court (ce qui est livré, tests, écarts, nouvelles remarques sécurité) puis **point avec Clem** avant le lot 2.
