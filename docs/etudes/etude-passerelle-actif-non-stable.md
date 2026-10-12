# Étude — Passerelle par actif non stable (PAXG sur Kraken), USDT exclu

Date : 2026-10-12. Statut : analyse, aucun code, aucun ordre. Suite de `etude-chemin-achat-execution.md`. Prompt d'origine : `prompts/prompt-analyse-passerelle-actif-non-stable.md`.

## Décisions de Clem (2026-10-12)
- Fiat interdit ; **USDT exclu** (groupe `USD` conservé, USDC seul) ; vente en USDC (caduque : « vente forcée USDT », « stock USDT »).
- Intermédiaire : **BTC seul choisi** ; ETH affichée comme route, jamais choisie automatiquement.
- Vente PAXG : `PAXG -> BTC -> USDC` (retour en USDC, intermédiaire jamais conservé).
- Échec de la jambe 2 : **retry borné** (re-devis à chaque essai) puis **stock en transit** (jamais d'ordre compensatoire) ; le plan suivant termine la jambe 2 avant tout nouvel achat.
- Taille DCA PAXG typique < 100 $.
- Fiscalité : crypto->crypto jugé non assimilé au fiat = hypothèse, « pas de souci pour l'instant » (à confirmer plus tard, dont PAXG).

## Faits Kraken (API publique, 2026-10-12)
`XBTUSDC` ordermin 0,00005 / costmin 0,5 ; `ETHUSDC` 0,001 / 0,5 ; `PAXGXBT` 0,001 PAXG / costmin 0,00002 BTC ; `PAXGETH` 0,001 / 0,0002 ETH ; lot 8 décimales ; toutes `online`. `AssetPairs.fees` vide : frais à lire par `TradeVolume`.

## Chiffrage (carnets réels, coût total %, taker identique sur les jambes)
| Montant | BTC @0,08 % | ETH @0,08 % | BTC @0,40 % | ETH @0,40 % |
|---|---|---|---|---|
| 10-100 $ | 0,200 | 0,648 | 0,84 | 1,29 |
| 1000 $ | 0,227 | 0,653 | 0,87 | 1,29 |
| 5000 $ | 0,294 | 0,699 | 0,93 | 1,34 |

Vente BTC 0,200-0,252 %, ETH 0,648-0,712 % (@0,08 %). Spread `PAXG/ETH` 0,975 % ; `PAXG/XBT` 0,078 %. Inconnue majeure : taux taker réel du compte (0,08 % étude du 11/10, non vérifié) : BTC pile au seuil WARNING à 0,08 %, ROUGE à 0,40 %.

## Écarts code à lever
- `PathFinder` : `accepts` (cotation stable), `sz = montantUSD/prix` (unités mélangées), notionnel de `BookSlippage`, `marketCache` sans montant.
- Kraken : ni `TradingFeeProvider` ni `OrderBookClient` (path-quote Kraken impossible aujourd'hui).
- `BindingCheck`, `OrderPlanner` (achat/vente/`noStablePair`), `RealPortfolioSource` (NOT_TRADABLE) à généraliser ; `OrderExecutor` réutilisable (chaîne dimensionnée sur le reçu net).
- Position du lien BTC/ETH sur Kraken = solde réel − stock en transit ; sources d'achat = cash USDC + stock en transit, jamais la position d'un lien.
- Statut `TRADABLE_VIA_BRIDGE` -> `TRADABLE_VIA_INTERMEDIARY` ; lien mémorise `routeKey`.

## Feuille de route (2 lots, validée le 2026-10-12 ; Lot 1 et Lot 2 codés, 1er achat réel PAXG exécuté)
- **Lot 1 (lecture seule + dry-run)** : retrait USDT (groupe USDC seul, nœud interdit, vente USDC, retrait `FORCED_SELL_QUOTE`/`preferredQuote`/pont stable->stable, SQL manuel, tests, docs) + clients Kraken lecture seule (`TradeVolume`, `Depth`) + `PathFinder`/`FeeTest` avec intermédiaires + `path-quote` PAXG chiffré + `BindingCheck` (`TRADABLE_VIA_INTERMEDIARY`) + plan dry-run multi-jambes (achat/vente), stock en transit, `routeKey`.
- **Lot 2 (réel + page)** : retry borné, `validate=true` par jambe, ordre réel minimal sous contrôle de Clem, page (routes côte à côte, route liée).

## Idée future (non retenue maintenant, complexité)
Faire « jongler » les actifs entre eux au lieu de repasser par USDC : Rainbow lu sur BTC/USDC pour acheter du BTC, puis sur **PAXG/BTC** (et non PAXG/USDC) pour PAXG ; le BTC servirait de monnaie de cotation. Suppose un capital commun multi-cotations et des presets sur ratios. Consigné dans `known-gaps/rainbow-dca-chantiers-ouverts.md`.
