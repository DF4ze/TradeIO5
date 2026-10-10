# Rainbow DCA ATR — chantiers ouverts

Vérifié le : 2026-10-09 (code + décisions de Clem). Objectif n°1 du projet : **automatiser le DCA intelligent** (achats/ventes réels). Cette page liste ce qui sépare l'état actuel de cet objectif. Sauf mention « fait », rien ici n'est implémenté.

## État de la chaîne

| Brique | État |
|---|---|
| Moteur Rainbow ATR (pine v4 = source de vérité, port Java) | fait — découpé en couches L1 bornes / L2 `step` pur + `RainbowAtrState` / L3 `AthReference` / L4 `Sizer` (référence seulement) ; état et ATH persistés pour les presets `TREND_MIX` du bench (`RainbowLiveEngineState`, `RainbowAthReference`) — [`04-market-data.md`](../architecture/04-market-data.md) |
| Paramétrage par actif et par trend (Bull / Bear) | fait pour BTC, ETH, PAXG : presets `<ACTIF> Perso Bear/Bull` du pine v4 repris en Java ; templates de presets système (copiés par user, inactifs) = jeu global du bench + Trend Mix aux réglages par défaut. Réglages PAXG ajustés à la main par Clem |
| Trend simple (`TrendAnalyzer`, UP/DOWN/RANGE) | fait — [`09-trend.md`](../architecture/09-trend.md) |
| Branchement Trend → jeu de paramètres | fait : Trend Mix (`TrendMixCalculator` = méga-pine `rainbow_trend_dca_v1.pine`, vérifié par Clem Java vs pine) choisit Bull/Bear à chaque bougie (`RainbowSetSelector`) ; état de la machine et ATH persistés ; branché au dry-run quotidien (presets `TREND_MIX`, [`08`](../architecture/08-rainbow-bench-grandeur-nature.md)), pas à une exécution réelle |
| Bench grandeur nature (dry-run quotidien, wallet fictif, page web) | fait, tourne chaque jour sur le VPS (résultats de tous les presets en base) — [`08`](../architecture/08-rainbow-bench-grandeur-nature.md) |
| Recréation d'une base (Flyway + initializers) | fait : `V1__init.sql` généré (non activé), défauts créés par les initializers, presets système par templates — [`operations/flyway.md`](../operations/flyway.md). données historiques (`candle`, `etf_flow_snapshot`) sauvegardées chaque semaine et rechargées au démarrage. **Ouvert** : sauvegarde récurrente de la branche décisionnelle (branche en pause) |
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

## 6. Preset Bull PAXG

Constat du 2026-10-05 : trop de ventes, le bag ne gonfle pas. Réglage manuel par Clem (pas de chantier de code).

## 6b. Config par défaut pilotable par System (non fait)

Constat (code, 2026-10-09) : les presets système sont des **copies** d'un template, faites une fois par utilisateur (`RainbowLivePresetService#ensureSystemPresets`), jamais recréées ni modifiées ; le template est immuable et un défaut changé dans le code n'altère pas la base. Changer la config par défaut ne change donc rien chez les utilisateurs existants.
Besoin (Clem) : une config par défaut **pilotable par le compte System** et qui s'applique à tous les utilisateurs ayant choisi d'utiliser la config par défaut (au lieu d'une copie figée). Piste à étudier : preset de l'utilisateur en mode « suit le défaut » (référence au template/preset System, pas de copie des paramètres), template modifiable par System avec effet immédiat sur les runs suivants, historique des runs conservant le snapshot de config (`configHash`/`changedParams` déjà en place). Questions : le wallet mock et l'historique restent propres à chaque user (oui ?) ; un user peut-il « détacher » (copie éditable) ; effet sur les runs déjà écrits ; interaction avec Flyway/initializers (le défaut vit en base, plus dans le code). Analyse à mener avant tout code.

## 7. Interface utilisateur : modes et alertes (intentions, pas de code)

- **Modes de réglage** par utilisateur (choix stocké en base, sélecteur déjà présent sur la page) : *Expert* (tous les paramètres, Trend Mix et jeux Bear/Bull éditables) est livré ; restent *Auto*, *Simple* (curseurs seulement) et *Avancé* (paramètres les plus influents). Un bench en ligne (comparable au pinescript sous TradingView) est envisagé pour chacun des modes.
- **Alerte utilisateur** quand le portefeuille devient pleinement exposé (plus de stablecoin disponible) : inviter à ajouter des fonds. Dépend du vrai wallet et du curseur d'exposition.

## 8. Fee Test et jugement des benchs utilisateur (intentions, pas de code)

- **Fee Test** : quand un utilisateur choisit une paire sur un exchange / une connexion, un test de frais estime leur hauteur (frais + spread) ; au-dessus d'un seuil ⇒ warning à l'utilisateur.
- Extensible aux « Bench online » ; à terme, un système qui juge les benchs des utilisateurs, surtout ceux à PnL négatif.
- Contexte chiffré : [`etudes/etude-trading-stablecoin-frais-btc-eth-paxg.md`](../etudes/etude-trading-stablecoin-frais-btc-eth-paxg.md).
- **Token de l'exchange** (OKB chez OKX, KRAKEN chez Kraken…) : détenir ce token donne souvent une remise sur les frais. Le Fee Test le signale à l'utilisateur (palier actuel, économie potentielle). Option plus tard : achat automatique d'une petite quantité du token pour couvrir/réduire les frais — nécessite l'exécution réelle, donc hors lecture seule. Les remises et seuils sont à vérifier par exchange.
