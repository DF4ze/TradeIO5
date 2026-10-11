# Étude — UX Admin d'exploitation (exécution réelle multi-exchange), pilotage du test Kraken

Rédigée le 2026-10-11, analyse seule (aucun code, aucun ordre, aucun appel authentifié). Sources : `docs/**` listés au prompt + code (`ExecutionAdminController`, `ExecutionPlanAdminController`, `MainController`, `adminPage.html`, `rainbow-live.js`, `ApiCredentialInitializer`, `TradeCredentialService`, `ExecutionGuard`, `SpotOrderPort`, `Kraken*`, `PathFinder`, `ExchangeInstrument`, `WebSecurityConfig`, `JwtUtils`). Ce que ne couvre pas cette étude : exactitude des paramètres Kraken (`AddOrder`, permissions de clé) — **à reconfirmer dans la doc officielle** avant le lot Kraken.

## 1. Reformulation

Remplacer « curl + SQL » par une page **Admin d'exécution** (Thymeleaf + JS vanilla, `textContent`, sans charte) pour piloter/superviser les ordres réels, d'abord pour **introduire Kraken** : `validate=true` puis ordre réel minimal `BTC-USDC`/`ETH-USDC`. Six écrans : Console, Credentials, Bindings, Testeur d'ordre, Plans & audit, Catalogue & routes. Garde-fous intacts (LIVE verrouillé, kill switch engagé par défaut, 1er ordre à double validation, plafonds absolus, jamais de retrait, fiat interdit).

### Coquilles / ambiguïtés
- Le prompt dit « Prérequis : lot B a–e livré » ; les docs ne décrivent que a, b, C1, C2 → on suppose c–e = C1/C2 + 1er ordre OKX.
- `07-security.md` cite « les clés d'OKlm » : c'est le user de dev `OKlm` (pas une faute de frappe de Clem) — le seul user seedé avec clés exchange.
- « OKX = calcul local/démo » : OKX n'a pas de `validate` natif ; le mode démo OKX exige des clés démo distinctes → proposé : **calcul local uniquement** (tick/lot/min + plafonds + garde), voir Q4.
- « Testeur d'ordre » appelant un client d'ordres depuis un contrôleur viole `ExecutionOrderArchitectureTest` (seuls `exchange` et `run` référencent `service.execution.exchange`) → voir §5.2.
- `adminPage.html` n'a pas 76 lignes de contenu utile : un titre « Welcome » ; aucune migration.

## 2. État des lieux vérifié

### 2.1 Endpoints admin (tous `ROLE_ADMIN`, `@PreAuthorize` classe)
| Méthode | Chemin | Effet |
|---|---|---|
| POST | `/api/admin/execution/plan[?day&pass]` | construit les plans dry-run de **tous** les users → `PlanSummary` |
| POST | `/api/admin/execution/run?planId=` | exécute (`PLANNED`) ou réconcilie (`EXECUTING`) → `ExecutionResult{planId,status,blocked{actif:raison,*:global},sent}` |
| GET | `/api/admin/execution/control` | `{killSwitch,maxOrderMultiplier,maxDayMultiplier,updatedAt,updatedBy}` |
| PUT | `/api/admin/execution/kill-switch` | `{engaged}` ; `updatedBy` = username |
| POST | `/api/admin/execution/bindings/{id}/arm` | arme le 1er ordre réel (remet la confirmation owner à 0), 204/404 |
| POST | `/api/admin/execution/credentials` | crée/remplace la clé TRADE (OKX seulement, chiffrée) → `{id,userId,provider,scope,enabled}` ; sans `TRADEIO_MASTER_KEY` ⇒ 409 |

Côté propriétaire (`isAuthenticated`, scopé user, 404 si autre user) : bindings CRUD + `/execution` + `/confirm-first-live` + `/check` + `/path-quote`, `execution-plans[/latest|/{id}/events]`, `execution-state` (mode, liveExecution, killSwitch), `live-wallet`.

