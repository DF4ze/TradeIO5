# Rainbow DCA ATR — chantiers ouverts

Vérifié le : 2026-10-11 (code + décisions de Clem). Objectif n°1 du projet : **automatiser le DCA intelligent** (achats/ventes réels). Cette page liste ce qui sépare l'état actuel de cet objectif. Sauf mention « fait », rien ici n'est implémenté.

## État de la chaîne

| Brique | État |
|---|---|
| Moteur Rainbow ATR (pine v4 = source de vérité, port Java) | fait — découpé en couches L1 bornes / L2 `step` pur + `RainbowAtrState` / L3 `AthReference` / L4 `Sizer` (référence seulement) ; état et ATH persistés pour les presets `TREND_MIX` (`RainbowLiveEngineState`, `RainbowAthReference`) — [`04-market-data.md`](../architecture/04-market-data.md) |
| Paramétrage par actif et par trend (Bull / Bear) | fait pour BTC, ETH, PAXG : presets `<ACTIF> Perso Bear/Bull` du pine v4 repris en Java ; stratégies Actif (une Trend Mix par actif, pilotées par System, suivies par les presets des users, inactifs au départ) aux réglages par défaut. Réglages PAXG ajustés à la main par Clem |
| Trend simple (`TrendAnalyzer`, UP/DOWN/RANGE) | fait — [`09-trend.md`](../architecture/09-trend.md) |
| Client d'ordres Kraken | code et tests faits (`KrakenSpotOrderClient`, testeur d'ordre `validate`) ; **jamais utilisé en réel** : ni `validate` ni ordre réel passés sur Kraken, hypothèses sur l'API à confirmer au 1ᵉʳ essai. PAXG reste `NOT_TRADABLE_WITHOUT_FIAT` (aucune paire stable) |
| Branchement Trend → jeu de paramètres | fait : Trend Mix (`TrendMixCalculator` = méga-pine `rainbow_trend_dca_v1.pine`, vérifié par Clem Java vs pine) choisit Bull/Bear à chaque bougie (`RainbowSetSelector`) ; état de la machine et ATH persistés ; branché au dry-run quotidien (presets `TREND_MIX`, [`08`](../architecture/08-rainbow-live.md)), pas à une exécution réelle |
| Run Rainbow quotidien 23:55 UTC (signal des presets actifs, page web ; sans simulation de wallet ni performance) | fait, tourne chaque jour sur le VPS — [`08`](../architecture/08-rainbow-live.md) |
| Candles du signal par lien (D1 natif 720 j, source = exchange du wallet) | fait en code, tests ciblés verts, **pas encore déployé ni exécuté en réel** (colonnes `candle_source*` ajoutées par `ddl-auto=update` au 1ᵉʳ démarrage). Reste : affichage page, ATH Trend Mix sur la source du lien, bascule Binance au-delà de 720 j — §8bis, [étude](../etudes/etude-source-candles-provider-du-lien.md) |
| Recréation d'une base (Flyway + initializers) | fait : `V1__init.sql` généré (non activé), défauts créés par les initializers, stratégies Actif pilotées par System (presets qui les suivent) — [`operations/flyway.md`](../operations/flyway.md). données historiques (`candle`, `etf_flow_snapshot`) sauvegardées chaque semaine et rechargées au démarrage. **Ouvert** : sauvegarde récurrente de la branche décisionnelle (branche en pause) |
| Bande d'exposition, puissance, biais, onboarding | design validé, non fait — [spec V1 §2, §4–§7](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md) |
| Risque macro (`riskCursor`) | non fait — spec V1 §3 |
| Wallet réel (lecture seule) branché au preset live | fait : connecteurs, bindings, moteur sur vrai cash, page ; lecture OKX et Kraken vérifiée en réel (Home affiche PAXG Kraken) ; reste la bascule preset / `bagPercent` et les cas BLOCKED / UNAVAILABLE en conditions réelles, cf. §5 ; `WalletSnapshot` (branche décisionnelle) non branché — [`decision-to-order-gap.md`](decision-to-order-gap.md) |
| Exécution d'ordres réels | partiel : exécuteur, audit, kill switch, plafonds faits ; 1ᵉʳ ordre réel OKX (BTC-USDC) et vente via le testeur d'ordre validés, 1ᵉʳ achat réel PAXG sur Kraken (USDC → BTC → PAXG) exécuté depuis la Console, `LIVE` verrouillé par défaut ; cron d'exécution désactivé — cf. §5c |

