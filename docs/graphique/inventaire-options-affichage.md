# Inventaire GUI — actions utilisateur et informations affichées

Vérifié le : 2026-10-20 (sources : [`../api/rest-endpoints.md`](../api/rest-endpoints.md), [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md), [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md), [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md), [`../architecture/09-trend.md`](../architecture/09-trend.md), [`../etudes/etude-architecture-interface.md`](../etudes/etude-architecture-interface.md)).

But : liste unique de **ce sur quoi l'utilisateur peut agir** et de **ce que le back remonte à l'affichage**, pour travailler les maquettes sans rouvrir les autres docs. Les docs sources font foi ; en cas de doute, y retourner. Toute évolution des endpoints, DTO ou réglages doit être répercutée ici (et un élément implémenté passe de « à implémenter » à disponible).

Légende : **[OK]** disponible côté back (API) · **[GUI]** déjà un écran dans `/user` · **[Prévu]** décrit dans l'étude d'interface, écran non construit (back disponible) · **[À implémenter]** absent du back (aucun endpoint ni modèle) : à construire pour que la maquette soit réalisable. Récapitulatif : §6.

Vocabulaire : « actif » (utilisateur) = **lien/binding** (technique : actif × wallet) ; « preset » = jeu de paramètres d'une stratégie ; « stratégie Actif » = preset système géré par l'admin. Actifs gérés : **BTC, ETH, PAXG**. Jour = jour UTC ; une passe quotidienne à 23:55 UTC (signal), plan à 23:58.

---

## 1. Actions utilisateur

Tout est scopé à l'utilisateur connecté (JWT cookie) ; ressource d'un autre user ou inexistante ⇒ 404 ; erreurs `{error}` (400 validation, 404, 409 conflit). 401/403 ⇒ redirection `/login`.

### 1.1 Compte

| Action | Endpoint | Remarques |
|---|---|---|
| Connexion / déconnexion / inscription | `POST /api/auth/signinForm`, `GET /api/auth/signoutForm`, `POST /api/auth/signupForm` | formulaires uniquement ; rôles USER / MODERATOR / ADMIN ; connexion d'un compte archivé ⇒ restauré |
| Curseur de risque (0–10) | `GET/PUT /api/user/risk-cursor` | continu ; **non branché** à la modulation Rainbow |
| Mode d'affichage | `GET/PUT /api/rainbow-live/ui-mode` | `AUTO\|SIMPLE\|ADVANCED\|EXPERT`, défaut `EXPERT` ; seul Expert fonctionnel aujourd'hui [GUI] |
| Saisir sa clé d'échange de lecture (soldes) | `POST /api/credentials/read` | `{provider OKX\|KRAKEN, apiKey, secretKey, passphrase (OKX), enabled?}` ; chiffrée, réponse sans secret ; crée ou rattache le wallet ; 409 sans clé maître serveur ; droit requis : lecture seule |
| Saisir sa clé d'échange TRADE | `POST /api/credentials/trade` | `{provider OKX\|KRAKEN, apiKey, secretKey, passphrase (OKX), enabled?}` ; réponse sans secret ; 409 sans clé maître serveur ; droits requis : créer/annuler des ordres, jamais retrait, restriction IP |
| Clés de lecture (maquette Réglages « clés lecture seule ») | — | **[À implémenter]** côté utilisateur |
| Plafonds utilisateur, alertes (maquette Réglages) | — | **[À implémenter]** |

### 1.2 Onboarding

| Action | Endpoint | Paramètres / effet |
|---|---|---|
| Choisir le wallet | `GET /api/rainbow-live/wallets` | `[{id, name, exchange}]` |
| Lier ses actifs | `POST /api/rainbow-live/onboarding/link` `{walletId}` | un lien **inactif** par actif sur le preset système ; résultat par actif : `LINKED \| ALREADY_LINKED \| NOT_LINKED` + statut/raison ; rejouable |
| Étaler le cash sur N jours/mois | `GET /api/rainbow-live/onboarding/proposals?walletId=&days=` | `days` ∈ bornes admin (7–365, défaut `autonomyDays` = 90) ; lecture seule |
| Appliquer les montants proposés | `PUT /bindings/{id}` `{baseAmount}` | déclenchement **manuel** (rien ne se lie tout seul) |

