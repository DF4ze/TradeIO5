# Bench grandeur nature Rainbow DCA ATR — persistance, presets, exécution quotidienne, API REST

Vérifié le : 2026-10-04 (`model/entity/dca/bench/*`, `repository/dca/bench/*`, `service/dca/atr/bench/{RainbowLivePresetService,RainbowLiveRunService,RainbowLiveExecutionService,RainbowLiveQueryService,RainbowLivePerformanceCalculator,RainbowLiveDeltaCalculator,RainbowLiveDefaultPresets}`, `model/dto/dca/bench/RainbowLiveDtos`, `service/scheduler/RainbowLivePass{2355,0005}Job`, `controller/RainbowLive{,Admin}Controller`, `controller/RainbowLiveControllerAdvice`, `templates/userPage.html`, `templates/fragments/rainbowLive.html`, `static/assets/js/rainbow-live.js`, `static/assets/css/rainbow-live.css`, `static/assets/vendor/lightweight-charts/`).

Périmètre actuel : persistance + moteur câblé + 2 jobs quotidiens (désactivés par défaut) + déclenchement admin + API REST utilisateur (CRUD presets, runs, performance, delta) + page web dans `/user` (section « Bench grandeur nature », cf. §Page web) ; **aucun appel exchange, aucun chemin d'ordre** (test de garde `RainbowLiveNoExchangeDependencyTest` : seule la lecture de marché `MarketDatasetEngine` est autorisée). Le moteur pur reste `service/dca/atr/RainbowAtrEngine` (cf. [`04-market-data.md`](04-market-data.md)).

## Entités (schéma créé par `ddl-auto=update`, pas de script SQL)

- `RainbowLivePreset` (`rainbow_live_preset`) : `user` (FK `User`), `assetSymbol` (BTC|ETH|PAXG), `name`, `enabled`, `analysisWindowMonths`, `initialCapitalUsdc`, `createdAt`/`updatedAt`, + `@Embedded RainbowAtrConfig`. **Unique (user, asset_symbol, name)**. Timeframe 1D fixe (non stocké).
- `RainbowAtrConfig` (`@Embeddable`) : 1 colonne typée par paramètre = `RainbowAtrTuning` (17) + `RainbowAtrGlobals` (12 + `multX2/X1/X0_5/Triggered` + `baseAmount`). `of(tuning, globals)`, `toTuning()`, `toGlobals()`, `hash()` (SHA-256 tronqué). `baseAmount` n'existe que dans la config (pas de colonne en double sur le preset). Réutilisé tel quel (mêmes noms de colonnes) comme snapshot dans le run.
- `RainbowLiveUserSeed` (`rainbow_live_user_seed`) : marqueur « presets par défaut déjà semés », `user` unique + `seededAt` (entité dédiée, hors `User`).
- `RainbowLiveMockWallet` (`rainbow_live_mock_wallet`) : 1-1 avec le preset (`ON DELETE CASCADE`), `cashUsdc`, `positionQuantity`, `assetSymbol`. Implémente `PortfolioView` (`cash()` USDC, `quantity(asset)`) — interface fine, ne présuppose pas « 1 wallet = 1 actif ». `applyBuy` refuse un achat > cash, `applySell` une vente > position. Distinct de l'entité `Wallet` (exchange) ; seul stablecoin : USDC.
- `RainbowLiveRun` (`rainbow_live_run`) : **unique (preset, `run_day`)**, `ON DELETE CASCADE` sur le preset ; `user`, `assetSymbol`, `day` (jour UTC), deux blocs `@Embedded` `RainbowLivePassBlock` nullables indépendamment (`pass2355` → colonnes `t2355_*`, `pass0005` → `t0005_*`), `config` (snapshot) + `configHash`. `deltaActionDiffers()` : vrai si les deux blocs existent et que type/montant/quantité d'action diffèrent.
- `RainbowLivePassBlock` : `close`, `sma`, `atr`, `boundDown2`/`boundDown1`/`boundUp1`/`boundUp2`/`boundUp3` (SMA ± ATR×mult correspondant, extrêmes DOWN2/UP3 compris), `zone`, `athDistance`, `buyFactor`/`sellFactor`, `moonMode`, `buyArmed`/`sellArmed`/`buyLocked`, `cooldownRemaining`, `moonReserveQty`, action (`RainbowLiveAction` BUY/SELL/NONE, `actionAmountUsdc`, `actionQuantity`, `actionPrice`), `cashAfter`/`positionAfter`, `configHash`, `computedAt`.