## 1. Paramétrage automatique d'un actif quelconque (priorité moindre)

Besoin : pouvoir ajouter n'importe quel actif, avec un système de paramétrage intelligent derrière. Constat : le bench d'optimisation automatique (`RainbowAtrRegimeBench`, coordinate ascent) donne des résultats décevants (paysage non monotone, ~30 paramètres couplés, jeux dégénérés « ne vend jamais ») ; les jeux Bull/Bear de BTC, ETH et PAXG (Kraken) ont été trouvés à la main sous TradingView. Le bench n'est donc **pas** la source des paramètres ([`calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md)). Approche testée puis abandonnée (2026-10-09) : bench automatique du Trend Mix (grille + walk-forward) et recherche automatique des jeux Bull/Bear, tous deux sans edge robuste hors échantillon — verdict dans [`etudes/etude-bench-auto-trend-mix-nouvel-actif.md`](../etudes/etude-bench-auto-trend-mix-nouvel-actif.md) §7. Un nouvel actif se règle donc à la main (Trend Mix par défaut puis jeux Bull/Bear sous TradingView) ; le harnais `TrendMixBenchMain` ([`09-trend.md`](../architecture/09-trend.md)) contrôle la qualité de la Trend.

## 2. Trend Mix : constantes à valider par actif

Le branchement Trend → jeux Bull/Bear est fait (cf. tableau). Décisions : Trend double **abandonnée** (2026-10-09) ; `RANGE` n'est pas un switch (la dernière direction décisive de la régression est mémorisée, repli sur le régime de la SMA) ; le jeu actif est réévalué chaque jour, y compris pendant un armement en cours.

Reste : les constantes du Trend Mix ont été réglées à la main sur BTC et servent par défaut à ETH et PAXG ; à valider par actif à l'œil sous le pine (le bench automatique n'apporte pas d'edge, cf. §1).

## 3. Bande d'exposition, puissance et biais

Implémenté (étapes 1–2, 2026-10-13) ; écrans et onboarding restent à faire. Design : l'utilisateur fixe une **bande** `[min, max]` d'exposition (actifs ÷ total), modulation des achats/ventes Rainbow uniquement dans les marges près des bornes (hors bande : achat ramené à 0 ou vente accentuée, **jamais d'ordre de rééquilibrage** : seul le Rainbow déclenche) ; **puissance** (un scalaire sur le montant) et **biais** achat/vente ; journal des modulateurs à chaque action ; paramètres globaux réglables par l'admin. Détail : [spec V1 §2, §4–§7](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md). S'applique dans `LiveSizing`, par lien. Un achat et une vente simultanés sur la même bougie sont aujourd'hui impossibles (la vente l'emporte).

## 4. Risque macro

Intention (pas de code) : `riskCursor` (0-10, persisté, orphelin) comme gain sur un score de risque marché à construire. Voir spec V1 §3 ; pas d'historique macro rejouable pour un backtest.

## 5. Wallet réel et exécution

Hors Rainbow : branchement `WalletSnapshot`, sizing réel, composant d'exécution (dry-run obligatoire avant activation) — [`decision-to-order-gap.md`](decision-to-order-gap.md).

**Wallet réel du preset live — reste à vérifier** (la lecture OKX et Kraken est vérifiée en réel) :
- **Vérification réelle** : basculer le preset live et `bagPercent` et constater leur prise en compte au run suivant ; cas BLOCKED / UNAVAILABLE en conditions réelles.
- **Page** : le rendu JS n'est pas testé en navigateur (tests Java : API + conteneur + absence d'`innerHTML`) .
- **Clôture du lot** : après le « Point » avec Clem, la roadmap `wallet-reel` reste en `docs/prompts/` (trace de la réflexion) ; les prompts d'implémentation exécutés sont déjà supprimés.
- **Hors lot** (plus tard) : token d'exchange, découverte automatique du wallet, alerte % de liquidité (§7), compte Funding OKX / Earn.

## 5b. Pages, navigation, charte graphique et saisie des credentials (non fait)

Ordre voulu : d'abord boucler le fonctionnel du back, puis :
- **Architecture des pages** : définir les pages, leur organisation, les menus.
- **Charte graphique** à définir. Besoins d'interface déjà collectés (réglage des `asset_provider` depuis la GUI, affichage de la source des candles d'un lien) : [`../graphique/remarques-gui.md`](../graphique/remarques-gui.md).
- **Saisie des credentials d'exchange** : faite pour la clé **TRADE OKX** (page `/admin`, onglet Credentials, `POST /api/credentials/trade`, cf. [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md)). Saisie des clés **READ** : `POST /api/credentials/read` (chiffrées, wallet créé). Reste à faire : autres exchanges que OKX/Kraken, gestion fine des wallets (renommer, désactiver).

