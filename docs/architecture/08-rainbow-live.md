---
statut: courant
vérifié_le: 2026-11-07
modifié_le: 2026-11-10
empreinte: c827dba7d5
---
# Rainbow DCA ATR live — Affectations, signal quotidien, liens (bindings), API REST

Vérifié le : 2026-11-07 (`service/expert/*`, `controller/Expert*`, `model/dto/expert/ExpertDtos`, `service/market/instrument/*`, `service/connector/{instrument,fee,orderbook}`, `service/dca/atr/binding/BindingPathQuoteService`, `model/entity/dca/bench/*`, `repository/dca/bench/*`, `service/dca/atr/bench/{AssetAffectationService,ExpertBasePresetService,AffectationConfigResolver,AffectationMigrationService,RainbowLiveRunService,RainbowLiveBindingRunService,RainbowLiveExecutionService,RainbowTrendLiveService,RainbowLiveQueryService,RainbowLiveDefaultPresets,RealPortfolioSource,LiveSizing,CashLedger}`, `model/dto/dca/bench/RainbowLiveDtos`, `service/scheduler/RainbowLivePass2355Job`, `configuration/initializer/{ExpertBasePresetInitializer,AffectationMigrationInitializer}`, `controller/RainbowLive{,Admin}Controller`, `controller/RainbowLiveControllerAdvice`, `templates/userPage.html`, `templates/fragments/rainbowLive.html`, `static/assets/js/rainbow-live.js`, `static/assets/css/rainbow-live.css`).

Périmètre : persistance + moteur câblé + **une passe quotidienne à 23:55 UTC** (job désactivé par défaut) + déclenchement admin + API REST utilisateur (Affectations, liens, wallet réel) + écrans `/app/**`. La passe calcule le **signal** de chaque Affectation jouée (référencée par au moins un lien non archivé), le dimensionne sur le **portefeuille réel en lecture seule** (par lien) et alimente le plan d'ordres ([`10-execution-plan.md`](10-execution-plan.md)). Aucun appel exchange hors lecture de marché, lecture des soldes (`RealPortfolioSource`) et exécution réelle verrouillée (test de garde `RainbowLiveNoExchangeDependencyTest`). Le moteur pur reste `service/dca/atr/RainbowAtrEngine` (cf. [`04-market-data.md`](04-market-data.md)).

Il n'existe **aucun wallet fictif ni performance simulée** : le signal d'une Affectation n'est jamais plafonné par un portefeuille, seul le dimensionnement par lien (portefeuille réel) l'est.

## Sémantique du run (`RainbowLiveRunService`)

Horloge `DomainClock` (`computedAt`) ; un run par (Affectation, jour UTC).
- **`upsert(affectation, day, block)`** (passe du jour, 23:55 UTC) : crée le run ou, sur rejeu du même jour, remplace les indicateurs en **conservant l'action d'origine**. `discardDay(user, day)` supprime les runs d'Affectation d'un utilisateur pour un jour (recalcul forcé, cf. ci-dessous).
- **`refresh(affectation, day, closedBlock)`** (passe du lendemain) : remplace les indicateurs d'un run existant par ceux calculés sur la bougie désormais **clôturée** ; l'action décidée à 23:55 reste figée. Sans run ce jour-là : `false`, rien n'est écrit (pas de rattrapage). `hasSignal(affectation, day)` conditionne le rafraîchissement.
- Chaque écriture rafraîchit le snapshot de config du run et le hash (aussi stocké dans le bloc) ; comparer au hash effectif de l'Affectation détecte « config modifiée ».
- `RainbowLiveBindingRunService` : `snapshotOf(binding, day)` / `upsert(binding, day, block)` ; rejeu du même jour : snapshot live existant conservé ; `discardDay(user, day)` supprime les snapshots de lien d'un utilisateur pour un jour (recalcul forcé).

## Affectations (`AssetAffectationService`)