### 2.2 Manques par écran
| Écran | Manque back |
|---|---|
| Console | rien pour l'état (`control` + `execution-state`) ; **pas d'endpoint plans admin** (lister les plans pour choisir un `planId`), pas de mode/`liveUnlocked` dans `control` |
| Credentials | **aucun listing** (même masqué), aucun « dernier test », aucun test de validité sans ordre, création **OKX seule** (`TRADABLE_PROVIDERS`), pas de désactivation/suppression, credentials READ non gérables (seed properties seulement) |
| Bindings | `arm` prend un `id` brut : **aucune liste admin cross-user** ; création/édition seulement côté owner (pas de formulaire, pas de liste des wallets/presets) |
| Testeur | rien : aucun client d'ordres Kraken, aucun `validate`, port absent hors LIVE déverrouillé |
| Plans & audit | `OrderPlanQueryService` est **scopé owner** (plans, events) ; pas de vue admin multi-user ; pas de filtre UNKNOWN |
| Catalogue & routes | `ExchangeInstrument` n'a ni `costmin` ni devise de frais ; `path-quote` = **un seul** chemin (le meilleur), pas de comparaison de routes ; catalogue non filtré par instruments du compte |

### 2.3 Sécurité (écarts à traiter avant d'ajouter des boutons)
- Chaîne de filtres **permissive** : seule la protection par méthode (`@PreAuthorize`) compte ⇒ tout nouveau contrôleur admin doit porter `hasRole('ADMIN')` + test de sécurité par méthode.
- `/admin` est protégé (`hasRole('ADMIN')`), mais `adminPage.html` charge jQuery depuis un CDN Google et Google Fonts : pour une page qui porte des actions à risque, préférer des assets locaux (ou au moins ne pas ajouter d'autre script tiers).
- **CSRF désactivé + cookie JWT sans `SameSite`** (`ResponseCookie…path("/api").httpOnly(true)` ; le défaut navigateur est `Lax`, non garanti) + `@CrossOrigin(origins="*")` sur `MainController` + `cors(withDefaults)`. Aujourd'hui acceptable (les endpoints à risque sont difficiles à déclencher), mais « lever le kill switch » en un POST JSON depuis un navigateur connecté est le type d'action à protéger : **proposé** `SameSite=Strict` sur le cookie + refus des requêtes `/api/admin/**` mutantes sans en-tête `X-Requested-With` (non posable cross-site sans preflight) — à valider (Q7).
- **Journal d'audit des actions admin : inexistant.** `execution_control` ne garde que le dernier `updatedBy`/`updatedAt` ; `arm` ne trace rien (juste `firstLiveApprovedAt`, sans qui) ; la création de credential n'est loguée qu'en INFO. `rainbow_live_order_event` est lié à un plan/étape (append-only), inadapté aux actions globales.
- Secrets : jamais en réponse/log/`toString` (record `TradeCredentialRequest` masqué) ✔ ; READ en clair en base (chiffrement optionnel non décidé) — la page ne doit jamais les relire.
- Exécution : `ExecutionResult.blocked` donne les raisons ; le panneau owner existant (`rainbow-live.js`) affiche déjà kill switch (affichage seul), interrupteur, 1er ordre, audit paginé.

### 2.4 Kraken aujourd'hui
`KrakenApiClient` (lecture : `BalanceEx`, `TradesHistory`) avec signature HMAC-SHA512 **privée** (`privatePost`) ; `nonce = System.currentTimeMillis()` — **non monotone** (deux appels dans la même ms ⇒ `EAPI:Invalid nonce`, et concurrence avec la clé Read). `KrakenInstrumentListClient` lit `AssetPairs` (ordermin, lot_decimals, tick_size) mais **ignore `costmin`**, `fee_volume_currency`, `fees`. `SpotOrderPort` n'a qu'un implémenteur (`OkxSpotOrderClient`, `@Conditional(LiveExecutionCondition)`) ; `OrderExecutor`/`StepRunner` injectent `ObjectProvider<SpotOrderPort>` (un seul port, **pas de routage par provider**) ; `TradeCredentialResolver.resolve(user, provider)` est déjà par provider ✔. Le `clOrdId` interne (`t5…`, ≤ 32, alphanumérique) est à mapper sur le champ client de Kraken (à vérifier : `cl_ord_id` vs `userref`, longueurs).

## 3. Écrans proposés (ordre = ordre de livraison)

Onglets d'une page `/admin` unique (barre d'onglets Bootstrap déjà présent, JS lazy par onglet). Tous les montants/IDs affichés en `textContent`.

