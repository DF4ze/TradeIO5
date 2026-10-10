# Exécution réelle des ordres (lots C1 back-end et C2 page)

Vérifié le : 2026-10-10 (`rainbow-live.js`, `service/execution/{exchange,run,credential}/**`, `service/execution/ExecutionControlService`, `model/entity/execution/**`, `controller/ExecutionAdminController`, `service/scheduler/ExecutionJob`).

**Aucun ordre réel n'a été envoyé à ce jour : `LIVE` reste verrouillé.** Le code existe, il est testé sur un exchange simulé (`FakeSpotExchange`, `src/test`) et sur un serveur HTTP local pour le client OKX. Le déverrouillage est l'étape d (avec Clem). Plan d'ordres en amont : [`10-execution-plan.md`](10-execution-plan.md).

## Verrous (tous requis pour qu'un ordre parte)

1. `tradeio.execution.mode=LIVE` **et** `tradeio.execution.live-unlocked=true` (défaut `false`) **et** variable d'environnement `TRADEIO_MASTER_KEY` présente : sinon `ExecutionSettings` refuse `LIVE` au démarrage (`IllegalStateException`). `OFF` / `DRY_RUN` ⇒ l'exécuteur n'appelle jamais le port.
2. Bean `OkxSpotOrderClient` (seul implémenteur de `SpotOrderPort`) conditionné par `LiveExecutionCondition` (mode `LIVE` **et** déverrouillé) : absent sinon (`ObjectProvider<SpotOrderPort>` vide ⇒ `PORT_UNAVAILABLE`).
3. **Kill switch** (`execution_control`, ligne unique, **engagé par défaut**, ligne absente ⇒ engagé) relu avant chaque étape. `PUT /api/admin/execution/kill-switch`.
4. Binding : `executionEnabled` (propriétaire) **et** double validation du 1ᵉʳ ordre (`firstLiveApprovedAt` armé par l'admin, puis `firstLiveConfirmedAt` par le propriétaire ; réarmer remet la confirmation à zéro).
5. `ExecutionGuard.requireExecutable(binding, pathComputed)` : `OK`, ou `TRADABLE_VIA_BRIDGE` si un chemin est chiffré ; `NOT_TRADABLE_WITHOUT_FIAT` toujours refusé.
6. Credential **TRADE** résolue et déchiffrée.
7. Plafonds : par ordre (`maxOrderMultiplier` × `baseAmount` du run, défaut 1), par jour et par user (`maxDayMultiplier` × somme des `baseAmount`, défaut 2), bornés par des **constantes absolues** non configurables (`ExecutionDefaults.ABSOLUTE_MAX_ORDER_USD` = 500, `ABSOLUTE_MAX_DAY_USD` = 1000). Le jour compte les étapes principales déjà envoyées (hors ponts).

## Credentials TRADE

- `ApiCredential.scope` (`READ|TRADE`, défaut `READ`), unique (user, provider, scope). Toutes les lectures existantes passent par `findRead…` (la clé TRADE n'est jamais lue par les connecteurs de lecture).
- `SecretCipher` : AES-256-GCM, IV aléatoire 12 octets, format `enc:v1:base64(iv‖chiffré+tag)`, AAD = nom du champ ; clé maître = env `TRADEIO_MASTER_KEY` (base64 de 32 octets), jamais en base. Clé invalide ⇒ échec au démarrage ; absente ⇒ chiffrement / déchiffrement impossibles, **aucun repli en clair**.
- `TradeCredentialResolver` : seul lecteur ; renvoie une **copie détachée** déchiffrée (l'entité gérée reste chiffrée) ou `Optional.empty()` (absente, désactivée, clé manquante, valeur non chiffrée ou altérée).
- Création / rotation : `POST /api/admin/execution/credentials` (ADMIN, OKX seulement), réponse sans secret, `toString` du corps masqué.

## Port d'ordres (`service.execution.exchange`)

`SpotOrderPort` : `submit`, `query`, `cancel`, `fills`. `OrderRequest` n'accepte que `LIMIT_IOC` (prix plafond obligatoire). `OkxSpotOrderClient` : `POST /api/v5/trade/order` (`tdMode=cash`, `ordType=ioc`, `sz` en base via `tgtCcy=base_ccy`, `px`, `clOrdId`), `GET /api/v5/trade/order`, `POST /api/v5/trade/cancel-order`, `GET /api/v5/trade/fills`, signature HMAC-SHA256 via `OkxSignedRequests`, espacement minimal entre appels. Erreurs : rejet métier ⇒ `OrderRejectedException(code)` ; clé refusée (`501xx`) ⇒ `TradeCredentialRejectedException` ; réponse illisible / HTTP / codes système incertains ⇒ `ExchangeUnavailableException` (issue **inconnue**, l'ordre a pu partir) ; `51603` ⇒ `OrderNotFoundException`. Frais OKX négatifs normalisés en coût positif.

## Exécution (`service.execution.run`)

- `OrderExecutor` (seul appelant du port avec `StepRunner`) : `executeAll(jour, passe)` (job) ; `execute(planId)` (endpoint) ; `reconcile(planId)` (aussi au démarrage via `ExecutionStartupReconciler` en LIVE). Un plan `EXECUTING` n'est jamais renvoyé, seulement réconcilié. Prise du plan par un `UPDATE` conditionnel (`PLANNED → EXECUTING`).
- Par étape : relecture du kill switch ; **re-devis** (carnet frais, `StepRequoter`) puis arrêt si niveau Fee Test `RED` ou coût projeté > coût du plan + 0,05 point ; écriture `SUBMITTED` **avant** l'envoi ; attente bornée de l'état terminal ; lecture des fills.
- Chaîne pont : l'étape principale est dimensionnée sur ce que le pont a réellement reçu (net de frais), arrondi au `lotSz`.
- États d'étape : `PLANNED → SUBMITTED → FILLED|PARTIAL|REJECTED|CANCELED|UNKNOWN`. Timeout / sortie illisible ⇒ `UNKNOWN`, **jamais de renvoi** : réconciliation par `clOrdId` (introuvable ⇒ `CANCELED`). Doublon de `clOrdId` ⇒ relecture. Échec partiel : le stock reste, aucun ordre compensatoire.
- États de plan : `PLANNED → EXECUTING → EXECUTED|PARTIAL|FAILED` ; `BLOCKED` si une raison permanente (`ExecutionBlockReason.permanent`) ; sinon retour à `PLANNED`. `executionBlockReason` = `ACTIF:RAISON;…`.
- Fills ⇒ `Transaction` (`FillRecorder`, idempotent sur `<PROVIDER>:<tradeId>`, `feeCurrency`). Quantité nette reçue = remplie − frais si prélevés dans l'actif reçu ; slippage réel = prix moyen vs mid du re-devis.
- Audit : `rainbow_live_order_event`, **append-only** (aucun `update`/`delete` exposé, écriture en transaction indépendante), types `PLAN_STARTED, STEP_REQUOTED, STEP_SUBMITTING, STEP_ACK, STEP_RESULT, STEP_UNKNOWN, STEP_RECONCILED, STEP_SKIPPED, PARTIAL_REMAINDER, BLOCKED, ALERT, PLAN_RESULT`, charge utile JSON sans secret.

## Page (lot C2, `rainbow-live.js`, `textContent` uniquement)

Sous le plan de l'actif sélectionné (panneau live) :
- plan : badge de statut (dont `EXECUTING/EXECUTED/PARTIAL/FAILED`), « ordres envoyés » ou « non envoyé », blocages (`executionBlockReason`) ; par étape : statut, quantité remplie, prix moyen, frais réels (+ devise), slippage réel ; badge Fee Test inchangé ;
- bloc « Exécution réelle » : badges kill switch (engagé / levé, affichage seul) et mode (`GET /api/rainbow-live/execution-state`), interrupteur « Exécution activée » (`PUT /bindings/{id}/execution`), état du 1er ordre réel (en attente d'armement / bouton « Confirmer le 1er ordre réel » une fois armé / validé) ;
- « Audit du plan » : liste paginée en lecture seule (`GET …/execution-plans/{id}/events`), aucune action ;
- le bandeau dit « exécution réelle verrouillée » tant que `liveExecution` est faux.
Tests de rendu : `MainControllerUserPageTest` ; audit : `OrderPlanServiceTest` (propriétaire seul, pagination bornée).

## Déclenchement

`ExecutionJob` (`tradeio.execution.run-cron`, défaut `-`, `zone = "UTC"`) et `POST /api/admin/execution/run?planId=`. Endpoints : [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

## Garde-fous (tests)

`ExecutionOrderArchitectureTest` (seul `service.execution.exchange` déclare des méthodes d'ordre ; seuls `exchange` et `run` référencent ce package — analyse du pool de constantes, donc ni bench, ni plan, ni contrôleurs de lecture ; bean client absent par défaut ; `LIVE` sans déverrouillage / clé maître refusé), `ExecutionPlanArchitectureTest`, `ConnectorNoOrderMethodTest` et `RainbowLiveNoExchangeDependencyTest` inchangés, `OrderExecutorTest`, `StepRunnerTest`, `OkxSpotOrderClientTest`, `SecretCipherTest`, `TradeCredentialPersistenceTest`, `ExecutionAdminSecurityTest`, `RainbowLiveBindingExecutionTest`.

## Hors périmètre (volontairement absent)

Token natif de l'exchange, découverte de wallet, alerte % de liquidité, compte Funding OKX, retrait / transfert (**jamais**), `post_only`, ordre inverse USDT→USDC.
