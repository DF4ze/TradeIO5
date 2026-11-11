---
statut: courant
vérifié_le: 2026-11-07
modifié_le: 2026-11-10
empreinte: 6f0c38fa27
---
# Endpoints REST — Rainbow live (bindings, wallet, décisions, modulation, run)

Suite de [`rest-endpoints.md`](rest-endpoints.md) (Endpoints REST).

## Run Rainbow (admin, `RainbowLiveAdminController`)

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| POST | `/api/admin/rainbow-live/run[?day=YYYY-MM-DD]` | ADMIN | Exécute le run quotidien (tous users actifs, Affectations référencées par un lien non archivé) sur la bougie D1 ouverte ; `day` (UTC) force le jour. Réponse : `{day, processed, skipped, errors}`. Aucun ordre réel |
| POST | `/api/admin/rainbow-live/resync[?affectationId=]` | ADMIN | Resynchronisation **forcée** de l'état moteur des Affectations actives (ou d'une seule) jusqu'à la veille : rejeu de la fenêtre sur les clôtures, sans run ni ordre ; tracée au journal admin (action `RUN`). Réponse : `{toDay, resynced, upToDate, skipped, errors}` |

## Bindings Rainbow live (utilisateur, `RainbowLiveBindingController`, `/api/rainbow-live/bindings`)

Collection Postman (login, création de binding, `path-quote`) : [`tools/postman/tradeio5-path-quote.postman_collection.json`](../../tools/postman/tradeio5-path-quote.postman_collection.json).

Authentifié, scopé à l'user ; binding / wallet d'un autre user ou inexistant ⇒ 404. Erreurs via `RainbowLiveControllerAdvice` (`{error}`). Aucun endpoint de ce contrôleur n'envoie d'ordre (l'interrupteur et la confirmation ne font que changer l'état du binding). Aucun solde exposé.

