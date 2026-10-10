# Rainbow DCA ATR — chantiers ouverts

Vérifié le : 2026-10-09 (code + décisions de Clem). Objectif n°1 du projet : **automatiser le DCA intelligent** (achats/ventes réels). Cette page liste ce qui sépare l'état actuel de cet objectif. Sauf mention « fait », rien ici n'est implémenté.

## État de la chaîne

| Brique | État |
|---|---|
| Moteur Rainbow ATR (pine v4 = source de vérité, port Java) | fait — découpé en couches L1 bornes / L2 `step` pur + `RainbowAtrState` / L3 `AthReference` / L4 `Sizer` (référence seulement) ; état et ATH persistés pour les presets `TREND_MIX` du bench (`RainbowLiveEngineState`, `RainbowAthReference`) — [`04-market-data.md`](../architecture/04-market-data.md) |
| Paramétrage par actif et par trend (Bull / Bear) | fait pour BTC, ETH, PAXG : presets `<ACTIF> Perso Bear/Bull` du pine v4 repris en Java ; stratégies Actif (une Trend Mix par actif, pilotées par System, suivies par les presets des users, inactifs au départ) aux réglages par défaut. Réglages PAXG ajustés à la main par Clem |
| Trend simple (`TrendAnalyzer`, UP/DOWN/RANGE) | fait — [`09-trend.md`](../architecture/09-trend.md) |
| Branchement Trend → jeu de paramètres | fait : Trend Mix (`TrendMixCalculator` = méga-pine `rainbow_trend_dca_v1.pine`, vérifié par Clem Java vs pine) choisit Bull/Bear à chaque bougie (`RainbowSetSelector`) ; état de la machine et ATH persistés ; branché au dry-run quotidien (presets `TREND_MIX`, [`08`](../architecture/08-rainbow-bench-grandeur-nature.md)), pas à une exécution réelle |
| Bench grandeur nature (dry-run quotidien, wallet fictif, page web) | fait, tourne chaque jour sur le VPS (résultats de tous les presets en base) — [`08`](../architecture/08-rainbow-bench-grandeur-nature.md) |
| Recréation d'une base (Flyway + initializers) | fait : `V1__init.sql` généré (non activé), défauts créés par les initializers, stratégies Actif pilotées par System (presets qui les suivent) — [`operations/flyway.md`](../operations/flyway.md). données historiques (`candle`, `etf_flow_snapshot`) sauvegardées chaque semaine et rechargées au démarrage. **Ouvert** : sauvegarde récurrente de la branche décisionnelle (branche en pause) |
| Curseur d'exposition | non fait — [spec V1 §2](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md) |
| Risque macro (`riskCursor`) | non fait — spec V1 §3 |
| Wallet réel (lecture seule) branché au preset live | code et page faits (connecteurs, bindings, moteur sur vrai cash, page) — **vérification réelle OKX/Kraken à faire**, cf. §5 ; `WalletSnapshot` (branche décisionnelle) non branché — [`decision-to-order-gap.md`](decision-to-order-gap.md) |
| Exécution d'ordres réels | non fait — idem |

## 1. Paramétrage automatique d'un actif quelconque (priorité moindre)

Besoin : pouvoir ajouter n'importe quel actif, avec un système de paramétrage intelligent derrière. Constat : le bench d'optimisation automatique (`RainbowAtrRegimeBench`, coordinate ascent) donne des résultats décevants (paysage non monotone, ~30 paramètres couplés, jeux dégénérés « ne vend jamais ») ; les jeux Bull/Bear de BTC, ETH et PAXG (Kraken) ont été trouvés à la main sous TradingView. Le bench n'est donc **pas** la source des paramètres ([`calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md)). Approche testée puis abandonnée (2026-10-09) : bench automatique du Trend Mix (grille + walk-forward) et recherche automatique des jeux Bull/Bear, tous deux sans edge robuste hors échantillon — verdict dans [`etudes/etude-bench-auto-trend-mix-nouvel-actif.md`](../etudes/etude-bench-auto-trend-mix-nouvel-actif.md) §7. Un nouvel actif se règle donc à la main (Trend Mix par défaut puis jeux Bull/Bear sous TradingView) ; le harnais `TrendMixBenchMain` ([`09-trend.md`](../architecture/09-trend.md)) contrôle la qualité de la Trend.

## 2. Trend Mix : constantes à valider par actif

Le branchement Trend → jeux Bull/Bear est fait (cf. tableau). Décisions : Trend double **abandonnée** (2026-10-09) ; `RANGE` n'est pas un switch (la dernière direction décisive de la régression est mémorisée, repli sur le régime de la SMA) ; le jeu actif est réévalué chaque jour, y compris pendant un armement en cours.

Reste : les constantes du Trend Mix ont été réglées à la main sur BTC et servent par défaut à ETH et PAXG ; à valider par actif à l'œil sous le pine (le bench automatique n'apporte pas d'edge, cf. §1).

## 3. Curseur d'exposition

Intention (pas de code) : cible d'exposition crypto/stablecoin pilotée par l'utilisateur, agissant sur les quantités achetées **et** vendues ; l'écart exposition voulue / réelle accélérerait achats ou ventes via les facteurs buy/sell. Impose un vrai wallet (aujourd'hui wallet fictif USDC par preset, qui diverge de la position du moteur) et, à terme, un capital commun à tous les actifs. Un achat et une vente simultanés sur la même bougie sont aujourd'hui impossibles (la vente l'emporte) et pourraient devenir possibles.