## 5c. Chemin d'achat via intermédiaire non stable (vérifié le 2026-10-12)

- **Constat** : PAXG n'a aucune paire spot en USDC/USDT sur Kraken (USD, EUR, XBT, ETH) ; la route sans fiat est `USDC → BTC → PAXG` (ETH chiffré, affiché, jamais choisi). Sur OKX, le compte EEA de Clem reçoit `51155` sur les paires USDT, USDT est exclu : seuls BTC et ETH (en USDC) sont achetables.
- **Déjà en place** : groupe USD = USDC, statuts `OK` / `TRADABLE_VIA_INTERMEDIARY` / `NOT_TRADABLE_WITHOUT_FIAT` + `routeKey` persistés sur le binding, clients Kraken lecture seule (TradeVolume, Depth), `PathFinder` multi-jambes, plan dry-run multi-jambes avec stock en transit (`TransitStock`). `ExecutionGuard` refuse tout sauf `OK`. Attendu : Kraken PAXG ⇒ `TRADABLE_VIA_INTERMEDIARY` (`USDC>BTC>PAXG`), BTC/ETH ⇒ `OK`.
- **Exécution multi-jambes codée** (retry borné de la jambe 2, stock en transit, validation par jambe, page) — [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md). **1ᵉʳ achat réel PAXG minimal exécuté sous contrôle de Clem** (taux taker réel via `TradeVolume`, coût ≈ 0,8 % par jambe ⇒ Fee Test rouge à 0,8 % : le seuil se règle par `tradeio.execution.fee-test.red-pct`). **Reste** : vérifier le comportement de la jambe 2 sans BTC détenu, exécution manuelle quelques jours avant tout cron. Hors périmètre : route ETH automatique, bascule automatique de route, ordre compensatoire, retrait/transfert API (jamais).
- **Lot B, étape a faite** (catalogue d'instruments, frais réels `trade-fee`, carnet, `PathFinder`, Fee Test, `GET …/bindings/{id}/path-quote` ; cf. [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md)) : le chemin le moins coûteux est chiffré en lecture seule. 
- **Lot B, étape b faite** : plan d'ordres dry-run enregistré et affiché (job `ExecutionPlanJob` désactivé par défaut, bilan virtuel USDC, achats + ventes, `clOrdId` déterministes, expiration 5 min, mode global `OFF|DRY_RUN`, `LIVE` refusé au démarrage) — [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md). 
- **Lots C1 (back-end) et C2 (panneau d'exécution sur la page) faits** : clé TRADE chiffrée, port d'ordres + client OKX (inactif par défaut), exécuteur avec réconciliation, audit append-only, kill switch, plafonds, double validation du 1ᵉʳ ordre — [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md). **1ᵉʳ ordre réel validé (BTC-USDC, minimal) ; `LIVE` verrouillé par défaut.** **Reste** : exécution manuelle quelques jours avant tout cron ; filtrage du catalogue par instruments du compte ; chiffrement optionnel des clés READ ; plafonds absolus (30 USD par ordre, 100 USD par jour, constantes) et multiplicateurs relatifs (table `execution_control`, SQL seulement) à rendre configurables par utilisateur dans la page, avec avertissement à l'approche des seuils.

## 5d. Carry des mises sous le minimum du provider (back fait, UI à faire)

- **Fait** : un achat `BELOW_MIN` est reporté et cumulé jusqu'à ce que l'ordre passe ; remis à zéro si le base amount change ; pas de sur-achat ; seul `BELOW_MIN` côté achat déclenche le report (vente et autres blocages : carry inchangé). Cf. [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md) § Carry. Exposé en lecture (`carryInUsd` / `carryOutUsd` par actif du plan).
- **Reste** : UI (journal des exécutions, signalement du carry et de sa somme, choix de l'utilisateur), cf. [`../graphique/remarques-gui.md`](../graphique/remarques-gui.md) §2. Pas de report des échecs d'exécution réelle (`PARTIAL` / `FAILED`) : l'échec reste visible. Report sur `INSUFFICIENT_FUNDS` : idée UX, non faite.

## 6. Preset Bull PAXG

Constat du 2026-10-05 : trop de ventes, le bag ne gonfle pas. Réglage manuel par Clem (pas de chantier de code).

## 6b. Stratégies Actif : gestion System et Stratégie Wallet (reste à faire)

Fait : la config par défaut est pilotée par System via la stratégie Actif (cf. [`architecture/08`](../architecture/08-rainbow-live.md)). Reste :
- **Création de stratégies** : aujourd'hui seul l'Initializer en crée (une Trend Mix par actif). Prévoir un endpoint System de création (nouvel actif / nouvelle stratégie) et une **page de gestion admin** dédiée (édition des jeux Bear/Bull et Trend, vue des révisions).
- **Stratégie Wallet** : niveau portefeuille avec vue globale des actifs (capital commun, priorités, exposition) — aujourd'hui portés par les bindings (`priority`, `bagPercent`) et le `CashLedger`.
- **Page** : afficher l'historique des switchs (`GET /preset-events`) ; l'API existe, pas d'écran.

## 7. Interface utilisateur : modes et alertes (intentions, pas de code)

- **Modes de réglage** par utilisateur (choix stocké en base, sélecteur déjà présent sur la page) : *Expert* (tous les paramètres, Trend Mix et jeux Bear/Bull éditables) est livré ; restent *Auto*, *Simple* (curseurs seulement) et *Avancé* (paramètres les plus influents). Un bench en ligne (comparable au pinescript sous TradingView) est envisagé pour chacun des modes.
- **Alerte utilisateur** quand le portefeuille devient pleinement exposé (plus de stablecoin disponible) : inviter à ajouter des fonds. Dépend du vrai wallet et du curseur d'exposition.

## 8. Fee Test et jugement des benchs utilisateur (intentions, pas de code)

- **Fee Test** : fait pour le chemin d'achat (étape a du lot B, `FeeTest` + `path-quote`) : coût total (frais réels + demi-spread + slippage) < 0,2 % GREEN, jusqu'à 0,8 % WARNING, au-delà RED. Reste à brancher : test à la sélection d'une paire dans le formulaire, extension aux autres connexions.
- Extensible aux « Bench online » ; à terme, un système qui juge les benchs des utilisateurs, surtout ceux à PnL négatif.
- Contexte chiffré : [`etudes/etude-trading-stablecoin-frais-btc-eth-paxg.md`](../etudes/etude-trading-stablecoin-frais-btc-eth-paxg.md).
- **Token de l'exchange** (OKB chez OKX, KRAKEN chez Kraken…) : détenir ce token donne souvent une remise sur les frais. Le Fee Test le signale à l'utilisateur (palier actuel, économie potentielle). Option plus tard : achat automatique d'une petite quantité du token pour couvrir/réduire les frais — nécessite l'exécution réelle, donc hors lecture seule. Les remises et seuils sont à vérifier par exchange.
- **Liens stratégie / provider / actif : points ouverts.** Nom définitif remplaçant « binding » ; clé du lien (provider, actif) vs (wallet, actif) ; `Strategy` comme entité distincte (aujourd'hui `RainbowLivePreset.mode`) ; état moteur indépendant par lien (aujourd'hui partagé par preset). Cf. [`../etudes/etude-liens-strategie-provider-actif.md`](../etudes/etude-liens-strategie-provider-actif.md) §6.

## 8bis. Source des candles du Rainbow : limites assumées

Signal par lien sur l'exchange du wallet, D1 natif sur 720 j ([`../architecture/04-market-data.md`](../architecture/04-market-data.md#chemin-d1-natif-rainbow-getdailydatasetforasset), [`../architecture/08-rainbow-live.md`](../architecture/08-rainbow-live.md)). Limites connues :
- **720 j d'historique** : suffit pour l'ATH de BTC, ETH, PAXG. Un actif sans ATH depuis 720 j est considéré mort. Si un actif exige plus de 720 j, il faudra agir ; piste prévue : quand plus que ce qu'un exchange sert est demandé, basculer les bougies manquantes sur Binance (meilleur historique). Piège : cotations différentes (`PAXGUSDT` vs `PAXGUSD`), un ATH tombant dans la partie Binance contaminerait le signal de l'autre exchange ; recalage des prix au raccord à étudier.
- **Trend Mix** : machine d'état unique par preset, signal partagé entre ses liens (WARN + lien marqué substitué si les sources diffèrent). L'ATH de référence du Trend Mix (`rainbow_ath_reference`, par actif) reste amorcé sur l'historique complet Binance, pas sur la source du lien.
- **Page web** : `candleSource` / `candleSourceSubstituted` sont persistés sur le snapshot du lien mais pas encore affichés.

## 9. Jonglage entre actifs (idée future, non faite)

Au lieu de repasser par USDC, utiliser un actif comme monnaie de cotation d'un autre : acheter BTC avec le Rainbow BTC/USDC, puis PAXG avec le Rainbow **PAXG/BTC** (pas PAXG/USDC). Complexe (capital commun multi-cotations, presets sur ratios) : après la passerelle non stable ([`../etudes/etude-passerelle-actif-non-stable.md`](../etudes/etude-passerelle-actif-non-stable.md)).

Point PAXG (2026-10-12) : pour l'instant le signal et l'exécution PAXG restent sur PAXG/USDC (route `USDC -> BTC -> PAXG` déjà complète). Lire le signal sur PAXG/BTC (cotation de la jambe 2) est un dilemme à traiter dans une étude à part entière ; le Rainbow PAXG se comportant très différemment selon le provider (constat TradingView), cette étude suppose d'abord la source de candles propre au lien ([`../etudes/etude-source-candles-provider-du-lien.md`](../etudes/etude-source-candles-provider-du-lien.md)).

## 10. Onboarding : limites assumées
- **Carry** : changer le `baseAmount` d'un lien ne remet pas le carry du plan d'ordres à zéro.
- **Déclenchement** : la liaison est un bouton (carte « Démarrage ») ; la sauvegarde d'une clé TRADE ne lie pas automatiquement.
- **Bande** : proposition neutre (pas de profil utilisateur) ; pas de borne par le minimum du catalogue, seulement un avertissement `BELOW_MIN`.

## 11. Chaîner les traitements quotidiens plutôt que des crons indépendants (étude à faire)

Idée de Clem (2026-10-19). Aujourd'hui la chaîne quotidienne repose sur des crons séparés : passe Rainbow `tradeio.rainbow-live.pass-2355-cron` (23:55 UTC), plan d'ordres `tradeio.execution.plan-cron` (23:58), exécution `tradeio.execution.run-cron` (désactivé). Chaque étape est calée sur une heure fixe, sans savoir si la précédente est terminée.

Question : vaut-il mieux enchaîner les étapes (la fin de l'une déclenche la suivante) ?

Risque à étudier : si une étape devient plus longue (plus d'actifs, de liens, de providers, candles lentes ; la passe prend déjà ~8 s pour 3 presets), l'étape suivante peut démarrer alors que la précédente n'est pas finie et lire des runs incomplets. Les plans d'ordres expirent 5 min après leur création (`plan-ttl=PT5M`) : un enchaînement par cron doit donc rester dans cette fenêtre, ce que le chaînage garantirait mieux.

À couvrir par l'étude : orchestrateur unique ou événements, comportement en cas d'échec d'une étape (arrêt de la chaîne ou poursuite), reprise, idempotence, observabilité, lien avec le kill switch et le `DecisionOrchestrator` (cron 06:00).
