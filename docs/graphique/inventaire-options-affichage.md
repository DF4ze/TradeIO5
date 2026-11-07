# Inventaire GUI — actions utilisateur et informations affichées

Vérifié le : 2026-11-06 (sources : [`../api/rest-endpoints.md`](../api/rest-endpoints.md), [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md), [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md), [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md), [`../architecture/09-trend.md`](../architecture/09-trend.md), [`../etudes/etude-architecture-interface.md`](../etudes/etude-architecture-interface.md)).

But : liste unique de **ce sur quoi l'utilisateur peut agir** et de **ce que le back remonte à l'affichage**, pour travailler les maquettes sans rouvrir les autres docs. Les docs sources font foi ; en cas de doute, y retourner. Toute évolution des endpoints, DTO ou réglages doit être répercutée ici (et un élément implémenté passe de « à implémenter » à disponible).

Légende : **[OK]** disponible côté back (API) · **[GUI]** déjà un écran dans `/app` · **[Prévu]** décrit dans l'étude d'interface, écran non construit (back disponible) · **[À implémenter]** absent du back (aucun endpoint ni modèle) : à construire pour que la maquette soit réalisable. Récapitulatif : §6.

Vocabulaire : « actif » (utilisateur) = **lien/binding** (technique : actif × wallet) ; « preset » = jeu de paramètres (Trend ou Rainbow) ; « Affectation » = par actif, un preset Trend + un Rainbow UP + un Rainbow DOWN. Actifs gérés : **BTC, ETH, PAXG**. Jour = jour UTC ; une passe quotidienne à 23:55 UTC (signal), plan à 23:58.

---

## 1. Actions utilisateur

Tout est scopé à l'utilisateur connecté (JWT cookie) ; ressource d'un autre user ou inexistante ⇒ 404 ; erreurs `{error}` (400 validation, 404, 409 conflit). 401/403 ⇒ redirection `/login`.

### 1.1 Compte

| Action | Endpoint | Remarques |
|---|---|---|
| Connexion / déconnexion / inscription | `POST /api/auth/signinForm`, `GET /api/auth/signoutForm`, `POST /api/auth/signupForm` | formulaires uniquement ; rôles USER / MODERATOR / ADMIN ; connexion d'un compte archivé ⇒ restauré |
| Curseur de risque (0–10) | `GET/PUT /api/user/risk-cursor` | continu ; **non branché** à la modulation Rainbow |
| Saisir sa clé d'échange de lecture (soldes) | `POST /api/credentials/read` | `{provider OKX\|KRAKEN, apiKey, secretKey, passphrase (OKX), enabled?}` ; chiffrée, réponse sans secret ; crée ou rattache le wallet ; 409 sans clé maître serveur ; droit requis : lecture seule |
| Saisir sa clé d'échange TRADE | `POST /api/credentials/trade` | `{provider OKX\|KRAKEN, apiKey, secretKey, passphrase (OKX), enabled?}` ; réponse sans secret ; 409 sans clé maître serveur ; droits requis : créer/annuler des ordres, jamais retrait, restriction IP |
| Clés de lecture (maquette Réglages « clés lecture seule ») | — | **[À implémenter]** côté utilisateur |
| Plafonds utilisateur, alertes (maquette Réglages) | — | **[À implémenter]** |

### 1.2 Onboarding

| Action | Endpoint | Paramètres / effet |
|---|---|---|
| Choisir le wallet | `GET /api/rainbow-live/wallets` | `[{id, name, exchange}]` |
| Lier ses actifs | `POST /api/rainbow-live/onboarding/link` `{walletId}` | un lien **inactif** par actif sur l'Affectation de l'actif ; résultat par actif : `LINKED \| ALREADY_LINKED \| NOT_LINKED` + statut/raison ; rejouable |
| Étaler le cash sur N jours/mois | `GET /api/rainbow-live/onboarding/proposals?walletId=&days=` | `days` ∈ bornes admin (7–365, défaut `autonomyDays` = 90) ; lecture seule |
| Appliquer les montants proposés | `PUT /bindings/{id}` `{baseAmount}` | déclenchement **manuel** (rien ne se lie tout seul) |