Suppression d'un preset ⇒ wallet mock + runs supprimés par FK. Édition d'un preset ⇒ même id, wallet et historique conservés, aucun run écrit ni recalculé.

## Sémantique des deux passes (`RainbowLiveRunService#upsertPass`)

Idempotent par (preset, jour, passe) ; horloge `DomainClock` (`computedAt`).
- **23:55** : action fictive. Appliquée au wallet mock **une seule fois par (preset, jour)** ; `cashAfter`/`positionAfter` renseignés par le service. Rejeu : indicateurs remplacés, mais action et état du wallet d'origine conservés.
- **00:05** : mêmes indicateurs sur la vraie clôture + « action qui aurait été prise », **jamais appliquée** (n'altère ni le bloc 23:55 ni le wallet). Peut exister sans 23:55.
- Chaque upsert rafraîchit le snapshot de config du run et le hash (aussi stocké dans le bloc) ; comparer au hash du preset détecte « config modifiée ».
- Un achat supérieur au cash lève `IllegalStateException` (rien n'est persisté) : l'appelant (`RainbowLiveExecutionService`) plafonne donc AVANT l'appel.

## Presets (`RainbowLivePresetService`)

`create` (valide `Tuning.isValid()`, fenêtre ≥ 1, capital > 0, baseAmount > 0, actif autorisé, nom libre ; crée le wallet mock à `initialCapitalUsdc`), `update` (nom, enabled, fenêtre, config ; actif et capital non modifiables), `delete`, `get(user, id)`, `list(user[, actif])` ; opérations réservées au propriétaire. Erreurs typées (sous-classes d'`IllegalArgumentException`) : `RainbowLivePresetNotFoundException` (inexistant **ou** d'un autre user), `RainbowLivePresetConflictException` (nom déjà pris pour l'actif).

**Seed une seule fois par user** : `ensureDefaultPresets(User)` est un no-op si le marqueur `RainbowLiveUserSeed` existe ; sinon il crée un preset « Bench global » pour chaque actif sans preset puis pose le marqueur. Supprimer tous les presets d'un actif = ne plus le suivre (jamais re-créé). Un user qui a déjà ses presets obtient le marqueur sans doublon. Aucun initializer au boot (appelé par le job quotidien et par `GET /presets`).

## Presets par défaut (`RainbowLiveDefaultPresets`, source unique des constantes)

Fenêtre 6 mois, capital 1000 USDC, base 1, multiplicateurs de zone = `RainbowAtrGlobals.pineDefault()` (2 / 1 / 0,5 / 3). Jeu global du bench ([`calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md)) :

| | BTC (jeu recommandé, moon OFF) | ETH (optimum plein échantillon, moon 50 %) | PAXG (optimum plein échantillon, moon 75 %) |
|---|---|---|---|
| bornes | sma 50, atr 21, down2 5, down1 0,5, up 2/3/3,5 | sma 36, atr 21, down2 4, down1 0,5, up 2/3/3,5 | sma 36, atr 28, down2 6, down1 0,5, up 2/2/3 |
| achat / vente | TRAILING_STOP 3 % / FIXED_DELAY | FIXED_DELAY / FIXED_DELAY | FIXED_DELAY / TRAILING_STOP 2 % |
| cooldown / délai / fraction | 1 / 15 / 0,25 | 7 / 3 / 0,20 | 1 / 10 / 0,20 |
| allowSellDuringCooldown / cooldownAfterSellOn / blockBuy | false / true / true | false / true / true | true / true / true |
| ATH refBuy/refSell, buyMin/Max, sellMax/Min | 30/15, 0,25/4, 1/1 | 60/15, 0/5, 3/1 | 15/10, 1/5, 2/0,5 |
| moon reserve / ratchet / trailing / stop | 0 / non / 15 / 100 | 50 / non / 10 / 50 | 75 / non / 8 / 50 |

## Exécution quotidienne (`RainbowLiveExecutionService#runPass(pass, asOf[, dayOverride])`)

Horloge injectée (`DomainClock` dans les jobs/l'endpoint, `asOf` explicite en test) ; `synchronized` (cron et déclenchement manuel ne se chevauchent pas).

- **Jour UTC traité** (`dayFor`) : `T2355` ⇒ date UTC de `asOf` ; `T0005` ⇒ date UTC de `asOf` − 1 (la clôture qui vient de se terminer). `dayOverride` (endpoint) force le jour.
- **Utilisateurs/presets** : pour chaque `User` `enabled` non archivé, `ensureDefaultPresets` puis presets `enabled` groupés par actif. Clé user jamais codée en dur. Une erreur (user, actif, preset) est comptée et n'arrête jamais les autres ; pas de rattrapage des jours manqués.
- **Données** : `MarketDatasetEngine.getDatasetForAsset(actif, D1, D1_LOOKBACK_CANDLES, asOf)` (pipeline officiel + fallback `asset_provider`), chargé **une fois par (actif, passe)** et partagé entre presets/users (un seul `RainbowAtrDataset`, caches SMA/ATR partagés). La bougie du `day` est repérée par sa date UTC ; la bougie du jour suivant (présente à 00:05) est ignorée ; bougie absente ⇒ WARN, actif sauté (pas d'exception). Dataset < warmup+2 ⇒ preset sauté.
- **Contrainte bucket** : D1 est un resampling de H1 (`Bucket`, `BASE_MAX_ITEMS` = 100 000 H1 ≈ 4166 j). `RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES` = 4000 (4000 × 24 = 96 000 H1) couvre BTC/ETH depuis 2017-08 sans dépasser la capacité (test de garde sur la taille). La dernière bougie D1 est l'agrégat H1 potentiellement incomplet (voulu pour 23:55). Le bucket est en mémoire (singleton) : 1er appel lourd, puis incrémental ; perdu au redémarrage.
- **Rejeu** : `startIdx = max(warmup(sma, atr), 1re bougie ≥ day − analysisWindowMonths)`, `endIdx` = bougie du jour ; `RainbowAtrEngine.simulate(..., trace=true)` (moteur inchangé). ATH/moon sur tout l'historique chargé.
- **Action du jour** = events dont `index == endIdx` : `BUY_ZONE`/`BUY_TRIGGERED` ⇒ BUY (montant moteur en USDC), `SELL`/`MOON_STOP` ⇒ SELL (quantités sommées). Achat et vente sur la même bougie (ex. `MOON_STOP` sans cooldown) : la **vente l'emporte** (WARN).
- **Dimensionnement (dans le service, pas dans le moteur)** : achat `min(montant, cash mock)` (cash 0 ⇒ `NONE`), quantité = montant / close ; vente `min(quantité, position mock)` (position 0 ⇒ `NONE`), produit = quantité × close. À 00:05 : même calcul sur l'état courant du wallet (non modifié ; `upsertPass` n'applique jamais l'action 00:05).
- **Limite connue assumée** : le rejeu repart d'une position moteur nulle, le wallet mock est la vérité cumulée ; moteur et wallet divergent donc (le moteur peut vouloir vendre plus que le mock ne détient). Le wallet ne sert qu'à plafonner.
- **Persistance** : bloc `RainbowLivePassBlock` complet (close, sma, atr, 5 bornes, zone, facteurs ATH, états des machines, `moonReserveQty`, action, `actionPrice` = close) via `upsertPass`. Retour : `PassSummary(pass, day, processed, skipped, errors)`.

## Jobs et déclenchement manuel

`RainbowLivePass2355Job` / `RainbowLivePass0005Job` : propriétés `tradeio.rainbow-live.pass-2355-cron` / `pass-0005-cron`, défaut `-` (désactivés), `zone = "UTC"` explicite, crons cibles `0 55 23 * * *` / `0 5 0 * * *` (cf. [`operations/scheduled-jobs.md`](../operations/scheduled-jobs.md)). Manuel : `POST /api/admin/rainbow-live/run?pass=T2355|T0005[&day=YYYY-MM-DD]` (ADMIN, `RainbowLiveAdminController`, réponse = `PassSummary`). Pas de déclenchement manuel côté user.

## API REST utilisateur (`RainbowLiveController`, `/api/rainbow-live`)

Contrat détaillé : [`../api/rest-endpoints.md`](../api/rest-endpoints.md). `@PreAuthorize("isAuthenticated()")` au niveau classe ; utilisateur = `IAuthenticationFacade#getConnectedUser()` ; tout est scopé à l'user connecté (aucune clé user en dur), preset d'un autre user ou inexistant ⇒ 404. DTO dédiés (`RainbowLiveDtos`, jamais d'entité JPA) ; `tuning`/`globals` = records `RainbowAtrTuning`/`RainbowAtrGlobals` tels quels. Erreurs : `RainbowLiveControllerAdvice` (limité à ce contrôleur) ⇒ `IllegalArgumentException` 400, not found 404, conflit de nom 409. Lectures via `RainbowLiveQueryService` (`@Transactional(readOnly)`, requêtes bornées par plage de jours, agrégats presets en 3 requêtes groupées) ; `listPresets` est en écriture (seed lazy).

- **`configChanged` / `changedParams`** : par run, `configChanged` = `configHash` ≠ celui du run précédent du preset (le 1er run d'une plage `from` est comparé au run antérieur hors plage ; le tout premier run ⇒ `false`) ; `changedParams` = `RainbowAtrConfig#diff` des snapshots (noms des composants de `RainbowAtrTuning` nus, de `RainbowAtrGlobals` préfixés `globals.`, ex. `smaPeriod`, `globals.athRefDdBuyPct`). Même liste de marqueurs dans `/performance` (`configMarkers`).
- **Performance** (`RainbowLivePerformanceCalculator`, pur, à la lecture, rien de persisté) : rejeu des actions figées des blocs 23:55 du 1er run jusqu'à `to`. BUY ⇒ investi/position/coût de revient ; SELL ⇒ réalisé += produit − coût × qté/position (coût moyen). Mêmes définitions que `RainbowAtrResult`/pine : restant = position × dernier close, potentiel = restant − coût, total = vendu + restant − investi, pourcentages rapportés à l'investi (0 si investi = 0, jamais de NaN/Inf). Un jour sans bloc 23:55 est ignoré (pas de rattrapage). Reflète le **wallet mock** (actions plafonnées), pas la position du rejeu moteur (cf. limite ci-dessus) ; une vente > position est plafonnée à la position.
- **DCA fixe de référence** : à chaque jour ayant un bloc 23:55, achat de `baseAmount` (celui du snapshot de config du run du jour) au close du jour, à partir du 1er run ; jours manquants non comblés ; valorisé au dernier close. `outperformanceGain` / `outperformancePoints` = stratégie − DCA fixe (gain, points de PnL %).
- **Équité wallet mock** : `cashAfter + positionAfter × close` stockés par le 23:55 du dernier jour, vs `initialCapitalUsdc` ; la position rejouée == `positionAfter` stocké (testé).
- **Delta** (`RainbowLiveDeltaCalculator`) : jours ayant les deux blocs ; nb de jours où l'action diffère (détail 23:55 vs 00:05), écarts moyens/max absolus et en % de la valeur 23:55 sur close, sma, atr et les 5 bornes, divergences de zone et d'états des machines (`moonMode`, `buyArmed`, `sellArmed`, `buyLocked`, `cooldownRemaining`).

## Page web (`GET /user`, section « Bench grandeur nature — Rainbow DCA ATR »)

- **Emplacement** : `templates/userPage.html` inclut le fragment `templates/fragments/rainbowLive.html` (conteneur `#rainbow-live`, modales formulaire/suppression) ; toute la logique est dans `static/assets/js/rainbow-live.js` (JS sans framework, `fetch` same-origin, cookie JWT, pas de jQuery) ; styles dans `static/assets/css/rainbow-live.css` (modales relevées au-dessus du menu latéral du template). `MainController#userAccess` inchangé. Test de rendu : `MainControllerUserPageTest`.
- **Graphiques** : TradingView `lightweight-charts` **5.2.1** vendorée en local (`static/assets/vendor/lightweight-charts/lightweight-charts.standalone.production.js` + `LICENSE`, Apache-2.0 ; attribution dans `NOTICE`, logo/lien TradingView affiché sur le graphique de performance). API v5 : `chart.addSeries(LineSeries|HistogramSeries, …)`, `createSeriesMarkers`. Dates `YYYY-MM-DD` ; les jours sans run ne sont pas interpolés.
- **Données** : tous les chiffres viennent de l'API (`/api/rainbow-live`), le JS ne fait que de l'affichage (null/NaN ⇒ « — »). Valeurs de l'enum `ReentryMode` et libellés de zones : fournis par `GET /defaults` (`reentryModes`, `zones`). Entrées utilisateur/serveur insérées via `textContent` (jamais en HTML). 401/403 ⇒ redirection `/login` ; erreurs `{"error"}` (400/404/409) affichées dans le formulaire ; erreur réseau/5xx ⇒ message + « Réessayer ».
- **Panneaux** : onglets par actif (BTC/ETH/PAXG) → tableau des presets (bascule actif = `PUT` à l'identique, wallet mock, nb de runs, 1er/dernier run ; actions Voir/Éditer/Dupliquer/Supprimer, Nouveau preset). Formulaire en accordéon (Général, Bornes ATR, Mécanisme achat/vente, Cooldown & verrous, Modulation ATH, To the moon, Multiplicateurs & montant) couvrant tous les champs de `tuning`/`globals` ; « Remplir avec le défaut de l'actif » ← `/defaults.configs[actif]` ; en édition actif et capital initial sont en lecture seule. Suppression : modale de confirmation (config + historique + wallet mock). Détail d'un preset (chargé à la sélection, panneau par panneau) : performance (`/performance` : `metrics`, DCA fixe, `wallet`), graphique performance (stratégie = vendu + restant, DCA fixe, investi cumulé ; marqueurs achats/ventes = `markers`, « config modifiée » = `configMarkers`, infobulle au survol), histogramme de taille de position (valeur de marché / coût / quantité = `currentValue` / `costBasis` / `position` de `series`), tableau des runs (`/runs`, 90 derniers jours par défaut, badges `⚠ delta` et `config modifiée`, lignes dépliables pour les bornes), vue delta (`/delta`, chiffres bruts sans conclusion).

## Place dans la chaîne cible

Le bench tourne chaque jour sur le VPS et enregistre en base le résultat de tous les presets (utilisable pour comparer les jeux sur données réelles). Les presets par défaut sont les jeux **globaux** du bench d'optimisation ; les jeux Bull/Bear réglés à la main (pine v4) n'y sont pas semés et aucune sélection automatique par la Trend n'existe. Suite : [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md).