### 3.1 Console d'exécution
Bandeau permanent (visible sur tous les onglets) : `MODE` · `LIVE déverrouillé` · `KILL SWITCH` (+ qui/quand). Procédure guidée en 7 étapes, chaque étape = bouton + état + préconditions grisées :

```
┌ Console ─ MODE: LIVE (déverrouillé) ─ KILL SWITCH: ENGAGÉ (admin, 10/10 14:02) ┐
│ 1 Plan        [Planifier jour 2026-10-11 passe T2355]   ✔ plan #42 PLANNED     │
│ 2 Armer       binding BTC/OKX #7   [Armer]               ✔ armé 12:01          │
│ 3 Confirmer   (propriétaire)                              ⏳ en attente        │
│ 4 Lever KS    [Lever 5 min]  (désactivé tant que 2-3 ≠ ✔)                       │
│ 5 Exécuter    plan #42  [Exécuter]                        —                    │
│ 6 Réconcilier [Réconcilier] (actif si UNKNOWN/EXECUTING)  —                    │
│ 7 Réengager   automatique à la fin  [Réengager maintenant]                      │
│ Dernier résultat : sent=1 status=EXECUTED blocked={}                            │
└─────────────────────────────────────────────────────────────────────────────┘
```
Blocages traduits en phrase + action : `KILL_SWITCH` « kill switch engagé → étape 4 », `MODE_NOT_LIVE`/`PORT_UNAVAILABLE` « LIVE non déverrouillé / aucun client d'ordres pour ce provider », `FIRST_LIVE_NOT_VALIDATED`, `CAP_*` (montant vs plafond), `NO_BASE_AMOUNT`, `CREDENTIAL_INVALID`, `NOT_TRADABLE`/`NOT_TRADABLE_WITHOUT_FIAT`, et exceptions exchange : `51155` (OKX, « paire USDT interdite par la conformité du compte »).
États vides : aucun plan du jour → « Aucun plan — [Planifier] » ; contrôle illisible (échec fermé) → « kill switch supposé engagé ».
**Réengagement** : voir Q5 ; recommandé côté **back** (atomique) plutôt que par le navigateur (onglet fermé = KS laissé levé).

### 3.2 Credentials
Tableau (user · provider · scope READ|TRADE · actif · créée le · dernier test · résultat), **sans aucune valeur secrète** (au plus 4 derniers caractères de la clé API, jamais du secret). Actions : saisie/rotation (formulaire POST ; champs `autocomplete="off"`, `type=password`, vidés après envoi), activer/désactiver, **tester** (appel de lecture sans ordre : OKX `account/balance`, Kraken `BalanceEx` pour READ ; pour TRADE : un `validate` minimal ou une lecture protégée par le droit Trade — Q3).
```
 user  provider scope  actif  créée       dernier test         clé (…)
 OKlm  OKX      READ   oui    2026-09-10  2026-10-11 ✔ 12:00   …a3f9
 OKlm  OKX      TRADE  oui    2026-10-10  2026-10-11 ✔ 12:01   …77c2   [Tester][Rotation][Désactiver]
 OKlm  KRAKEN   READ   oui    …           …                     …91bd   [Créer clé TRADE]
```
Erreurs : 409 « `TRADEIO_MASTER_KEY` absente dans le processus » (message dédié, pas de repli clair), clé refusée, droits trop larges. **Détection de droits** : si l'exchange expose les permissions de la clé (OKX `account/config`, Kraken : seulement par erreur `EGeneral:Permission denied`), afficher « Withdraw détecté ⇒ refuser » — réaliste seulement pour OKX (à vérifier).

### 3.3 Bindings
Liste admin cross-user : token · provider · wallet (nom) · user · `bagPercent`/priorité · `executionEnabled` · `tradability` (+ date du `check`) · état 1er ordre (non armé / armé / confirmé) · plafonds applicables. Formulaire de création/édition = réutilisation de `RainbowLiveBindingService` (admin pour user cible ?) — Q2. Boutons : Vérifier (`check`), Armer. Vide : « Aucun binding — créer ». Un binding `NOT_TRADABLE_WITHOUT_FIAT` affiche la cause et un lien vers Catalogue & routes (§3.6).