[GUI] page **Ajouter un actif** (`/app/onboarding`) : actif → comparateur de frais de tous les exchanges (`GET /onboarding/path-quotes`) → montant de base par jour ; pas de choix de profil (le profil déjà paramétré s'applique, réglable dans Réglages). Page **Exchanges** (`/app/exchanges`) : clés lecture / écriture par exchange (`GET /api/exchanges`, tests et suppressions : cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md)). Les actions ci-dessus (lier, proposer des montants) restent des endpoints sans écran dédié.

### 1.3 Liens (bindings) = « mes actifs »

Unique par (user, actif, wallet). `GET /api/rainbow-live/bindings`, `POST` (création), `GET/PUT/DELETE /{id}`. Création [GUI] seulement dans l'onglet Bindings de l'admin ; onboarding et « + Ajouter un actif » côté utilisateur = [Prévu].

| Réglage | Champ | Contraintes / effet |
|---|---|---|
| Wallet | `walletId` | du user ; clé unique (actif, wallet) ⇒ 409 si déjà lié |
| Montant de base du lien | `baseAmount` (> 0, fini) ; `resetBaseAmount=true` | vide = montant du preset Rainbow joué ; ne met à l'échelle que les **achats** ; modifier le montant remet le **carry à zéro** (le dire à l'utilisateur) |
| Achat minimum | `PUT /{id}` `minBuyUsd` (`resetMinBuy` pour retirer) | sous ce montant l'achat est reporté en carry |
| Part de la position offerte | `bagPercent` 0–100 (défaut 100) | dynamique : % de la position réelle que la stratégie peut vendre |
| Priorité de passage du cash | `priority` | défaut = rang d'actif (BTC 0, ETH 1, PAXG 2) ; ordre de consommation du cash commun |
| Mode d'exécution | `PUT /{id}/execution` `{mode DISABLED\|SIMULATION\|ACTIVE}` | défaut **Désactivé** ; Simulation = chaîne complète jouée sans ordre (tag « Simulation » Journal / Actifs gérés) ; Actif n'envoie rien en soi ; la 1ʳᵉ activation arme automatiquement le 1ᵉʳ ordre réel. L'écran affiche d'abord exchange, chemin d'achat et Fee Test, puis une seule validation (activation + confirmation) |
| Confirmer le 1ᵉʳ ordre réel | `POST /{id}/confirm-first-live` | possible dès que l'exécution du lien est activée (sinon 409) ; enchaînée par l'écran avec l'activation |
| Vérifier la disponibilité | `GET /{id}/check` | appelle l'exchange (lecture seule), persiste le statut |
| Comparer les routes d'achat | `GET /{id}/path-quote?amount=` | lecture seule, `amount` > 0 USD |
| Supprimer le lien | `DELETE /{id}` | 204, l'Affectation reste |

Après création/modification, la réponse porte `{binding, check}` : un check KO n'empêche pas l'enregistrement.

### 1.4 Affectations

`GET /api/rainbow-live/affectations[?asset=]` (crée au passage les Affectations manquantes, inactives, sur les presets système de base), `GET /affectations/{id}`, `PATCH /affectations/{id}/enabled`.