[GUI] carte « Démarrage » (explication repliable, wallet, « Lier mes actifs », « Montants proposés » avec colonnes autonomie « proposée » / « actuelle », « Appliquer »). Parcours en 4 étapes (exchange, actif, profil, montant) = [Prévu]. Le **profil** Prudent / Équilibré / Dynamique n'existe pas en back (bande proposée = neutre) : **[À implémenter]**.

### 1.3 Liens (bindings) = « mes actifs »

Unique par (user, actif, wallet). `GET /api/rainbow-live/bindings`, `POST` (création), `GET/PUT/DELETE /{id}`. Création [GUI] seulement dans l'onglet Bindings de l'admin ; onboarding et « + Ajouter un actif » côté utilisateur = [Prévu].

| Réglage | Champ | Contraintes / effet |
|---|---|---|
| Preset alimentant le lien | `presetId` | doit être du même actif et du user ; changer = « bascule » (événement `LIVE_SWITCH`) ; propositions : `GET /bindings/candidates?walletId=&asset=` → `[{id, name, mode, origin SYSTEM\|CUSTOM, alreadyLinkedBindingId?}]` |
| Wallet | `walletId` | du user ; clé unique (actif, wallet) ⇒ 409 si déjà lié |
| Montant de base du lien | `baseAmount` (> 0, fini) ; `resetBaseAmount=true` | vide = montant du preset ; ne met à l'échelle que les **achats** ; modifier le montant remet le **carry à zéro** (le dire à l'utilisateur) |
| Part de la position offerte | `bagPercent` 0–100 (défaut 100) | dynamique : % de la position réelle que la stratégie peut vendre |
| Priorité de passage du cash | `priority` | défaut = rang d'actif (BTC 0, ETH 1, PAXG 2) ; ordre de consommation du cash commun |
| Interrupteur d'exécution | `PUT /{id}/execution` `{enabled}` | défaut **éteint** ; n'envoie rien en soi |
| Confirmer le 1ᵉʳ ordre réel | `POST /{id}/confirm-first-live` | possible seulement après armement admin (sinon 409) |
| Vérifier la disponibilité | `GET /{id}/check` | appelle l'exchange (lecture seule), persiste le statut |
| Comparer les routes d'achat | `GET /{id}/path-quote?amount=` | lecture seule, `amount` > 0 USD |
| Supprimer le lien | `DELETE /{id}` | 204, le preset reste |

Après création/modification, la réponse porte `{binding, check}` : un check KO n'empêche pas l'enregistrement. Un preset lié ne peut pas être supprimé (400).

### 1.4 Presets

`GET /api/rainbow-live/presets[?asset=]` (crée au passage les presets système manquants), `POST`, `GET/PUT/DELETE /presets/{id}`, `PATCH /presets/{id}/enabled`.

