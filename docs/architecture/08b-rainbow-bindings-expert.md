---
statut: courant
vérifié_le: 2026-11-07
modifié_le: 2026-11-10
empreinte: db67129418
---
# Rainbow live — Bindings, disponibilité, presets Expert

Suite de [`08-rainbow-live.md`](08-rainbow-live.md) (Rainbow DCA ATR live — Affectations, signal quotidien, liens (bindings), API REST).

## Presets Expert : règles de gestion (`service/expert/`)

Garde, API, simulation et écrans : [`13-espace-expert.md`](13-espace-expert.md). Règles portées par `AbstractExpertPresetService<P extends ExpertPreset>` (spécialisé `TrendPresetService`, `RainbowPresetService`) :

- **Droits** : preset SYSTEM modifiable par ADMIN seul et jamais supprimable ; preset USER visible et modifiable par son propriétaire seul (autre ⇒ 404). Duplication (même actif ou autre) ⇒ preset USER de l'appelant, jamais affecté d'office.
- **Modification** : verrou pessimiste + contrôle manuel de `revision` (409 si périmée), `revision` +1 à chaque changement.
- **Propagation + reset moteur** : si les réglages changent, `deleteByAffectation` des états moteur et événement `LIVE_SWITCH` pour **chaque** Affectation qui utilise le preset (ré-amorçage à la passe suivante) ; preset SYSTEM ⇒ en plus ligne `AdminAction.PRESET` (journal `AdminActionLogService`). `GET …/{id}/impact` (ADMIN, SYSTEM) renvoie le nombre d'Affectations concernées et actives.
- **Suppression** : un preset encore installé (Trend, UP ou DOWN) dans une Affectation, quelle qu'elle soit, est refusé en **409 `PRESET_IN_USE`** avec la liste des usages (Affectation, actif, rôle, jouée) ; il faut d'abord changer l'Affectation.
- **Provider de référence** : porté par le preset (`ExpertPreset#referenceProvider`), validé contre les providers actifs de l'actif, copié à la duplication seulement si l'actif cible le porte, jamais affecté automatiquement.
- **Validation** : `ExpertPresetValidator` (bornes de `RainbowAtrTuning#violations`, `RainbowLiveTrendConfigs#trendErrors`, fenêtre d'analyse 1..24 mois).

## Bindings et vérification de disponibilité (`service/dca/atr/binding/`)

- `RainbowLiveBindingService` : CRUD scopé au user (binding ou wallet d'un autre user ⇒ `RainbowLiveBindingNotFoundException` ⇒ 404 ; binding déjà existant pour (actif, wallet) ⇒ 409 ; `bagPercent` hors [0,100], actif inconnu ⇒ 400). Création et modification retournent le résultat de `BindingCheck` sans le persister : un KO n'empêche pas l'enregistrement.
- `BindingCheck` (appelable depuis le moteur) → `BindingCheckResult(status, message, quoteMember, routeKey)` (`isOk()` = OK ou TRADABLE_VIA_INTERMEDIARY : lecture autorisée ; `isExecutable()` = OK seul, tant que l'exécution multi-jambes n'existe pas ; `isBlocked()` = NOT_TRADABLE_WITHOUT_FIAT), jamais d'exception métier. Ordre : wallet activé (`WALLET_DISABLED`) → credential active (`CREDENTIAL_INVALID`) → lecteur + vérificateur pour l'exchange (`PROVIDER_UNSUPPORTED`) → lecture des soldes via `ReadOnlyBalanceReader` (`CredentialRejectedException` = clé/signature/passphrase/permission/IP rejetées ⇒ `CREDENTIAL_INVALID` ; autre panne ⇒ `BALANCE_UNAVAILABLE`) → chemin spot **sans monnaie fiat** (USDC, catalogue en base) : paire `<actif>/USDC` ⇒ `OK` ; sinon route via un intermédiaire choisi (BTC, `PathFinder`) ⇒ `TRADABLE_VIA_INTERMEDIARY` (`quoteMember`, `routeKey` ex. `USDC>BTC>PAXG`, mémorisée sur le lien) ; sinon `NOT_TRADABLE_WITHOUT_FIAT` ; catalogue absent et exchange injoignable ⇒ `INSTRUMENT_UNAVAILABLE`. Solde nul accepté, clé absente de la map = solde 0 (pas indisponible) ; les soldes ne sont ni conservés ni exposés.
- **Catalogue d'instruments** (`service/market/instrument/InstrumentCatalog`, table `exchange_instrument` : provider, `inst_id` `BASE-QUOTE` normalisé, base, quote, `state`, `min_sz`, `lot_sz`, `tick_sz`, `cost_min` (nullable ; Kraken `costmin`), `native_pair` (nullable ; Kraken `altname`), `fee_currency` (nullable ; Kraken `fee_volume_currency`), `fetched_at`, unique (provider, inst_id)) : lu par `BindingCheck` et `PathFinder`. Alimenté par **un seul** appel public « toutes les paires » par exchange via `InstrumentListClient` (`OkxInstrumentListClient` : `GET /api/v5/public/instruments?instType=SPOT`, `state=live` ; `KrakenInstrumentListClient` : `GET /0/public/AssetPairs`, noms normalisés par `KrakenAssetNames`, `status=online`, paires `.d` ignorées). Rafraîchi à la demande quand il a plus de 24 h (`tradeio.execution.instrument-catalog.refresh-after`, défaut `PT24H`) ou via `refresh(provider)` ; panne ⇒ catalogue conservé (WARN), jamais vidé, pas de nouvelle tentative avant 5 min ; catalogue absent et exchange injoignable ⇒ `InstrumentLookupException` ⇒ `INSTRUMENT_UNAVAILABLE`. Pas de checker Binance (pas de wallet Binance).
- `WalletInitializer` ne crée plus de wallet Binance ; il crée le wallet « OKX » (credential OKX si présente) en plus de Kraken/Binance Test/Ledger. Les lignes déjà en base ne sont pas touchées.