| Action | Détail |
|---|---|
| Activer / désactiver | seule une Affectation **active** est jouée par la passe (bascule obligatoire par l'utilisateur) |
| Presets installés | Trend, Rainbow UP, Rainbow DOWN affichés avec leur origine (système / propre) ; leur édition relève de l'Espace Expert |

### 1.5 Modulation (curseurs)

`GET/PUT /api/user/modulation-settings` — corps complet obligatoire `{exposureMin, exposureMax, power, bias}` (+ `regulationMarginPct`) ; réponse `{settings, limits, profile}` : une modification réelle bascule le profil sur `CUSTOM`. `GET /api/user/modulation-settings/history` ⇒ `[{at, source PROFILE|MANUAL, profileBefore, profileAfter, before, after}]` (50 derniers). Neutre = 0 / 100 / 1 / 0. Pris en compte à la **passe suivante** (valeurs clampées aux bornes admin à ce moment-là).

| Curseur | Contrainte (limites admin lues dans `limits`) |
|---|---|
| Bande d'exposition mini / maxi (% de la valeur du wallet) | `0 ≤ min < max ≤ 100`, largeur ≥ `minBandWidthPts` (défaut 10, 5–50) ; trop étroite ⇒ élargie autour du centre (message à afficher) |
| Puissance | dans `[powerMin, powerMax]` (défaut 0,25–2) |
| Biais achat/vente | `\|biais\| ≤ biasMax` (défaut 0,5) |
| « Neutre » | remet 0/100/1/0 |

Effet : le Rainbow reste seul à acheter/vendre ; la modulation ne fait que changer les **quantités** (aucun ordre de rééquilibrage). [GUI] bloc « Modulation » (jauge 0–100 %, marges de régulation, repère d'exposition, curseurs).

### 1.6 Carry (mise sous le minimum du provider)

Aucune action dédiée : l'utilisateur **augmente sa mise** (`baseAmount` du lien) ou laisse le report s'accumuler. Option de carry toujours visible [Prévu] ; carry sur `INSUFFICIENT_FUNDS` = idée, non retenue.

### 1.7 Ce que l'utilisateur ne peut PAS faire

Lancer une passe ou un plan, lever le kill switch, armer le 1ᵉʳ ordre réel, envoyer/annuler un ordre, régler `asset_provider`, modifier les bornes de modulation, retirer/transférer des fonds (jamais).

---

## 2. Informations remontées à l'affichage

Principe : l'affichage ne lit que la **base** (aucun appel exchange), sauf `check` et `path-quote` (déclenchés par l'utilisateur). Nombre manquant/NaN ⇒ « — ». Le soir (23:55) le signal est calculé sur la bougie D1 **encore ouverte** (valeurs provisoires), rafraîchi le lendemain sur la bougie clôturée ; l'action décidée reste figée.

### 2.1 Mode d'exécution (bandeau) — `GET /api/rainbow-live/execution-state`

`{mode OFF|DRY_RUN|LIVE, liveExecution (LIVE déverrouillé), killSwitch}`. Affichage seul. Bandeau « Simulation / exécution réelle verrouillée » tant que `liveExecution` est faux ; état d'alerte global.

### 2.2 Affectations — `AffectationDto`

`id, assetSymbol, name, enabled, complete, analysisWindowMonths, createdAt, updatedAt, trendPreset{id, name, origin}, rainbowUp{…}, rainbowDown{…}, tuning, globals, trendConfig?, runCount, firstRunDay?, lastRun{day, close, zone, actionType, actionAmountUsdc, actionQuantity, activeSet?, trendRegime?}`.
Badges : LIVE si liée à ≥ 1 lien ; colonne « Jeu · Trend » (preset Rainbow actif + régime UP/DOWN/RANGE du dernier run). Libellés de zones et énums via `GET /defaults` (`zones[{code,name,label}]`, `reentryModes`, `rangeMappings`).

Historique : `GET /preset-events[?asset=]` → `{type ENABLE|DISABLE|LIVE_SWITCH, presetBefore/After (id, nom), occurredAt, reason?}`.

Données du signal calculées en base mais **non exposées** par l'API (bloc `RainbowLivePassBlock`) : SMA, ATR, 5 bornes, distance ATH, facteurs achat/vente, armements/verrous/cooldown, réserve moon. Nécessaires pour tracer les **bandes Rainbow** du graphique : **[À implémenter]** (endpoint d'historique des runs).

### 2.3 Liens — `BindingDto`

`id, assetSymbol, affectationId, affectationName, walletId, walletName, exchange, bagPercent, priority, baseAmount?, tradability?, quoteMember?, routeKey?, minBuyUsd?, executionEnabled, executionMode, firstLiveArmed, firstLiveConfirmed`.
`tradability` = dernier `BindingCheck` : `OK, TRADABLE_VIA_INTERMEDIARY, NOT_TRADABLE_WITHOUT_FIAT, WALLET_DISABLED, CREDENTIAL_INVALID, PROVIDER_UNSUPPORTED, BALANCE_UNAVAILABLE, INSTRUMENT_UNAVAILABLE` (+ `message`, `ok`, `executable`, `blocked`). `TRADABLE_VIA_INTERMEDIARY` = route à deux jambes (ex. USDC → BTC → PAXG) : bandeau d'information.
État du 1ᵉʳ ordre réel : à confirmer (exécution activée) → validé.