| Action | Détail |
|---|---|
| Activer / désactiver | seul un preset **actif** est joué par la passe (bascule obligatoire par l'utilisateur) ; seule action permise sur un preset système |
| Créer | mode `FIXED` (un jeu de paramètres) ou `TREND_MIX` (jeu Bull/Bear choisi chaque jour) ; nom libre sauf préfixe réservé « [Système] » (400) ; unique (user, actif, nom) sinon 409 ; « Remplir avec le défaut de l'actif » ← `GET /defaults` |
| Éditer | nom, `enabled`, `analysisWindowMonths` (≥ 1), paramètres ; actif non modifiable ; effet **au run suivant**, historique conservé |
| Dupliquer | `POST` + `duplicatedFromId` ; dupliquer un preset système = **détachement** (preset propre, événement `DETACH`, avertissement avant) |
| Supprimer | supprime ses runs ; refusé si lié (400) ou preset système (409) ; modale de confirmation (config + historique) |
| Presets système (« suit la stratégie ») | non éditables, non supprimables ; config lue à chaque passe dans la stratégie Actif (révision `N`) |
| Stratégies Actif | `GET /strategies` lecture seule côté utilisateur (édition = admin) |

Paramètres éditables (formulaire [GUI] en accordéon, noms exacts = records `RainbowAtrTuning` / `RainbowAtrGlobals` du code, valeurs par défaut par actif via `GET /defaults.configs`) :
- **Bornes ATR** : périodes SMA et ATR, multiplicateurs de bandes (down2, down1, up 1/2/3).
- **Mécanisme achat/vente** : mode de réentrée (`reentryModes` fournis par le serveur : trailing stop %, délai fixe…), délais, fractions.
- **Cooldown & verrous** : cooldown, vente pendant cooldown, cooldown après vente, blocage d'achat.
- **Modulation ATH** : références ATH achat/vente (jours), facteurs min/max achat et vente.
- **To the moon** : réserve %, ratchet, trailing, stop.
- **Multiplicateurs & montant** : `multX2`, `multX1`, `multX0_5`, `multTriggered`, `baseAmount`.
- **Trend Mix seulement** : `trend{shortWindow, mediumWindow, longWindow, slopeScale, enter, exit, confirm, smaPeriod, atrPeriod, atrMultiplier, wickDown, wickUp}`, `rangeMapping` (`KEEP_PREVIOUS \| TO_BEAR \| TO_BULL`), puis tableau paramètre × (Bear, Bull) (ligne ambre si les deux jeux diffèrent).

Validations clés : fenêtres ≥ 2, `0 ≤ exit ≤ enter`, `confirm ≥ 1`, `baseAmount > 0`, tuning valide.

### 1.5 Modulation (curseurs)

`GET/PUT /api/user/modulation-settings` — corps complet obligatoire `{exposureMin, exposureMax, power, bias}`. Neutre = 0 / 100 / 1 / 0. Pris en compte à la **passe suivante** (valeurs clampées aux bornes admin à ce moment-là).

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

Lancer une passe ou un plan, lever le kill switch, armer le 1ᵉʳ ordre réel, envoyer/annuler un ordre, éditer une stratégie Actif, régler `asset_provider`, modifier les bornes de modulation, retirer/transférer des fonds (jamais).

---

## 2. Informations remontées à l'affichage

Principe : l'affichage ne lit que la **base** (aucun appel exchange), sauf `check` et `path-quote` (déclenchés par l'utilisateur). Nombre manquant/NaN ⇒ « — ». Le soir (23:55) le signal est calculé sur la bougie D1 **encore ouverte** (valeurs provisoires), rafraîchi le lendemain sur la bougie clôturée ; l'action décidée reste figée.

### 2.1 Mode d'exécution (bandeau) — `GET /api/rainbow-live/execution-state`

`{mode OFF|DRY_RUN|LIVE, liveExecution (LIVE déverrouillé), killSwitch}`. Affichage seul. Bandeau « Simulation / exécution réelle verrouillée » tant que `liveExecution` est faux ; état d'alerte global.

### 2.2 Presets — `PresetDto`

`id, assetSymbol, name, enabled, analysisWindowMonths, createdAt, updatedAt, mode (FIXED|TREND_MIX), tuning, globals, trendConfig?, followsStrategy, strategyRevision?, runCount, firstRunDay?, lastRun{day, close, zone, actionType, actionAmountUsdc, actionQuantity, activeSet?, trendRegime?}`.
Badges : « Stratégie · rév. N » / « Détaché » ; LIVE si lié à ≥ 1 lien ; colonne « Jeu · Trend » (jeu actif + régime UP/DOWN/RANGE du dernier run). Libellés de zones et énums via `GET /defaults` (`zones[{code,name,label}]`, `reentryModes`, `rangeMappings`, `uiModes`, `systemPrefix`).

