# Rainbow DCA ATR — chantiers ouverts

Vérifié le : 2026-10-04 (code + décisions de Clem du jour). Objectif n°1 du projet : **automatiser le DCA intelligent** (achats/ventes réels). Cette page liste ce qui sépare l'état actuel de cet objectif. Sauf mention « fait », rien ici n'est implémenté.

## État de la chaîne

| Brique | État |
|---|---|
| Moteur Rainbow ATR (pine v4 = source de vérité, port Java) | fait — [`04-market-data.md`](../architecture/04-market-data.md) |
| Paramétrage par actif et par trend (Bull / Bear) | **manuel** : presets `<ACTIF> Perso Bear/Bull` du pine v4 ; dans le code Java, les presets par défaut restent le jeu global du bench |
| Trend simple (`TrendAnalyzer`, UP/DOWN/RANGE) | fait — [`09-trend.md`](../architecture/09-trend.md) |
| Trend double (globale lente + locale) | non implémenté |
| Branchement Trend → jeu de paramètres | **non fait** |
| Bench grandeur nature (dry-run quotidien, wallet fictif, page web) | fait, tourne chaque jour sur le VPS (résultats de tous les presets en base) — [`08`](../architecture/08-rainbow-bench-grandeur-nature.md) |
| Curseur d'exposition | non fait — [spec V1 §2](../etudes/spec-rainbow-v1-trend-global-exposition-risque-macro.md) |
| Risque macro (`riskCursor`) | non fait — spec V1 §3 |
| Wallet réel dans le calcul (`WalletSnapshot`) | non fait — [`decision-to-order-gap.md`](decision-to-order-gap.md) |
| Exécution d'ordres réels | non fait — idem |

## 1. Paramétrage automatique d'un actif quelconque (priorité moindre)

Besoin : pouvoir ajouter n'importe quel actif, avec un système de paramétrage intelligent derrière. Constat : le bench d'optimisation automatique (`RainbowAtrRegimeBench`, coordinate ascent) donne des résultats décevants (paysage non monotone, ~30 paramètres couplés, jeux dégénérés « ne vend jamais ») ; les jeux Bull/Bear de BTC, ETH et PAXG (Kraken) ont été trouvés à la main sous TradingView. Le bench n'est donc **pas** la source des paramètres ([`calibration-rainbow-atr-v3-regime.md`](../calibration/calibration-rainbow-atr-v3-regime.md)). Approche de remplacement : à définir. Matière disponible : le testeur live enregistre chaque jour le résultat de tous les presets.

## 2. Branchement de la Trend sur le Rainbow ATR (priorité n°1)

- Il faut un jeu de paramètres **par trend, Bull et Bear seulement** (pas de Sideways) ; l'ancien raisonnement « un jeu global par actif suffit » est abandonné.
- `TrendAnalyzer` produit 3 états (UP/DOWN/RANGE) : mapping de `RANGE` vers Bull ou Bear à définir.
- Un « double indicateur » (global lent + local réactif, 2×2 = 4 combinaisons) avait été envisagé pour limiter le retard ; il n'existe pas dans le code et on ne sait pas comment le raccorder à 2 jeux Bull/Bear (ou à 4 jeux ATR).
- Décision de la spec V1 (2026-09-25) à reconfirmer : le jeu actif est réévalué chaque jour, y compris pendant un armement en cours (pas de jeu figé à l'armement).
- Validation : pas de bench fiable ; pistes disponibles = rejeu pine sous TradingView et testeur live.
- Le retrait de la sélection par Trend dans le pine (2026-10-03) ne vaut plus comme décision d'orientation.

## 3. Curseur d'exposition

Intention (pas de code) : cible d'exposition crypto/stablecoin pilotée par l'utilisateur, agissant sur les quantités achetées **et** vendues ; l'écart exposition voulue / réelle accélérerait achats ou ventes via les facteurs buy/sell. Impose un vrai wallet (aujourd'hui wallet fictif USDC par preset, qui diverge de la position du moteur) et, à terme, un capital commun à tous les actifs. Un achat et une vente simultanés sur la même bougie sont aujourd'hui impossibles (la vente l'emporte) et pourraient devenir possibles.

## 4. Risque macro

Intention (pas de code) : `riskCursor` (0-10, persisté, orphelin) comme gain sur un score de risque marché à construire. Voir spec V1 §3 ; pas d'historique macro rejouable pour un backtest.

## 5. Wallet réel et exécution

Hors Rainbow : branchement `WalletSnapshot`, sizing réel, composant d'exécution (dry-run obligatoire avant activation) — [`decision-to-order-gap.md`](decision-to-order-gap.md).