### 2.4 Wallet réel et décision du jour — `GET /api/rainbow-live/live-wallet`

Une entrée par lien (`LiveAssetDto`) : champs du lien + `tradabilityMessage?`, `transit[{currency, quantity, finishSide BUY|SELL, createdAt}]` (stock intermédiaire en attente de la jambe 2) et `snapshot` (nul tant qu'aucune passe live) :

| Champ | Sens |
|---|---|
| `day`, `fetchedAt` | jour et heure de lecture des soldes |
| `status` | `OK \| UNAVAILABLE \| NOT_TRADABLE` |
| `cashUsd`, `cashByMember{USDC, USDT}` | cash du groupe USD (USDC) et détail |
| `positionQty`, `tradableQty` | position réelle ; part offerte (`bagPercent`) |
| `cashReserved` | cash déjà consommé par les actifs prioritaires |
| `blockReason` | `NONE, INSUFFICIENT_CASH, UNAVAILABLE, NOT_TRADABLE_WITHOUT_FIAT` (+ `BAND_LIMIT` quand la modulation ramène l'action à 0) |
| `liveActionType` | `BUY \| SELL \| NONE \| BLOCKED` |
| `liveActionAmountUsdc`, `liveActionQuantity` | action recommandée, **non exécutée** par la passe |
| `modulation` | journal JSON (cf. §2.5) |

Rendu : encart « Achat bloqué » (BLOCKED), avertissement « non tradable sans monnaie fiat », « Données indisponibles, aucune action » (UNAVAILABLE).

### 2.5 « Pourquoi ce montant ? » — `snapshot.modulation`

Montants du signal, puissance, biais, facteurs de côté (achat/vente), bande, exposition avant/après (`exposureStatus = UNKNOWN` si prix manquant ⇒ pas de modulation de bande), `l` haute/basse, facteurs de bande et produit, plafonds atteints, paramètres admin utilisés, montant final. Chaîne : montant du signal × puissance × (1 ± biais) × facteur de bande → plafond de produit → plafond dur d'exposition → cash/position.
Exposition affichée dans la jauge = `exposureAfter` (sinon `exposureBefore`) du dernier journal des liens ; inconnue sinon.

### 2.6 Plan d'ordres — `GET /api/rainbow-live/execution-plans[?from=&to=]` et `/latest`

`PlanDto` : `id, day, revision, status, mode, totalCostPct, feeTestLevel, blockReason?, createdAt, expiresAt (5 min)`, + par actif `assets[{assetSymbol, action, outcome OK|BLOCKED|DISABLED|NO_ACTION, blockReason?, costPct, feeTestLevel, warning?, walletId, carryInUsd, carryOutUsd, steps[{rank, instId, side, ordType, sz, px, quoteAmount, clOrdId, feePct, spreadPct, slippagePct, estimation BOOK|TICKER}]}]`.
`/latest` ajoute après exécution : `executionBlockReason?` (`ACTIF:RAISON;…`), `executionStartedAt?`, `executionFinishedAt?`, et par étape `status, filledSz?, avgFillPx?, feeAmount?, feeCurrency?, receivedAmount?, receivedCurrency?, realSlippagePct?, lastError?`.
Chaque actif d'un plan porte `simulated` (lien en simulation : planifié, jamais exécuté). Statuts de plan : `PLANNED, BLOCKED, EXPIRED, DISABLED, EXECUTING, EXECUTED, PARTIAL, FAILED, CANCELLED` ; d'étape : `PLANNED, SUBMITTED, FILLED, PARTIAL, REJECTED, CANCELED, UNKNOWN`. Raisons de blocage de plan : `NO_PATH, BELOW_MIN, INSUFFICIENT_FUNDS_AFTER_FEES, NOT_TRADABLE_WITHOUT_FIAT, READING_UNAVAILABLE`. Raisons d'exécution : `COST_RED, COST_DRIFT, BELOW_MIN, REQUOTE_UNAVAILABLE, NO_BASE_AMOUNT, MODE_NOT_LIVE, KILL_SWITCH, PORT_UNAVAILABLE`…
Fee Test (coût total, somme brute des jambes) : `GREEN` < 0,2 % ≤ `WARNING` ≤ 0,8 % < `RED` (en %, jamais en bps). Mention « non envoyé » en DRY_RUN. Carry : `carryInUsd` / `carryOutUsd` par actif (badge « Reporté » + somme).

Audit : `GET /execution-plans/{id}/events?page=&size=` (≤ 100) → `{page, size, totalElements, items[{id, stepId?, clOrdId?, type, payload, createdAt}]}` ; types `PLAN_STARTED, STEP_REQUOTED, STEP_SUBMITTING, STEP_ACK, STEP_RESULT, STEP_UNKNOWN, STEP_RECONCILED, STEP_SKIPPED, PARTIAL_REMAINDER, BLOCKED, ALERT, PLAN_RESULT` (alerte `TRANSIT_STOCK`).

### 2.7 Devis de route — `GET /bindings/{id}/path-quote?amount=`

`{status OK|NO_PATH|BELOW_MIN, source, target, amount, legs[{instId, side, sz, refPrice, feePct, spreadPct, slippagePct, costPct, belowMin}], totalCostPct, feeTestLevel, estimation BOOK|TICKER, underfunded, routeKey, routes[{routeKey, source, legs, totalCostPct, feeTestLevel, status, estimation, chosen, warnings[]}], warning?}`. Route liée au lien marquée ; route ETH affichée mais jamais choisie (`chosen=false`). Erreurs : clé rejetée 409 ; données indisponibles 503.

### 2.8 Positions détenues — `GET /api/overview?quoteCurrency=&exchangeCode=`

Positions du user (membres du groupe USD agrégés en une ligne « USD », prix 1 ; wallet illisible ignoré). Champs exacts : voir `AssetOverviewService`.

### 2.9 États limites et leur source

| Situation | Source |
|---|---|
| Soldes illisibles / clé rejetée | `snapshot.status=UNAVAILABLE`, `tradability=CREDENTIAL_INVALID \| BALANCE_UNAVAILABLE` |
| Non tradable sans fiat | `NOT_TRADABLE_WITHOUT_FIAT` (aucune action) |
| Cash insuffisant (tout-ou-rien) | `blockReason=INSUFFICIENT_CASH`, action `BLOCKED` |
| Bande atteinte | `blockReason=BAND_LIMIT` |
| Mise sous le minimum | plan `BELOW_MIN` + `carryOutUsd` > 0 |
| Plan expiré | statut dérivé `EXPIRED` après `expiresAt` |
| Étape inconnue | `UNKNOWN` ⇒ réconciliation admin, jamais de renvoi |
| Stock en transit | `transit[]` + plan `PARTIAL` + alerte `TRANSIT_STOCK` |
| Exécution coupée | `mode ≠ LIVE`, `liveExecution=false` ou `killSwitch=true` |

---

## 3. Verrous visibles de l'utilisateur

Tous requis pour qu'un ordre réel parte : mode `LIVE` déverrouillé (serveur) · kill switch levé (admin) · `executionEnabled` du lien (utilisateur) · 1ᵉʳ ordre armé (automatiquement à la 1ʳᵉ activation, ou par l'admin pour forcer une revalidation) puis confirmé (utilisateur) · statut lien `OK`/`TRADABLE_VIA_INTERMEDIARY` · clé TRADE valide · plafonds (par ordre et par jour, constantes absolues basses en phase de test : 30 USD / ordre, 100 USD / jour, à rendre configurables avant usage réel). L'utilisateur ne voit/commande que les deux verrous qui lui appartiennent.

---

## 4. Admin (page séparée, `/admin`) — pour mémoire

Actions : kill switch (lever/engager), passe live manuelle, plan (génération, réinitialisation), armement du 1ᵉʳ ordre, exécution/réconciliation (kill switch réengagé automatiquement), saisie/activation des clés TRADE d'un user, testeur d'ordre (validation sans envoi, envoi réel derrière switch rouge + saisie `ENVOYER`, route à deux jambes), édition des stratégies Actif (`PUT /api/admin/rainbow-live/strategies/{id}`), paramètres globaux de modulation (`GET/PUT /api/admin/rainbow-modulation` : `regulationMarginPct, sellBoostMax, biasMax, powerMin, powerMax, minBandWidthPts, modulationProductMax, autonomyDays`, bornes absolues affichées), backfill ETF flow, veille média, cycle décisionnel/snapshot/archivage.
Infos : bandeau permanent (`/api/admin/execution/state` : mode, LIVE déverrouillé, clé maître, kill switch + qui/quand, plafonds, ports d'ordres), plans et audit de tous les users, credentials (sans secret, `apiKeyHint`), bindings de tous les users, catalogue d'instruments (200 max), journal `admin_action_log` (acteur, action, cible, charge, résultat, date ; ≤ 100 par page).
Écran **Données** (`asset_provider`) : **[À implémenter]** (aucun endpoint aujourd'hui ; `candleSource` / `candleSourceSubstituted` du snapshot sont stockés mais non exposés).

---

## 5. Écrans cibles ↔ données disponibles

État des maquettes (Accueil, Actifs gérés, Fiche actif, Journal, Réglages, Onboarding) : [`../etudes/etude-architecture-interface.md`](../etudes/etude-architecture-interface.md).

| Élément de maquette | Données | État |
|---|---|---|
| Bandeau mode / simulation | `execution-state`, `snapshot.status`, `tradability` | [OK] |
| Alertes agrégées (en-tête Accueil) | dérivables des statuts ci-dessus | [Prévu] (agrégation côté front ou endpoint dédié) |
| Patrimoine + PnL (courbe, périodes) | `/api/portfolio/summary` (PnL hors dépôts/retraits) | [OK] dès que des instantanés existent |
| Exposition (jauge dans la bande), cash | `modulation.exposureBefore/After`, `snapshot.cashUsd` | [OK] par lien ; total multi-liens à calculer côté front |
| Autonomie courante | `autonomyDays` de `/api/portfolio/summary` ; à la demande : `proposals` (onboarding) | [OK] |
| Décisions du jour | `live-wallet` (action par lien), `lastRun` | [OK] |
| Carte actif : statut, position, dernière décision | `BindingDto`, `snapshot`, `lastRun` | [OK] |
| Carte actif : valeur, latent, réalisé, total | `/api/portfolio/assets` | [OK] |
| Graphique : achats/ventes réels, prix moyen d'achat | `/api/rainbow-live/bindings/{id}/transactions` | [OK] (prix : candles en base, endpoint de série à prévoir avec les bandes, §6 #7) |
| Graphique : achats/ventes **virtuels** du moteur (marqueurs « V », bascule « Achats/ventes virtuels », activée par défaut) | `action` de chaque point de `/api/rainbow-live/bindings/{id}/bands` (bandes figées ; en simulé, `action` est rejoué par le moteur sur toute la période affichée) | [OK] (position virtuelle, indépendante du wallet ; distincts des achats/ventes réels et des actions simulées « S ») |
| Graphique : bandes Rainbow historiques (figées / simulées `simulate`) | blocs de signal des runs, non exposés | **[À implémenter]** (§2.2) |
| Frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat | `lastRun`, `modulation`, `plan.steps`, `events` | [OK] (données réparties sur 4 endpoints) |
| Journal : plans, exécutions, frais réels, slippage, Fee Test, carry | plans (`from`/`to`), `events` | [OK] |
| Journal : un jour repliable par date, une ligne par lien (actif × exchange) fusionnant décision, plan et état de la stratégie (`strategyState` de `/api/rainbow-live/decisions`) | décisions + plans + `bindings` | [OK] |
| Journal : filtres (alertes seulement, masquer « Rien à faire », actif, provider, état, période) | — | [OK] (côté navigateur, sur la période chargée) |
| Réglages : mode, bande/puissance/biais | §1.1, §1.5 | [OK] |
| Réglages : clés lecture, plafonds utilisateur, alertes | — | **[À implémenter]** |
| Modes Simple / Avancé / Expert | préférence `displayMode` | [OK] — contenu : [`../architecture/11-front-app.md`](../architecture/11-front-app.md) |

### Composants communs

Pastille de statut unique (OK / ATTENTION / BLOQUÉ / INCONNU, pointillé si données indisponibles), carte actif, frise de décision, jauge d'exposition, étiquettes de mode (AVANCÉ / EXPERT).

---

## 6. À implémenter (back) pour réaliser les maquettes

### Décisions de cadrage (Lot 0)

- **Widgets V1** : Patrimoine, Exposition, Décisions du jour, Puissance, Biais, Prix moyen vs cours, Reporté (carry), Cash et autonomie, Prochaine passe, Zone libre. Les autres widgets du catalogue ne sont pas construits (V2).
- Modes Simple / Avancé : répartition du §5.
- Historisation : les instantanés démarrent au premier jour de passe après déploiement, sans reconstruction rétroactive ; avant, l'affichage est « — ».
- Répartition affichée = répartition **réelle** (aucune dépendance à l'étude de répartition cible).
- Hors périmètre : alertes e-mail/notification, i18n (français seul), PWA. L'édition des presets et de l'Affectation est dans l'Espace Expert (`/app/expert`).
- **Profits** : *réalisé* (gains des ventes, net de frais, contre le prix moyen d'achat — PRU, calculé par wallet × actif en rejouant les trades), *latent* (quantité détenue × (cours − PRU)), *total* = réalisé cumulé + latent. Accueil : carte « Profits » (réalisé sur la période choisie, latent, total depuis le début). Actifs gérés / fiche d'un lien : latent, réalisé, total du provider du lien. Carte « Frais » : frais cumulés, en % du capital investi, du volume échangé et des gains bruts (total + frais), répartis par provider et par actif. La courbe de patrimoine garde sa variation de valeur « hors dépôts/retraits » (libellée « variation du patrimoine »).
- Mode démo : jeu de données fictif séparé ; ne touche ni au kill switch ni à l'exécution (en prod le kill switch est engagé).

### Disponible

Endpoints `GET /api/portfolio/summary` (patrimoine, PnL de période hors dépôts/retraits, autonomie courante agrégée), `GET /api/portfolio/assets` (valeur, réalisé, latent par actif), `GET /api/portfolio/pnl` (profits réalisés/latents par période), `GET /api/rainbow-live/bindings/{id}/transactions` (achats/ventes réels et prix moyen), `GET /api/rainbow-live/next-pass` (prochaine passe), `GET /api/rainbow-live/bindings/{id}/bands` (bandes Rainbow figées) : cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

Instantané quotidien immuable du patrimoine par (user, wallet, jour UTC) : stable, actifs, patrimoine, exposition, bande en vigueur, une ligne par actif — cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md). Démarre au premier jour de passe après déploiement ; avant : « — ».

### Backends manquants

| # | Besoin | Lot | DTO / endpoint cible | Source en base | Volume | Utilisé par |
|---|---|---|---|---|---|---|
| 12 | Édition des `asset_provider` + exposition de `candleSource` / `candleSourceSubstituted` (résoudre l'écrasement par `AssetInitializer` avant) | 8 | endpoints admin | `asset_provider`, snapshot | — | Admin « Données » |

Sans back à créer : Exposition (jauge), Décisions du jour, Puissance, Biais, Zone libre (dérivée de la bande et de l'exposition), Reporté (`carryIn/Out` des plans).

Écrans construits (`/app/**`, [`11-front-app.md`](../architecture/11-front-app.md)) : Accueil, Actifs gérés, fiche actif, Journal, Réglages, Ajouter un actif (comparateur de frais), Exchanges (clés lecture/écriture), modes Simple / Avancé / Expert. Restent à construire : Données, Santé, Stratégies (cf. [`known-gaps/front-gaps.md`](../known-gaps/front-gaps.md)).