Historique des switchs : `GET /preset-events[?asset=]` → `{type ENABLE|DISABLE|DETACH|LIVE_SWITCH|STRATEGY_CHANGED, presetBefore/After (id, nom), strategyRevision?, occurredAt, reason?}`.

Données du signal calculées en base mais **non exposées** par l'API (bloc `RainbowLivePassBlock`) : SMA, ATR, 5 bornes, distance ATH, facteurs achat/vente, armements/verrous/cooldown, réserve moon. Nécessaires pour tracer les **bandes Rainbow** du graphique : **[À implémenter]** (endpoint d'historique des runs).

### 2.3 Liens — `BindingDto`

`id, assetSymbol, presetId, presetName, walletId, walletName, exchange, bagPercent, priority, baseAmount?, tradability?, quoteMember?, routeKey?, executionEnabled, firstLiveArmed, firstLiveConfirmed`.
`tradability` = dernier `BindingCheck` : `OK, TRADABLE_VIA_INTERMEDIARY, NOT_TRADABLE_WITHOUT_FIAT, WALLET_DISABLED, CREDENTIAL_INVALID, PROVIDER_UNSUPPORTED, BALANCE_UNAVAILABLE, INSTRUMENT_UNAVAILABLE` (+ `message`, `ok`, `executable`, `blocked`). `TRADABLE_VIA_INTERMEDIARY` = route à deux jambes (ex. USDC → BTC → PAXG) : bandeau d'information.
État du 1ᵉʳ ordre réel : en attente d'armement → à confirmer → validé.

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
Statuts de plan : `PLANNED, BLOCKED, EXPIRED, DISABLED, EXECUTING, EXECUTED, PARTIAL, FAILED, CANCELLED` ; d'étape : `PLANNED, SUBMITTED, FILLED, PARTIAL, REJECTED, CANCELED, UNKNOWN`. Raisons de blocage de plan : `NO_PATH, BELOW_MIN, INSUFFICIENT_FUNDS_AFTER_FEES, NOT_TRADABLE_WITHOUT_FIAT, READING_UNAVAILABLE`. Raisons d'exécution : `COST_RED, COST_DRIFT, BELOW_MIN, REQUOTE_UNAVAILABLE, NO_BASE_AMOUNT, MODE_NOT_LIVE, KILL_SWITCH, PORT_UNAVAILABLE`…
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

Tous requis pour qu'un ordre réel parte : mode `LIVE` déverrouillé (serveur) · kill switch levé (admin) · `executionEnabled` du lien (utilisateur) · 1ᵉʳ ordre armé (admin) puis confirmé (utilisateur) · statut lien `OK`/`TRADABLE_VIA_INTERMEDIARY` · clé TRADE valide · plafonds (par ordre et par jour, constantes absolues basses en phase de test : 30 USD / ordre, 100 USD / jour, à rendre configurables avant usage réel). L'utilisateur ne voit/commande que les deux verrous qui lui appartiennent.

---

## 4. Admin (pages séparées, `/admin`, `/info`) — pour mémoire

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
| Carte actif : valeur, PnL | `/api/portfolio/assets` | [OK] |
| Graphique : achats/ventes réels, prix moyen d'achat | `/api/rainbow-live/bindings/{id}/transactions` | [OK] (prix : candles en base, endpoint de série à prévoir avec les bandes, §6 #7) |
| Graphique : bandes Rainbow historiques (figées / simulées `simulate`) | blocs de signal des runs, non exposés | **[À implémenter]** (§2.2) |
| Frise Signal → Modulation → Plafonds → Plan → Exécution → Résultat | `lastRun`, `modulation`, `plan.steps`, `events` | [OK] (données réparties sur 4 endpoints) |
| Journal : plans, exécutions, frais réels, slippage, Fee Test, carry | plans (`from`/`to`), `events` | [OK] |
| Journal : filtres serveur (actif, wallet, statut, raison), décisions et alertes hors exécution | — | **[À implémenter]** (filtres) ; décisions/alertes = [Prévu] |
| Réglages : mode, bande/puissance/biais | §1.1, §1.5 | [OK] |
| Réglages : clés lecture, plafonds utilisateur, alertes | — | **[À implémenter]** |
| Onboarding : profil Prudent/Équilibré/Dynamique | — | **[À implémenter]** |
| Modes Simple / Avancé / Expert | préférence `displayMode` | [OK] — contenu : [`../architecture/11-front-app.md`](../architecture/11-front-app.md) |

