# Glossaire

Vérifié le : 2026-10-04.

- **ScenarioOwner** — clé d'isolation de l'état vivant (scénarios/décisions) : un utilisateur réel, ou l'owner technique `SYSTEM` (`SystemOwner`).
- **StrategyType** — `DIRECTIONAL` (agrégé additivement au score) vs `CONFIDENCE_MODULATOR` (multiplie la confidence finale, jamais agrégé au score directionnel).
- **OpinionScope** — `LOCAL` (symbol-scoped, technique), `GLOBAL` (Fear&Greed + stablecoin, jamais symbol-scoped), `MACRO` (DXY/SP500/NASDAQ, jamais symbol-scoped), `EXTERNAL` (avis externe — attention, 2 implémentations concurrentes, voir [`known-gaps/code-level-inconsistencies.md`](known-gaps/code-level-inconsistencies.md)).
- **DecisionType** — `ENTER`/`EXIT`/`REBALANCE`/`STOP`, dérivé de l'`ExecutionAction` (BUY→ENTER, SELL/EXIT/NO_OP→EXIT) depuis le correctif du 2026-08-11.
- **ScenarioStatus** — état d'un `MarketScenario` (cycle de validation continue, expiration).
- **ExecutionMode** — mode d'exécution (`LIVE`/`DEV`/`BACKTEST`), détermine notamment l'implémentation d'`EventStore` utilisée (JPA pour `LIVE`, InMemory pour `DEV`/`BACKTEST`).
- **RiskProfile** — enum `LOW`/`MEDIUM`/`HIGH`, existe comme type mais non peuplé avec de vraies données utilisateur au 2026-09-12 (voir [`known-gaps/decision-to-order-gap.md`](known-gaps/decision-to-order-gap.md)). À ne pas confondre avec le curseur de risque continu 0-10, lui bien persisté (`UserTradingSettingsController`).
- **ActionIntent** — candidat d'action proposé par un `Scenario`, dédupliqué par épisode de validation continue.
- **ActionStep** — étape d'exécution d'une `Decision`, cycle de vie `CREATED → EXECUTED/ABORTED` (jamais atteint `EXECUTED` en pratique au 2026-09-12, rien n'émet l'événement correspondant).
- **OwnerRefreshGuard** — verrou anti-double-traitement en mémoire (1h), pas persisté, réinitialisé au redémarrage.
- **DomainClock** — abstraction d'horloge injectée partout dans le projet à la place d'`Instant.now()` en dur (convention systématique, y compris dans le code le plus récent).
- **asset_provider** — table pilotant la résolution en cascade des providers de marché par asset (priorité + `maxHorizonDays`), distincte du chemin plus ancien basé sur l'enum `MarketDataSource`.
- **CachingEtfFlowClient / CachingMarketDataApiClient** — décorateurs de cache DB au-dessus des clients bruts (SoSoValue / Binance-Kraken-OKX), posés par `EtfFlowCachingConfig` / `MarketDataCachingConfig`.
- **ReadOnlyBalanceReader** — contrat de lecture seule des soldes disponibles d'un exchange (Kraken, Binance, OKX) ; toute panne lève `BalanceUnavailableException` (une map vide = compte sans solde). Cf. [`architecture/07-security.md`](architecture/07-security.md).
- **ROLE_API_AGENT** — rôle attribué uniquement par `ApiKeyAuthFilter` (clé statique `X-Api-Key`), jamais persisté en base, limité aux endpoints de lecture `/scenarios`/`/decisions`.
- **Rainbow ATR** — DCA à bornes `SMA ± ATR × multiplicateur` (zones DOWN2…UP3), avec machines d'achat/vente ARMÉ, modulation par distance à l'ATH et mode « To the moon ». Moteur pur en couches `service/dca/atr/` (L1 bornes, L2 machine `RainbowAtrStrategy#step`, L3 `AthReference`, L4 `Sizer`), rejeu `RainbowAtrEngine`, port du pine `tools/pine/rainbow_dca_v4_atr_moon.pine` (source de vérité).
- **Preset (bench)** — jeu de paramètres Rainbow ATR nommé, par utilisateur et par actif, rejoué chaque jour par le bench grandeur nature.
- **Bench grandeur nature** — exécution quotidienne fictive (wallet mock USDC, aucun ordre) des presets Rainbow ATR, résultats en base ; double passe 23:55 / 00:05 UTC.
- **Stratégie Actif (Rainbow)** — config par défaut d'un actif (« TrendMix Rainbow DCA ATR · BTC »), pilotée par le compte System (`RainbowAssetStrategy`, `revision`). Les presets des users la **suivent** (aucune copie, effet à la passe suivante) tant qu'ils ne sont pas détachés par duplication. Cf. [`architecture/08`](architecture/08-rainbow-bench-grandeur-nature.md).
- **Binding (Rainbow live)** — lien unique (user, actif) entre le preset « live » et un wallet réel lu en lecture seule, avec `bagPercent` (part dynamique de la position réelle offerte à la stratégie) et `priority` (ordre de passage du cash). Cf. [`architecture/08`](architecture/08-rainbow-bench-grandeur-nature.md).
- **BLOCKED (action live)** — achat voulu par le moteur mais refusé faute de cash dans le pool du wallet (tout-ou-rien, raison `INSUFFICIENT_CASH`) ; jamais exécuté.
- **CashLedger** — pool de cash USDC d'un (user, wallet) pour une passe : les actifs le parcourent par `priority`, chaque achat accepté réserve son montant.
- **BindingCheck** — vérification de disponibilité d'un binding : credential valide, soldes lisibles, paire `<actif>/USDC` existante ; statut explicite (`OK`, `CREDENTIAL_INVALID`, `BALANCE_UNAVAILABLE`, `INSTRUMENT_MISSING`…).
- **TrendRegime** — `UP` / `DOWN` / `RANGE`, sortie du Trend unifié ([`architecture/09-trend.md`](architecture/09-trend.md)).
- **Bull / Bear (Rainbow)** — les deux jeux de paramètres Rainbow visés par actif (pas de Sideways) ; sélection manuelle dans le pine aujourd'hui.