### 3.4 Testeur d'ordre
Formulaire : provider, user (clé TRADE), paire (liste issue du catalogue), sens, **montant en quote ou taille base**, prix plafond (pré-rempli : mid ± tolérance, arrondi tick).
Bouton **« Valider »** : calcul local (tick/lot/min/costmin, plafonds absolus, garde) puis, pour Kraken, `AddOrder validate=true` ; affichage : requête normalisée (sans signature), réponse brute **filtrée** (clés sensibles masquées), erreur lisible (`EOrder:Order minimum not met` → « sous `ordermin` = 0,00005 BTC »…).
Bouton **« Envoyer un vrai ordre minimal »** (seulement si Q6 = oui) : double confirmation (saisie du `clOrdId` ou de la paire), rappelle plafond (≤ plafond absolu et ≤ `baseAmount`×multiplicateur), refuse si KS engagé, mode ≠ LIVE, 1er ordre non validé, paire hors liste blanche test (`BTC-USDC`, `ETH-USDC`).
```
 Provider [KRAKEN▾] Paire [BTC-USDC▾] Sens [BUY▾] Taille [0.00005] Plafond [≤ 83 210,0]
 [Valider]  → ✔ validate OK  desc: "buy 0.00005 XBTUSDC @ limit 83210.0"   ordermin 0.00005 ✔ costmin 0.5 ✔ tick 0.1 ✔
 [Envoyer l'ordre minimal] (grisé : validate non fait dans les 2 dernières minutes)
```

### 3.5 Plans & audit
Liste admin des plans (tous users, filtres jour/statut/user/`UNKNOWN`), détail : étapes (statut, rempli, prix moyen, frais + devise, slippage réel, `lastError`), plafonds consommés du jour, flux `rainbow_live_order_event` paginé (lecture seule, JSON affiché en `textContent`/`<pre>`). Action contextuelle : « Réconcilier » sur `UNKNOWN`/`EXECUTING`. Vide : « Aucun plan sur la période ».

### 3.6 Catalogue & routes
Par provider : paires (`minSz`/`ordermin`, `costmin`, `tickSz`, `lotSz`, état, filtré/non filtré compte). Par actif×provider : **routes candidates côte à côte** (nombre de jambes, coût total, Fee Test, warnings liquidité/fiat), route **liée** au binding.
```
 PAXG @ KRAKEN
 ┌ Route A (sans fiat)      USDC→ETH→PAXG   2 jambes  coût 0,42 %  WARNING  liquidité faible PAXG/ETH   [Lier]
 └ Route B (via fiat) ⚠     USDC→USD→PAXG   2 jambes  coût 0,31 %  GREEN    ⚠ passage par le fiat (interdit) — non sélectionnable
```
Dépend de `PathFinder` étendu (§6.7). Hors du premier tir : sert la décision, pas le test Kraken.