### Répartition cible par mode (étude, à valider)

| Mode | Contenu |
|---|---|
| Simple | décisions, PnL, montant de base, interrupteur d'exécution |
| Avancé | + bande d'exposition, puissance, biais, plafonds, part de la position offerte |
| Expert | + paramètres de la stratégie, presets, modulation détaillée (seul mode fonctionnel) |

### Composants communs

Pastille de statut unique (OK / ATTENTION / BLOQUÉ / INCONNU, pointillé si données indisponibles), carte actif, frise de décision, jauge d'exposition, étiquettes de mode (AVANCÉ / EXPERT).

---

## 6. À implémenter (back) pour réaliser les maquettes

### Décisions de cadrage (Lot 0)

- **Widgets V1** : Patrimoine, Exposition, Décisions du jour, Puissance, Biais, Prix moyen vs cours, Reporté (carry), Cash et autonomie, Prochaine passe, Zone libre. Les autres widgets du catalogue ne sont pas construits (V2).
- Modes Simple / Avancé : répartition du §5.
- Historisation : les instantanés démarrent au premier jour de passe après déploiement, sans reconstruction rétroactive ; avant, l'affichage est « — ».
- Répartition affichée = répartition **réelle** (aucune dépendance à l'étude de répartition cible).
- Hors périmètre : alertes e-mail/notification, i18n (français seul), PWA. `/user` est retiré à parité fonctionnelle.
- **PnL V1** = valeur actuelle − investi net (achats − ventes) ; les dépôts/retraits du wallet ne sont pas suivis. Chaque widget qui en dépend (Patrimoine, PnL) porte une mention visible « hors dépôts/retraits ». Cible : prendre les flux en compte, mention retirée alors.
- Mode démo : jeu de données fictif séparé ; ne touche ni au kill switch ni à l'exécution (en prod le kill switch est engagé).

### Disponible

Endpoints `GET /api/portfolio/summary` (patrimoine, PnL de période hors dépôts/retraits, autonomie courante agrégée), `GET /api/portfolio/assets` (valeur, investi net, PnL, prix moyen par actif), `GET /api/rainbow-live/bindings/{id}/transactions` (achats/ventes réels et prix moyen), `GET /api/rainbow-live/next-pass` (prochaine passe), `GET /api/rainbow-live/bindings/{id}/bands` (bandes Rainbow figées) : cf. [`../api/rest-endpoints.md`](../api/rest-endpoints.md).

Instantané quotidien immuable du patrimoine par (user, wallet, jour UTC) : stable, actifs, patrimoine, exposition, bande en vigueur, une ligne par actif — cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md). Démarre au premier jour de passe après déploiement ; avant : « — ».

### Backends manquants

| # | Besoin | Lot | DTO / endpoint cible | Source en base | Volume | Utilisé par |
|---|---|---|---|---|---|---|
| 12 | Édition des `asset_provider` + exposition de `candleSource` / `candleSourceSubstituted` (résoudre l'écrasement par `AssetInitializer` avant) | 8 | endpoints admin | `asset_provider`, snapshot | — | Admin « Données » |

Sans back à créer : Exposition (jauge), Décisions du jour, Puissance, Biais, Zone libre (dérivée de la bande et de l'exposition), Reporté (`carryIn/Out` des plans).

Écrans construits (`/app/**`, [`11-front-app.md`](../architecture/11-front-app.md)) : Accueil, Actifs gérés, fiche actif, Journal, Réglages, onboarding en 4 étapes, modes Simple / Avancé / Expert. Restent à construire : Données, Santé, Stratégies (cf. [`known-gaps/front-gaps.md`](../known-gaps/front-gaps.md)).