## 4. Risque macro

Intention (pas de code) : `riskCursor` (0-10, persisté, orphelin) comme gain sur un score de risque marché à construire. Voir spec V1 §3 ; pas d'historique macro rejouable pour un backtest.

## 5. Wallet réel et exécution

Hors Rainbow : branchement `WalletSnapshot`, sizing réel, composant d'exécution (dry-run obligatoire avant activation) — [`decision-to-order-gap.md`](decision-to-order-gap.md).

**Wallet réel du preset live — reste à faire** (lecture seule, aucun ordre) :
- **Vérification réelle avec Clem** : créer la clé OKX « Read » (IP du VPS whitelistée, passphrase) dans `application-*.properties` ; lire BTC/ETH/USDC (OKX, compte Trading) et PAXG/USDC (Kraken, actifs `.F` éventuels, nom de paire `PAXGUSDC` à confirmer) ; ouvrir la page et comparer wallet réel / dernier snapshot au solde réel ; basculer le preset live et `bagPercent` et constater leur prise en compte à la passe suivante ; cas BLOCKED / UNAVAILABLE / STALE en conditions réelles.
- **Page** : le rendu JS n'est pas testé en navigateur (tests Java : API + conteneur + absence d'`innerHTML`) ; la création d'un binding n'est possible que par l'API (pas de formulaire, pas de liste des wallets).
- **Clôture du lot** : après le « Point » avec Clem, la roadmap `wallet-reel` reste en `docs/prompts/` (trace de la réflexion) ; les prompts d'implémentation exécutés sont déjà supprimés.
- **Hors lot** (plus tard) : Fee Test, token d'exchange, découverte automatique du wallet, alerte % de liquidité (§7), compte Funding OKX / Earn.

## 5b. Pages, navigation, charte graphique et saisie des credentials (non fait)

Ordre voulu : d'abord boucler le fonctionnel du back, puis :
- **Architecture des pages** : définir les pages, leur organisation, les menus.
- **Charte graphique** à définir.
- **Page de saisie des credentials d'exchange** (et des wallets) : indispensable, car elles sont rattachées à l'utilisateur et ne doivent pas vivre dans les `application-*.properties` (il faudrait les modifier pour chaque user). Aujourd'hui seul `ApiCredentialInitializer` (propriétés → base, pour OKlm, au démarrage, si absente) ou le SQL direct permettent de les renseigner ; l'application ne peut pas tourner sans ces credentials pour les tests fonctionnels de dev.

## 5c. Chemin d'achat via passerelle USDT et groupes d'actifs (vérifié le 2026-10-10)

- **Constat** : PAXG n'a aucune paire spot en USDC/USDT sur Kraken (seulement USD, EUR, XBT, ETH) ⇒ non tradable sans passer par une monnaie fiat, refusé (taxation). Sur OKX : `PAXG-USDC` absente, `PAXG-USDT` et `USDC-USDT` existent ⇒ achat via une passerelle USDT.
- **Déjà en place** (groupes d'actifs, cf. [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md)) : cash = USDC + USDT, Home agrégée, statuts `OK` / `TRADABLE_VIA_BRIDGE` / `NOT_TRADABLE_WITHOUT_FIAT` persistés sur le binding, `ExecutionGuard`. Attendu en réel : Kraken PAXG ⇒ `NOT_TRADABLE_WITHOUT_FIAT`, OKX PAXG ⇒ `TRADABLE_VIA_BRIDGE`, BTC/ETH ⇒ `OK` (à confirmer avec Clem).
- **Lot B, étape a faite** (catalogue d'instruments, frais réels `trade-fee`, carnet, `PathFinder`, Fee Test, `GET …/bindings/{id}/path-quote` ; cf. [`../architecture/08-rainbow-bench-grandeur-nature.md`](../architecture/08-rainbow-bench-grandeur-nature.md)) : le chemin le moins coûteux est chiffré en lecture seule. 
- **Lot B, étape b faite** : plan d'ordres dry-run enregistré et affiché (job `ExecutionPlanJob` désactivé par défaut, bilan virtuel USDC/USDT, achats + ventes, `clOrdId` déterministes, expiration 5 min, mode global `OFF|DRY_RUN`, `LIVE` refusé au démarrage) — [`../architecture/10-execution-plan.md`](../architecture/10-execution-plan.md). 
- **Lots C1 (back-end) et C2 (panneau d'exécution sur la page) faits** : clé TRADE chiffrée, port d'ordres + client OKX (inactif par défaut), exécuteur avec réconciliation, audit append-only, kill switch, plafonds, double validation du 1ᵉʳ ordre — [`../architecture/10-execution-reelle.md`](../architecture/10-execution-reelle.md). **Aucun ordre réel envoyé, `LIVE` verrouillé.** **Reste** (roadmap `docs/prompts/prompt-roadmap-chemin-achat-execution.md`) : étape d (création de la clé OKX Trade, déverrouillage avec Clem, 1ᵉʳ ordre minimal), étape e ; chiffrement optionnel des clés READ ; stock USDT conservé à la vente pour les achats suivants.

## 6. Preset Bull PAXG

Constat du 2026-10-05 : trop de ventes, le bag ne gonfle pas. Réglage manuel par Clem (pas de chantier de code).

## 6b. Stratégies Actif : gestion System et Stratégie Wallet (reste à faire)

Fait : la config par défaut est pilotée par System via la stratégie Actif (cf. [`architecture/08`](../architecture/08-rainbow-bench-grandeur-nature.md)). Reste :
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