### 3.7 Smoke test réel (confirmation d'une route)
**But** : prouver de bout en bout que le côté Trade fonctionne (clé, droits, IP, paire, conformité du compte) pour une route donnée, au moindre coût.
- **Portée** : une ligne par (user, wallet/provider, actif, route) ; bouton « Smoke test » sur l'écran Bindings, Credentials (par wallet/user) et Catalogue & routes (par route d'actif).
- **Ordre** : limit IOC du minimum permis (`ordermin`/`minSz`, `costmin`), arrondis tick/lot, plafonné par les constantes absolues ; paires `BTC-USDC`/`ETH-USDC` d'abord.
- **Exécution** : un plan de type `SMOKE_TEST` passé par `OrderExecutor` (donc **tous** les verrous, audit, `clOrdId`, réconciliation, `Transaction`). Pas de chemin d'envoi parallèle. Le 1er ordre réel d'un binding peut être ce smoke test.
- **Machine à états** (persistée, ex. entité `route_smoke_test`) : `NOT_DONE → PASSED` ou `FAILED_KEY` / `FAILED_ORDER` / `FAILED_GUARD` (verrou non levé, rien n'est marqué sur la clé ni la route) ; `UNKNOWN` = réconciliation en cours (jamais compté comme échec ni renvoyé).
- **Classification des échecs** : `FAILED_KEY` = problème de droits/clé/IP (Kraken `EAPI:Invalid key`, `Permission denied`, IP ; OKX `501xx`) ⇒ marqué sur la **credential**, toutes ses routes à re-tester après rotation ; `FAILED_ORDER` = refus de transaction par l'API (conformité `51155`/pays, `EOrder:*`, minimum, paire indisponible) ⇒ marqué sur la **route** (indisponible, code + message d'API conservés), la clé étant jugée valide. Erreur réseau/timeout ⇒ `UNKNOWN`, pas de verdict.
- **UI** : `PASSED` ⇒ bouton masqué + « confirmé le … (ordre …) » ; `FAILED_*` ⇒ cause lisible, bouton « Relancer » ; rotation de clé ⇒ retour à `NOT_DONE`. Double confirmation (le montant exact et la paire sont rappelés).
- **Effet sur le planner/guard** : une route `FAILED_ORDER` est écartée du `PathFinder`/plan ; `NOT_DONE` autorisé mais signalé (warning) (le smoke test n'est pas obligatoire avant un ordre).

## 4. Maquette des blocages (messages)
| Code | Message affiché | Action |
|---|---|---|
| `KILL_SWITCH` | Kill switch engagé : aucun ordre envoyé | étape « Lever » |
| `MODE_NOT_LIVE` / `PORT_UNAVAILABLE` | LIVE non déverrouillé ou pas de client d'ordres pour ce provider | afficher mode, `live-unlocked`, providers câblés |
| `FIRST_LIVE_NOT_VALIDATED` | 1er ordre : armé/confirmé ? | boutons Armer / rappel owner |
| `CAP_ORDER`/`CAP_DAY`/`CAP_ABSOLUTE_*` | Plafond atteint (montant vs plafond) | rien (permanent) |
| `NOT_TRADABLE_WITHOUT_FIAT` | Aucun chemin sans fiat pour cet actif/provider | lien Routes |
| `CREDENTIAL_INVALID` | Clé Trade absente/refusée/non déchiffrable | lien Credentials |
| `51155` (OKX) | Paire interdite par la conformité du compte (EEA) | choisir paire USDC |
| `NO_PATH` / `BELOW_MIN` / `INSUFFICIENT_FUNDS_AFTER_FEES` / `READING_UNAVAILABLE` | motifs du planner | détail par actif |
| Kraken refuse la paire (conformité) | « Paire refusée par Kraken pour ce compte (code …) » | marquer la paire « non disponible compte » (Q8) |

## 5. Architecture

### 5.1 Découpage
- `AdminExecutionController` (`/api/admin/execution/**`) : **ajouter** des contrôleurs fins par écran plutôt que gonfler `ExecutionAdminController` (déjà 6 endpoints) : `AdminCredentialController`, `AdminBindingController`, `AdminOrderTesterController`, `AdminPlanController`, `AdminCatalogController`. Les deux contrôleurs existants restent inchangés (`run`, `control`, `kill-switch`, `arm`, `plan`).
- Tous : `@PreAuthorize("hasRole('ADMIN')")` classe, DTO dédiés (records dans `model/dto/admin/**`), jamais d'entité JPA ; services admin dédiés dans `service/admin/**` (pas de logique dans les contrôleurs).
- Journal : nouvelle entité append-only `admin_action_log` (`id, actor, action, target, payload JSON sans secret, at`), écrite via `DomainClock`, par **toutes** les actions mutantes (kill switch, arm, création/rotation/désactivation de credential, plan, run, reconcile, test d'ordre, envoi). Lecture dans l'onglet Plans & audit.
- Front : `adminPage.html` + `fragments/admin/{console,credentials,bindings,orderTester,plans,catalog}.html` (un fragment par onglet) + `static/assets/js/admin/{admin-core,console,credentials,…}.js` (un fichier par onglet, `admin-core.js` = `el()`, `api()` calqués sur `rainbow-live.js`, en-tête `X-Requested-With`). Aucune charte : classes Bootstrap déjà chargées.

### 5.2 Testeur et `ExecutionOrderArchitectureTest`
Le test impose que seuls `service.execution.exchange` et `service.execution.run` référencent le package d'ordres. Le testeur ne doit **pas** appeler `SpotOrderPort`. Proposition :
- Interface séparée **`SpotOrderValidator`** (`validate(credential, OrderRequest)` ⇒ `ValidationReport`), dans `service.execution.exchange`, **sans `submit`** ; implémentée par `KrakenOrderClient` (`validate=true`) et, pour OKX, par un contrôle local pur.
- Façade **`OrderTestService`** dans `service.execution.run` (seul appelant de la validation *et* de l'envoi minimal), appelée par `AdminOrderTesterController`. L'esprit du test est conservé : contrôleurs → `run` → `exchange`.
- `SpotOrderPort` devient **routé par provider** (`SpotOrderGateway`/`Map<WebProviderCode,SpotOrderPort>` dans `run`) ; bean par provider conditionné par `LiveExecutionCondition` (envoi) ; `SpotOrderValidator` disponible hors LIVE (pas d'ordre), mais seulement avec clé TRADE + `TRADEIO_MASTER_KEY` (Q4).
- Le test d'architecture gagne un cas : `SpotOrderValidator` n'expose aucune méthode `submit/place/new/create/cancel`.

### 5.3 Anti-XSS / contrôle d'accès
`textContent` uniquement ; test statique « aucun `innerHTML`/`insertAdjacentHTML`/`outerHTML`/`document.write` dans `static/assets/js/admin/**` et `fragments/admin/**` » (patron des tests actuels) ; pas de `th:utext` ; réponses brutes d'exchange insérées en texte dans `<pre>` ; bornées en taille ; JSON d'exchange parsé côté serveur et filtré (liste blanche de champs pour la vue « lisible », brut tronqué pour la vue « brute »). CSP restrictive sur `/admin` (script-src 'self') — impossible tant que jQuery vient d'un CDN → assets locaux. `@PreAuthorize` + test de sécurité par méthode (patron `ExecutionAdminSecurityTest`) pour chaque nouveau contrôleur ; CSRF : voir §2.3.

### 5.4 Tests
- API : un `*SecurityTest` par contrôleur (USER ⇒ 403, anonyme ⇒ refus, ADMIN ⇒ OK) ; DTO sans secret (réflexion : aucun champ nommé `secret|passphrase|apiKey` en lecture) ; `toString` masqué.
- Rendu : `MainControllerAdminPageTest` (onglets présents, scripts attendus) ; absence d'`innerHTML`.
- Services : `AdminActionLog` append-only (aucune méthode update/delete), routage par provider, validate sans port d'envoi, mapping d'erreurs Kraken (fixtures JSON, serveur HTTP local comme `OkxSpotOrderClientTest`).
- Architecture : `ExecutionOrderArchitectureTest` (esprit inchangé + règle validateur), `ExecutionPlanArchitectureTest`, `ConnectorNoOrderMethodTest`, `RainbowLiveNoExchangeDependencyTest` inchangés.

### 5.5 Impact documentation (à faire dans chaque lot, sans historique)
`api/rest-endpoints.md` (nouveaux endpoints admin, DTO), `architecture/10-execution-reelle.md` (routage provider, validateur, page admin, mapping `clOrdId` Kraken), `architecture/07-security.md` (CSRF/SameSite, journal admin, credentials gérées par l'UI, provider TRADE élargi), `architecture/10-execution-plan.md` (si routes liées), `known-gaps/rainbow-dca-chantiers-ouverts.md` (§5b : page de saisie des credentials passe de « non fait » à « fait » ; retirer ce qui devient faux), `glossary.md` (validate, route liée).

## 6. Pilotage Kraken

### 6.1 Ce que l'UI doit montrer
- **Clé Trade** : séparée de la Read (user, provider KRAKEN, scope TRADE), créée en UI ; droits à cocher côté Kraken : *Query Funds*, *Create & modify orders* (+ *Cancel/close orders* si on veut l'annulation), *Query open/closed orders & trades* (pour la réconciliation) ; **jamais Withdraw / Deposit** ; restriction IP = IP du VPS (affichée sur la page pour copier). Exact set de droits **à reconfirmer** (doc Kraken).
- Résultat `validate` par paire (OK / erreur Kraken brute), dernière validation datée.
- Par paire : `ordermin`, `costmin`, `tick_size`, `lot_decimals`, devise de frais (`fee_volume_currency`), `fees` palier ; frais réels du compte via `TradeVolume` (affiché en %).
- Réconciliation : `clOrdId` interne ↔ identifiant Kraken (txid) ↔ statut ; bouton « Interroger l'ordre » (lecture) ; devise des frais par fill.

### 6.2 Préalables back (dans l'ordre)
1. **Routage `SpotOrderPort` par provider** (aujourd'hui singleton `ObjectProvider<SpotOrderPort>` ⇒ ajout d'un 2ᵉ bean ambigu).
2. `KrakenOrderClient` : `AddOrder` (`ordertype=limit`, `timeinforce=IOC`, `volume` base, `price` plafond, `validate`, identifiant client), `QueryOrders`, `CancelOrder`, fills via `QueryTrades`/`TradesHistory` ; mapping erreurs : `EOrder:*` ⇒ `OrderRejectedException`, `EAPI:Invalid key|Permission denied` ⇒ `TradeCredentialRejectedException`, réseau/`EService:*`/timeout ⇒ `ExchangeUnavailableException` (issue inconnue ⇒ `UNKNOWN`, jamais de renvoi).
3. **Signeur HMAC-SHA512 + nonce monotone par clé** : extraire la signature de `KrakenApiClient.privatePost` en composant partagé (`KrakenSignedRequests`, comme `OkxSignedRequests`) avec `AtomicLong` par clé (`max(now_ms, last+1)`), idéalement nonce en µs ; clé Trade ≠ clé Read (nonces indépendants).
4. `ApiCredentialInitializer` : `tradeio.kraken.trade.apiKey|secretKey` (chiffré par `TradeCredentialService`, comme OKX) ; `TradeCredentialService.TRADABLE_PROVIDERS` += `KRAKEN` (passphrase **non requise** : la validation « apiKey/secret/passphrase requis » devient par provider).
5. Endpoint de création de credential non limité à OKX (§3.2).
6. Catalogue : `costmin`, devise de frais, filtrage par compte si disponible (colonnes `ExchangeInstrument` + Flyway/initializers selon `operations/flyway.md`).
7. `SpotOrderValidator` (§5.2) puis ordre minimal.

## 7. Feuille de route par lots (détail : `docs/prompts/prompt-roadmap-ux-admin-execution.md`, 4 lots condensés)

Tableau d'origine en 7 lots, regroupés en 4 : (1+2) page Admin ; (3+4) Kraken + testeur ; (5+6) smoke test + Plans & audit ; (7) routes PAXG.

| Lot | Contenu | Critères d'acceptation |
|---|---|---|
| 1 | Socle `/admin` (onglets, `admin-core.js`, assets locaux) + Console (état, plan, arm, lever/réengager, run, reconcile, plans admin minimal) + `admin_action_log` | procédure OKX complète sans curl ; chaque action écrite au journal ; sécurité par méthode testée ; aucun `innerHTML` ; kill switch réengagé automatiquement selon Q5 |
| 2 | Credentials (liste masquée, rotation, test, désactivation, création non-OKX) + Bindings (liste admin, formulaire) | plus aucune saisie par properties/SQL ; aucun secret en réponse (test réflexion) ; `TRADEIO_MASTER_KEY` absente ⇒ message clair |
| 3 *(parallèle, prompt séparé)* | Client Kraken + routage provider + signeur/nonce + `tradeio.kraken.trade.*` + catalogue `costmin` | `FakeSpotExchange` + serveur HTTP local verts ; nonce monotone testé en concurrence ; `ExecutionOrderArchitectureTest` vert |
| 4 | Testeur d'ordre : `validate` Kraken, calcul local OKX (mêmes verrous, aucun envoi) | `BTC-USDC`/`ETH-USDC` validés ; erreurs Kraken lisibles ; aucun envoi possible depuis cet écran |
| 5 | **Smoke test réel** (plan `SMOKE_TEST`, entité d'état, classification `FAILED_KEY`/`FAILED_ORDER`, bouton par route/wallet/user) — d'abord OKX `BTC-USDC` puis Kraken | ordre minimal réconcilié à l'unité avec l'historique exchange ; relance idempotente ; bouton masqué après `PASSED` ; refus de conformité mémorisé sur la route sans invalider la clé |
| 6 | Plans & audit (multi-user, filtres, `UNKNOWN`, journal admin, infos API en historique) | tout le déroulé du lot 5 lisible dans l'UI |
| 7 | `PathFinder` à intermédiaires crypto + Catalogue & routes + route liée au binding (PAXG) + smoke test par route | PAXG/Kraken : `USDC→ETH→PAXG` chiffrée, route fiat affichée non sélectionnable ; route `FAILED_ORDER` écartée |

Remarque lot 7 : `PathFinder` suppose aujourd'hui que les nœuds intermédiaires sont des stables (chaque jambe valorisée au même montant nominal USD) ; avec ETH/BTC en intermédiaire il faut convertir le montant par jambe et inclure le risque de prix entre les deux ordres (ordres séquentiels non atomiques) — c'est un sujet d'analyse à part.

## 8. Décisions de Clem (2026-10-11, après analyse)
- Credentials/Bindings : **le propriétaire saisit** ses clés, l'admin supervise (liste, désactivation, armement) — Q2 tranchée.
- Kill switch : **réengagement automatique côté back** après `run`/`reconcile` — Q5 tranchée.
- Testeur d'ordre : **`validate` seul** ; tout ordre réel passe par plan + Console — Q6 tranchée (le lot 5 devient « ordre réel Kraken via plan », sans bouton d'envoi dans le testeur).
- Sécurité des endpoints (CSRF, inscription publique…) : **reportée à la phase d'audit** ([`../securite/remarques-audit.md`](../securite/remarques-audit.md)) ; aucun durcissement dans les lots.

- Admin = **tout compte du groupe Admin** (`ROLE_ADMIN`) ; durcissement (rôle dédié, séparation des pouvoirs) renvoyé à un point sécurité ultérieur.
- **Smoke test réel** (remplace « valider une clé par lecture ») : achat réel du **montant minimal** pour confirmer un binding/une route. Aucun contournement des garde-fous : mêmes verrous qu'un ordre normal (LIVE, kill switch, double validation du 1er ordre, plafonds, `ExecutionGuard`). Bouton présent sur chaque route d'actif, wallet et user ; grisé/masqué dès que le test est concluant (§3.7).
- `validate` (Kraken `AddOrder validate=true`) reste le dry-run du testeur, **soumis aux mêmes verrous** (pas de bypass). OKX : calcul local seul (aucun `validate` natif ; le mode démo OKX exigerait des clés démo distinctes — écarté).
- Infos importantes de l'API (réponse filtrée, codes, frais, devise de frais, identifiants d'ordre) : **stockées dans l'historique** (`rainbow_live_order_event` + étapes/`Transaction`), pas dans un affichage éphémère.
- Refus d'une paire/route : **mémorisé** sur la route (indisponible), en **distinguant** problème de droits de clé et refus de transaction par l'API (§3.7).
- Page : on reste sur `/admin` (onglets).
- Audit : journaliser les **actions mutantes** et les smoke tests, pas les lectures (défaut recommandé, non contesté).

- **Smoke test non obligatoire**, mais **zéro passe-droit en test** : tests et smoke tests se déroulent comme en prod ; un verrou non levé (kill switch, LIVE, armement, double validation, plafond…) ⇒ résultat `FAILED_GUARD` (nouvelle classe : ni la clé ni la route ne sont marquées, le verrou concerné est affiché). Aucun mode « test » n'assouplit une règle.
- Section **sécurité** ouverte : [`../securite/remarques-audit.md`](../securite/remarques-audit.md) (auth, journalisation Admin/User) ; la page Admin d'exécution y renvoie pour tout ce qui relève de l'audit.

## 9. Questions encore ouvertes
Aucune bloquante. Point sécurité (rôles admin) planifié plus tard.