`ensureAffectations(user)` (instances Système), `forAsset(user, actif)` (instance Système), `list(user)` / `listForAsset`, `get(user, id)`, `create(user, actif, nom, trend, up, down)`, `update(user, id, nom?, trend, up, down)` (Système ⇒ `AffectationRuleException` SYSTEM_READ_ONLY ; nom pris ⇒ NAME_TAKEN), `delete(user, id)` (IN_USE si lien, même archivé, ou runs), `requireLinkable`, `switchLink(user, avant, après)` (purge des états de la nouvelle + `LIVE_SWITCH`), `isActive(a)` (jouée = au moins un lien non archivé la référence), `linked(user)` / `active(user)` (liées / liées et complètes, triées par rang d'actif), Installer des presets (`create` / `update`) : trois presets de l'actif (système ou propres à l'utilisateur ; ceux d'un autre utilisateur ou d'un autre actif sont refusés), **purge les états moteur** de l'Affectation (`deleteByAffectation`) et écrit un événement `LIVE_SWITCH`. Opérations réservées au propriétaire ; erreurs typées : `AssetAffectationNotFoundException` (inexistante **ou** d'un autre user) ⇒ 404.

**Config effective** (`AffectationConfigResolver`) : accesseur unique de la config d'une Affectation (`config` = preset DOWN, `analysisWindowMonths`, `trendConfig`, `effectiveHash`), lue à chaque appel dans les presets installés. Modifier un preset ou changer les presets installés s'applique à la passe suivante : le hash effectif change et `RainbowTrendLiveService` ré-amorce l'état moteur (cf. plus bas), de sorte que le moteur bascule immédiatement sur les résultats de la nouvelle config.

**Migration au démarrage** (`AffectationMigrationInitializer` `@Order(62)` → `AffectationMigrationService#migrate`, idempotente) : à partir des anciens presets utilisateur (tables legacy en lecture seule, cf. [`../known-gaps/nettoyages.md`](../known-gaps/nettoyages.md)), crée l'Affectation de chaque (user, actif) qui n'en a pas. Preset retenu = celui référencé par un binding, sinon l'actif, sinon le plus ancien. Ancien preset sans réglages Trend propres ou identique aux défauts ⇒ presets système de base ; sinon Trend et/ou Rainbow UP/DOWN propres à l'utilisateur (créés seulement s'ils diffèrent de la base). Runs, états moteur et bindings du preset retenu sont rattachés à l'Affectation avec le même `configHash` (aucun ré-amorçage) ; runs et états des autres presets sont supprimés (journalisés), leurs bindings rattachés. Les colonnes `preset_id` des tables concernées deviennent nullables. Sans ligne legacy, ne fait rien ; une relance ne crée aucun doublon.

## Presets par défaut (`RainbowLiveDefaultPresets`, source unique des constantes)

Fenêtre 6 mois, base 1, multiplicateurs de zone = `RainbowAtrGlobals.pineDefault()` (2 / 1 / 0,5 / 3). Jeu global ([`calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md)) :

| | BTC (jeu recommandé, moon OFF) | ETH (optimum plein échantillon, moon 50 %) | PAXG (optimum plein échantillon, moon 75 %) |
|---|---|---|---|
| bornes | sma 50, atr 21, down2 5, down1 0,5, up 2/3/3,5 | sma 36, atr 21, down2 4, down1 0,5, up 2/3/3,5 | sma 36, atr 28, down2 6, down1 0,5, up 2/2/3 |
| achat / vente | TRAILING_STOP 3 % / FIXED_DELAY | FIXED_DELAY / FIXED_DELAY | FIXED_DELAY / TRAILING_STOP 2 % |
| cooldown / délai / fraction | 1 / 15 / 0,25 | 7 / 3 / 0,20 | 1 / 10 / 0,20 |
| allowSellDuringCooldown / cooldownAfterSellOn / blockBuy | false / true / true | false / true / true | true / true / true |
| ATH refBuy/refSell, buyMin/Max, sellMax/Min | 30/15, 0,25/4, 1/1 | 60/15, 0/5, 3/1 | 15/10, 1/5, 2/0,5 |
| moon reserve / ratchet / trailing / stop | 0 / non / 15 / 100 | 50 / non / 10 / 50 | 75 / non / 8 / 50 |

## Passe quotidienne (`RainbowLiveExecutionService#runPass(asOf[, dayOverride])`)

Une seule passe par jour, à **23:55 UTC**, sur la bougie D1 **du jour encore ouverte** (valeurs provisoires), comptabilisée sur le **jour UTC courant** (`dayFor(asOf)` = date UTC de `asOf`) : le signal tombe sur la bougie du jour affichée par l'exchange. Horloge injectée (`DomainClock` dans le job/l'endpoint, `asOf` explicite en test) ; `synchronized` (cron et déclenchement manuel ne se chevauchent pas). `dayOverride` (endpoint) force le jour.

- **Utilisateurs/Affectations** : pour chaque `User` `enabled` non archivé, `ensureAffectations` puis Affectations **liées** (lien non archivé) et complètes ; une Affectation liée mais incomplète est comptée `skipped` (WARN). Aucun lien ⇒ rien n'est joué (passe 0/0/0). Clé user jamais codée en dur. Une erreur (user, actif) est comptée et n'arrête jamais les autres ; pas de rattrapage des jours manqués.
- **Données** : `MarketDatasetEngine.getDailyDatasetForAsset(actif, D1_LOOKBACK_CANDLES, asOf, source)` — chemin D1 **natif** (hors Bucket H1, cf. [`04-market-data.md`](04-market-data.md#chemin-d1-natif-rainbow-getdailydatasetforasset)), chargé **une fois par (actif, source, passe)** et partagé entre Affectations/users. `source` = exchange du wallet du lien (`LiveCandleSource`, `null` si wallet sans exchange de marché : défauts `asset_provider`). La bougie du `day` est repérée par sa date UTC ; la bougie du jour suivant, éventuelle, est ignorée ; bougie absente ⇒ WARN, série sautée (pas d'exception). Dataset plus court que le warmup du preset Trend de l'Affectation ⇒ Affectation sautée.
- **Rafraîchissement J-1** (`refreshPreviousDay`, avant le run du jour) : si l'Affectation a un run la veille, elle est rejouée sur la série tronquée à J-1 (bougie désormais clôturée) : indicateurs, état moteur + position virtuelle + ATH (`Outcome#commit` seulement si le run existait). **L'action décidée la veille reste figée.** Une erreur ici est un WARN et n'empêche pas le run du jour.
- **Signal par lien** : l'Affectation est calculée sur la source de son lien le plus prioritaire qui a un exchange (défauts sans lien). Sa machine d'état est unique : elle partage son signal entre ses liens, avec WARN si les sources diffèrent (lien marqué substitué). Source indisponible ⇒ fallback `asset_provider` avec WARN bruyant ; le snapshot du lien porte `candleSource` et `candleSourceSubstituted`.
- **Fenêtre** : `RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES` = **720** (limite native Kraken `OHLC` D1, test de garde). L'ATH de référence est persisté par actif (`RainbowAthReference`). Voir [`../known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md).
- **Action du jour** : un pas de `RainbowAtrStrategy#step` : achat ⇒ BUY (montant moteur en USDC), vente ⇒ SELL. Achat et vente sur la même bougie : la **vente l'emporte** (WARN). Le signal n'est **plafonné par aucun portefeuille** ; il est transmis en `LiveDemand` (achat voulu, vente = fraction × position) puis dimensionné par lien.
- **Persistance** : bloc `RainbowLivePassBlock` complet via `RainbowLiveRunService#upsert`. Retour : `PassSummary(day, processed, skipped, errors)`.

## Moteur Trend → Rainbow (`RainbowTrendLiveService`)

Pour chaque Affectation, `RainbowLiveExecutionService` délègue à `RainbowTrendLiveService#run(day, affectation, candles, dataset, dayIdx)` :

1. **Trend** : `TrendMixCalculator` (réglages du `TrendPreset`, cf. [`09-trend.md`](09-trend.md)) sur la série D1 chargée, puis `RainbowSetSelector` (RANGE selon `rangeMapping`, DOWN au départ) → preset Rainbow joué : UP ⇒ `rainbowUp`, DOWN ⇒ `rainbowDown`. Le montant de base est celui du preset joué. Les runs écrits restent figés (snapshot de config + `configHash` par run).
2. **État et position virtuelle** : dernière ligne `RainbowLiveEngineState` antérieure au jour **si son `configHash` == `AffectationConfigResolver#effectiveHash`**. Absente (1re passe), hash différent (preset modifié ou presets installés changés) **ou trou de passes** (dernier `state_day` antérieur à la bougie précédant le jour : service arrêté, série sautée faute de bougie) ⇒ (ré-)amorçage par `RainbowAtrReplay.runFull` sur `analysisWindowMonths` mois jusqu'à la veille avec la config courante (état + `finalPosition()`), enregistré pour la veille. Un trou est tracé (WARN + journal admin `AdminAction.RUN`, acteur `auto-resync`, cible `rainbow-resync:affectation:<id>`, payload `reason`/`lastStateDay`/`resyncedTo`) : sans ce rejeu, un événement manqué (ex. levée du verrou d'achat par une clôture < DOWN2) ne serait jamais vu. Les jours manqués ne produisent **ni run ni ordre** : seul l'état moteur avance. Sans changement ni trou, l'état est recalculé depuis celui de la veille (un seul `RainbowAtrStrategy#step`).
3. **ATH** : `RainbowAthService#athBefore` (ATH de la veille, lu en base). Amorçage one-shot à la 1re demande d'un actif sans aucune ligne : historique D1 complet Binance (`BinanceDailyCandleFetcher`, klines publics, seul appel exchange hors portefeuille, une fois par actif).
4. **Dimensionnement du signal** : `ReferenceSizer` (base × facteur ATH × multiplicateur de zone ; vente = fraction × position **virtuelle**), sans plafond de portefeuille.
5. **Écritures** : état du jour, position virtuelle (`max(0, position − quantité vendue + montant acheté / close)`) et ATH du jour via `Outcome#commit`, après l'enregistrement du run. Le run du lendemain rejoue le jour sur la vraie clôture (même chemin) et **remplace** ces écritures. Rejouer une passe est idempotent.

Validation des réglages Trend : `RainbowLiveTrendConfigs#validate` (fenêtres ≥ 2, `0 ≤ exit ≤ enter`, `confirm ≥ 1`, tunings valides, `baseAmount > 0`).

## Job et déclenchement manuel

`RainbowLivePass2355Job` : propriété `tradeio.rainbow-live.pass-2355-cron`, défaut `-` (désactivé), `zone = "UTC"` explicite, cron cible `0 55 23 * * *` (cf. [`operations/scheduled-jobs.md`](../operations/scheduled-jobs.md)). Manuel : `POST /api/admin/rainbow-live/run[?day=YYYY-MM-DD]` (ADMIN, `RainbowLiveAdminController`, réponse = `PassSummary`). Pas de déclenchement manuel côté user.

**Resynchronisation de l'état moteur** (`RainbowLiveExecutionService#resync` → `RainbowTrendLiveService#resync`) : ramène l'état des Affectations actives (ou d'une seule) à la veille (dernière bougie clôturée, série bornée à la veille) par `RainbowAtrReplay.runFull`, sans run ni ordre ; toute la chaîne d'états de l'Affectation est d'abord supprimée (la passe rejoue la veille à partir de l'état d'avant : il doit être sain ou absent, auquel cas la passe ré-amorce) ; sans `force`, un état déjà à jour (même config effective, dernier `state_day` ≥ veille) n'est pas recalculé. Réponse `ResyncSummary(toDay, resynced, upToDate, skipped, errors)` ; série indisponible (bougie de la veille absente) ⇒ « sautée », rattrapée par la détection de trou de la passe suivante. Déclencheurs : (1) **au démarrage** (`service/scheduler/RainbowLiveResyncRunner`, `ApplicationReadyEvent`, propriété `tradeio.rainbow-live.resync-on-startup=true`, absente en test ; une erreur n'empêche jamais le démarrage) ; (2) **admin** `POST /api/admin/rainbow-live/resync[?affectationId=]` (forcée, tracée au journal admin avec le nom de l'admin) ; (3) automatiquement par la détection de trou de chaque passe (ci-dessus). Traçabilité : toujours `AdminAction.RUN` (pas de valeur d'enum dédiée : la colonne MySQL est un `enum` non migré par `ddl-auto`).

**Instantané de patrimoine** (`PortfolioSnapshotService`, `portfolio_snapshot` + `portfolio_snapshot_asset`) : en fin de passe, une ligne **immuable** par (user, wallet, jour UTC) : statut de la lecture, stable (`cashUsd`), valeur des actifs, patrimoine, exposition %, bande d'exposition de l'utilisateur en vigueur (`exposureMin/Max`), base de prix `D1_OPEN_2355` (clôture de la bougie D1 encore ouverte, la même que le signal) ; une ligne d'actif (quantité, prix, valeur) par actif détenu ou lié. Réutilise la **lecture de la passe** (aucun appel exchange en plus) : pas de lecture ⇒ pas d'instantané. Lecture non `OK` ⇒ statut conservé, valeurs nulles (jamais 0) ; prix manquant pour un actif détenu ⇒ totaux et exposition nuls. Idempotent (unicité user/wallet/jour : un rejeu ne réécrit rien), pas de rattrapage ni de reconstruction rétroactive ; le provider se déduit de `wallet.exchange`. Lecture : `PortfolioSnapshotRepository` (aucun endpoint encore, cf. [`../graphique/inventaire-options-affichage.md`](../graphique/inventaire-options-affichage.md) §6).

## API REST utilisateur (`RainbowLiveController`, `/api/rainbow-live`)

Contrat détaillé : [`../api/rest-endpoints.md`](../api/rest-endpoints.md). `@PreAuthorize("isAuthenticated()")` au niveau classe ; utilisateur = `IAuthenticationFacade#getConnectedUser()` ; tout est scopé à l'user connecté (aucune clé user en dur), Affectation d'un autre user ou inexistante ⇒ 404. DTO dédiés (`RainbowLiveDtos`, jamais d'entité JPA) ; `tuning`/`globals` = records `RainbowAtrTuning`/`RainbowAtrGlobals` tels quels. Erreurs : `RainbowLiveControllerAdvice` (limité à ce contrôleur) ⇒ `IllegalArgumentException` 400, not found 404. Lectures via `RainbowLiveQueryService` (`@Transactional(readOnly)`, agrégats en requêtes groupées) ; `listAffectations` est en écriture (création des Affectations manquantes).

## Écrans

Les écrans utilisateur sont les pages `/app/**` ([`11-front-app.md`](11-front-app.md)) ; toutes les données viennent de l'API `/api/rainbow-live` (le JS ne fait que de l'affichage, `textContent` uniquement). L'édition des presets et de l'Affectation relève de l'Espace Expert (`/app/expert/**`, mode d'affichage EXPERT).

### Wallet réel de l'Affectation live

- **Données** : `GET /api/rainbow-live/live-wallet` (`RainbowLiveWalletController` → `RainbowLiveWalletQueryService`, dépend des seuls repositories bindings/runs : **aucun appel exchange à l'affichage**). Une entrée par lien ; snapshot = bloc `live` du dernier `RainbowLiveBindingRun`. Contrat : [`../api/rest-endpoints.md`](../api/rest-endpoints.md).
- **Édition** : `bagPercent` via `PUT /bindings/{id}` (pris en compte à la passe suivante), statut `BindingCheck` via `GET /bindings/{id}/check` (seul appel exchange, déclenché par l'utilisateur). La création d'un binding se fait dans l'onglet Bindings de l'admin ou l'assistant `/app/onboarding` : actif + wallet (l'Affectation de l'actif est utilisée).
- Tests : `RainbowLiveWalletControllerTest`.

Suite des chantiers : [`known-gaps/rainbow-dca-chantiers-ouverts.md`](../known-gaps/rainbow-dca-chantiers-ouverts.md).

## Voir aussi (suite de ce document)

- [`08a-rainbow-entites.md`](08a-rainbow-entites.md) — Rainbow live — Entités (schéma)
- [`08b-rainbow-bindings-expert.md`](08b-rainbow-bindings-expert.md) — Rainbow live — Bindings, disponibilité, presets Expert
- [`08c-rainbow-chemin-achat-wallet.md`](08c-rainbow-chemin-achat-wallet.md) — Rainbow live — Dimensionnement réel, chemin d'achat, groupes d'actifs
- [`08d-rainbow-modulation.md`](08d-rainbow-modulation.md) — Rainbow live — Modulation (admin + utilisateur)