| Méthode | Chemin | Description |
|---|---|---|
| GET | `` | Liste `BindingDto` `{id, assetSymbol, affectationId, affectationName, walletId, walletName, exchange, bagPercent, priority, baseAmount?, tradability?, quoteMember?, routeKey?, executionEnabled, firstLiveArmed, firstLiveConfirmed}` (par priorité ; `tradability` = dernier statut `BindingCheck` persisté) |
| POST | `` | Corps `{assetSymbol, walletId, bagPercent?, priority?, baseAmount?, preferredRoute?, affectationId?}` (défauts : 100, rang de l'actif, montant du preset Rainbow joué, route la moins chère ; `affectationId` absent = instance Système de l'actif ; Affectation d'un autre utilisateur ⇒ 404, d'un autre actif ou incomplète ⇒ 400) ; `preferredRoute` = route d'achat imposée (`USDC>ETH>BTC`, 2 ou 3 nœuds, dernier = l'actif, sinon 400), exposée dans `BindingDto.preferredRoute` ; si `baseAmount` est fourni, le Fee Test est calculé et mémorisé à la création (au mieux : un échec du devis n'annule pas le lien) ; `BindingDto` porte le dernier Fee Test (`feeTestRoute`, `feeTestPct` en %, `feeTestLevel`, `feeTestAmount` en USD, `feeTestAt`) ⇒ 201 `{binding, check}` ; binding existant pour (actif, wallet) ⇒ 409 (une même Affectation peut alimenter plusieurs bindings) ; `bagPercent` hors [0,100] / actif inconnu ⇒ 400 |
| GET | `/{id}` | `BindingDto` |
| PUT | `/{id}` | Corps `{walletId?, bagPercent?, priority?, baseAmount?, resetBaseAmount?, affectationId?}` (actif immuable, champs nuls inchangés ; `affectationId` différent de l'actuel ⇒ moteur de la nouvelle Affectation réinitialisé (états purgés, rejeu à la passe suivante) + événement `LIVE_SWITCH`, rien n'est bloqué ; mêmes contrôles qu'à la création ; `baseAmount` > 0 sinon 400 ; `resetBaseAmount=true` = retour au montant du preset) ⇒ `{binding, check}` |
| DELETE | `/{id}` | 204 : **archive** le lien (`archived_at`, exécution coupée, historique et Affectation conservés) ; déjà archivé ou inconnu ⇒ 404. Un `POST` sur le même (actif, wallet) réactive la ligne archivée au lieu de répondre 409 |
| PUT | `/{id}/execution` | Corps `{mode DISABLED\|SIMULATION\|ACTIVE}` (ou `{enabled}` : true ⇒ ACTIVE, false ⇒ DISABLED) : mode d'exécution du binding (défaut `DISABLED`) ; `SIMULATION` joue toute la chaîne sans envoyer d'ordre ; la 1ʳᵉ activation (`ACTIVE`) arme automatiquement le 1ᵉʳ ordre réel (`firstLiveApprovedAt`) ⇒ `BindingDto` (porte `executionMode`, `minBuyUsd`) |
| PUT | `/{id}` | (mise à jour du lien) accepte aussi `minBuyUsd` (> 0) et `resetMinBuy` (retire l'achat minimum) en plus de `bagPercent`, `baseAmount`… |
| POST | `/{id}/confirm-first-live` | Confirmation du 1ᵉʳ ordre réel par le propriétaire ; exécution du lien non activée (et jamais armé) ⇒ 409 ; un lien activé mais jamais armé est armé ici ⇒ `BindingDto` |
| GET | `/{id}/check` | Revérifie : `{status, message, ok, executable, blocked, quoteMember?, routeKey?}`, `status` ∈ `OK, TRADABLE_VIA_INTERMEDIARY, NOT_TRADABLE_WITHOUT_FIAT, WALLET_DISABLED, CREDENTIAL_INVALID, PROVIDER_UNSUPPORTED, BALANCE_UNAVAILABLE, INSTRUMENT_UNAVAILABLE` (appelle l'exchange en lecture seule et persiste le statut sur le binding) |
| POST | `/{id}/fee-test[?amount=]` | Recalcule le devis (`amount` USD, sinon montant de base propre du lien ; aucun des deux ⇒ 400) et le **mémorise** sur le lien ; réponse `BindingDto` ; un devis `NO_PATH` garde le dernier Fee Test connu ; mêmes erreurs que `path-quote` |
| GET | `/{id}/path-quote?amount=` | Devis à la demande de la route d'achat de `amount` USD vers l'actif du binding (lecture seule, aucun ordre) : `{status OK\|NO_PATH\|BELOW_MIN, source, target, amount, legs[{instId, side BUY\|SELL, sz, refPrice, feePct, spreadPct, slippagePct, costPct, belowMin}], totalCostPct, feeTestLevel GREEN\|WARNING\|RED, estimation BOOK\|TICKER, underfunded, routeKey, routes[{routeKey, source, legs[], totalCostPct, feeTestLevel, status, estimation, chosen, warnings[]}], warning?}` (`routes` : toutes les routes chiffrées, `chosen=false` pour ETH, affiché jamais choisi) (pourcentages en %). Le devis suit la route imposée du binding (`preferredRoute`) : indisponible ⇒ `NO_PATH`. Binding d'un autre user ⇒ 404 ; `amount` absent/≤ 0 ⇒ 400 ; clé rejetée ⇒ 409 ; soldes/frais/catalogue/carnet indisponibles ou exchange non pris en charge ⇒ 503 |

## Wallet réel des Affectations live (utilisateur, `RainbowLiveWalletController`, `GET /api/rainbow-live/live-wallet`)

Authentifié, scopé à l'user. Lit **uniquement la base** (bindings + dernier `RainbowLiveBindingRun` de chaque lien) : aucun appel exchange. Liste vide sans binding ; une entrée par binding (un actif sur deux wallets ⇒ deux éléments). Élément `LiveAssetDto` : `{assetSymbol, bindingId, affectationId, affectationName, walletId, walletName, exchange, bagPercent, priority, tradability, tradabilityMessage (texte d'avertissement si `NOT_TRADABLE_WITHOUT_FIAT`, sinon nul), executionEnabled, executionMode (DISABLED|SIMULATION|ACTIVE), firstLiveArmed, firstLiveConfirmed, routeKey, transit[{currency, quantity, finishSide BUY\|SELL, createdAt}], snapshot}` ; `snapshot` nul tant qu'aucun run live n'est joué, sinon `{day, status (OK|UNAVAILABLE|NOT_TRADABLE), fetchedAt, cashUsd (groupe USD), cashByMember {USDC, USDT}, positionQty, tradableQty, cashReserved, blockReason (NONE|INSUFFICIENT_CASH|UNAVAILABLE|NOT_TRADABLE_WITHOUT_FIAT), liveActionType (BUY|SELL|NONE|BLOCKED), liveActionAmountUsdc, liveActionQuantity, modulation (journal JSON de modulation ou nul)}`. L'action live est **recommandée, non exécutée**. Ni credential, ni secret, ni solde hors périmètre.

## Décisions quotidiennes (utilisateur, `RainbowLiveDecisionController`, `GET /api/rainbow-live/decisions`)

Lecture seule, base uniquement, scopé à l'utilisateur connecté. `?from=&to=&assetSymbol=&walletId=` (défaut : 30 derniers jours UTC). `[DecisionDto]` du plus récent au plus ancien : `{bindingId, assetSymbol, walletId, day, status, action, amountUsdc, quantity, blockReason, modulation (JSON texte), computedAt, strategyState}` (snapshot live de la passe, indépendant de l'exécution). `strategyState` = état de la machine Rainbow du preset à la fin du jour (nul si la passe n'a pas été jouée) : `{kind, close, referencePrice, triggerPrice, down2, cooldownDays, armedDays, buyLocked, zone, activeSet, trendRegime, athDistance (0..1), buyFactor, sellFactor (facteurs ATH)}`. `kind` (priorité du moteur) : `MOON_ACTIVE` (stop = plus haut × (1 − stop moon %)), `SELL_TRAILING` (plus haut depuis l'armement, stop = plus haut × (1 − trailing vente %)), `COOLDOWN` (jours restants ; `buyLocked` signale le verrou qui suivra), `LOCKED_UNTIL_DOWN2` (vendu, attente d'une clôture sous `down2`), `BUY_TRAILING` (plus bas depuis l'armement, déclenchement = plus bas × (1 + trailing achat %)), `WATCHING` (rien d'armé ; `zone` 0 = extrême bas … 5 = extrême haut). Calculé par `StrategyStateDescriber` à partir du signal du jour (`RainbowLiveRun.signal`), de l'état moteur (`RainbowLiveEngineState`) et de la config figée du run.

## Rainbow live (utilisateur, `RainbowLiveController`, `/api/rainbow-live`)

Authentifié (`@PreAuthorize("isAuthenticated()")` sur la classe), scopé à l'utilisateur connecté ; Affectation d'un autre user ou inexistante ⇒ 404. JSON, dates `YYYY-MM-DD` (jour UTC), enums en chaîne. Détail : [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md).

| Méthode | Chemin | Description |
|---|---|---|
| GET | `/defaults` | `{assets, cashCurrency, analysisWindowMonths, baseAmount, configs{BTC,ETH,PAXG → {tuning, globals}}, reentryModes[], zones[{code,name,label}], trendDefaults{actif → TrendConfigDto}, rangeMappings[]}` (enum `ReentryMode` et libellés de zones fournis par le serveur) |
| GET | `/preset-events[?asset=BTC]` | Historique des changements d'Affectation du user (`PresetEventDto`), du plus récent au plus ancien |
| GET | `/affectations[?asset=BTC][&provider=REVOLUT_X]` | Crée les instances Système manquantes (« BTC Système », presets de base ; jouées seulement une fois reliées) puis liste `AffectationDto` (Système d'abord, puis par nom). Avec `provider` : ordre de préférence = correspondance du `referenceProvider` des trois presets (`providerMatch` FULL, PARTIAL, NONE), puis Système, puis Affectations utilisateur les plus récentes ; actif hors BTC/ETH/PAXG ⇒ 400 |
| GET | `/affectations/{id}` | `AffectationDto` ; inexistante ou d'un autre user ⇒ 404 |

Réponses (noms de champs) :
- `AffectationDto` : `id, assetSymbol, name` (nom donné, à défaut libellé « <Up> / <Down> »)`, system, providerMatch?, enabled (dérivé : au moins un lien non archivé), complete, analysisWindowMonths, createdAt, updatedAt, trendPreset{id, name, origin}, rainbowUp{…}, rainbowDown{…}, tuning, globals` (config de référence = preset DOWN)`, runCount, firstRunDay?, lastRun?{day, close, zone, actionType, actionAmountUsdc, actionQuantity, activeSet?, trendRegime?}, trendConfig?` ; `origin` ∈ `SYSTEM|USER` ; `TrendConfigDto` = `{trend{shortWindow, mediumWindow, longWindow, slopeScale, enter, exit, confirm, smaPeriod, atrPeriod, atrMultiplier, wickDown, wickUp}, rangeMapping, bear{tuning, globals}, bull{tuning, globals}}` (`bear` = DOWN, `bull` = UP ; `tuning` = champs de `RainbowAtrTuning`, `globals` = champs de `RainbowAtrGlobals`, `baseAmount` inclus).
- `PresetEventDto` : `id, assetSymbol, type (ENABLE|DISABLE|LIVE_SWITCH), presetBeforeId?, presetBeforeName?, presetAfterId?, presetAfterName?, occurredAt, reason?`.
- Erreurs : `{"error": "<message>"}` (400 / 404 / 409, `RainbowLiveControllerAdvice`).

## Modulation Rainbow (paramètres admin et réglages utilisateur)

Les réglages module les quantités des achats/ventes du Rainbow (cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md)) ; aucun ordre n'est créé par la modulation.

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| GET | `/api/admin/limits` | ADMIN | `{parameters{maxOrderMultiplier, maxDayMultiplier, absoluteMaxOrderUsd, absoluteMaxDayUsd, feeWarnPct, feeRedPct : {value, defaultValue, max}}, updatedAt, updatedBy}` |
| PUT | `/api/admin/limits` | ADMIN | Corps = jeu complet des 6 valeurs ⇒ même forme que le GET ; valeur absente, ≤ 0 ou au-delà du maximum, jour < ordre, avertissement > bloquant ⇒ 400 `{error}` ; journal `LIMITS` |
| GET | `/api/admin/rainbow-modulation` | ADMIN | `{parameters{<nom>{value, defaultValue, min, max}}, updatedAt, updatedBy}` (8 paramètres, bornes absolues incluses) |
| PUT | `/api/admin/rainbow-modulation` | ADMIN | Corps = jeu complet `{regulationMarginPct, sellBoostMax, biasMax, powerMin, powerMax, minBandWidthPts, modulationProductMax, autonomyDays}` ⇒ même forme que le GET ; hors bornes ou `powerMin ≤ 1 ≤ powerMax` violé ⇒ 400 `{error}` ; journal `MODULATION` |
| GET | `/api/user/modulation-settings` | authentifié | `{settings{exposureMin, exposureMax, power, bias, regulationMarginPct} (stockés ou neutres 0/100/1/0 ; `regulationMarginPct` nul = défaut admin), limits{minBandWidthPts, powerMin, powerMax, biasMax, marginDefaultPct (défaut admin), marginMinPct, marginMaxPct (5–50)}}` |
| PUT | `/api/user/modulation-settings` | authentifié | Corps `{exposureMin, exposureMax, power, bias, regulationMarginPct?}` (les quatre premiers obligatoires ; la marge de régulation, en % de la largeur de bande dans [5, 50], est facultative : absente = inchangée) ⇒ même forme que le GET ; validation contre les limites admin courantes, 400 `{error}` sinon ; `riskCursor` inchangé. Pris en compte à la passe suivante (valeurs clampées aux bornes admin à ce moment-là) |

