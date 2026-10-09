# Etude trading stablecoin -> BTC / ETH / PAXG : frais, liquidite, API

Date de demarrage : 2026-10-08.

Statut : recherche en cours. Ce document est une etude de decision, pas une reference d'architecture.

## Objectif

Identifier la meilleure solution actuelle pour acheter/vendre BTC, ETH et PAXG depuis un stablecoin avec le cout total le plus bas possible, en tenant compte de :

- frais maker/taker reels ;
- spread et liquidite observable des carnets ;
- disponibilite des paires spot et de l'API ;
- risque du stablecoin utilise ;
- complexite d'integration dans TradeIO5.

Le cout a comparer n'est pas uniquement le fee schedule affiche : pour un ordre donne, le cout pertinent est :

```text
cout_total = frais_trading + spread_effectif + slippage + frais_conversion_stablecoin + frais_depot/retrait eventuels
```

## Synthese provisoire au 2026-10-09

Nouvelle contrainte utilisateur du 2026-10-09 : **Binance est exclu**. Les chiffres Binance restent dans cette etude comme benchmark et pour comprendre le manque a gagner, mais ils ne doivent plus piloter la decision cible.

La decision actuelle se resume ainsi :

| Profil | Stablecoin de reserve | Route BTC/ETH | Route PAXG | Verdict |
|---|---|---|---|---|
| Sans Binance, USDT accepte | USDT pour execution, USDC possible pour parking | OKX `BTC/USDT`, `ETH/USDT` | KuCoin/OKX/Bitget `PAXG/USDT` selon devis | Meilleur chemin non-Binance chiffre aujourd'hui ; cout spot autour de 10-11 bps hors retraits, mais risque MiCA/USDT a accepter explicitement. |
| Sans Binance, USDT refuse / MiCA strict | USDC/EURC | OKX `BTC/USDC`, `ETH/USDC` ou fiat regule | Aucun PAXG stablecoin competitif prouve ; Kraken utile si PAXG deja detenu | Le point bloquant est l'achat PAXG depuis stablecoin : Gemini `PAXG/USDC` existe mais spread prohibitif ; Bitpanda Fusion doit etre teste avec cle API. |
| Wrapped/on-chain accepte | USDC on-chain, idealement Base/Solana | `cbBTC` sur Base/Solana ou `WETH` sur Base si l'exposition wrapped est acceptable | PAXG officiel sur Solana via routeur, a valider par quote | Piste la plus interessante pour contourner les frais CEX/retrait, surtout BTC ; ajoute risque issuer/bridge/smart-contract et demande des quotes routeur juste avant ordre. |
| Retrait systematique apres chaque achat | USDC ou actif final | A recalculer avec frais de retrait | A recalculer avec frais de retrait | Impossible de conclure sans devis retrait authentifie ; les frais reseau peuvent dominer un petit DCA. |

Decision courte a ce stade :

- **Sans Binance, le meilleur cout complet spot passe probablement par USDT**, car PAXG n'a pas de route USDC liquide hors Binance.
- **Si USDT est accepte : OKX pour BTC/ETH, puis KuCoin/OKX/Bitget pour PAXG selon le carnet juste avant ordre.**
- **Si USDT est refuse : il faut soit tester Bitpanda Fusion avec cle API, soit relacher "stablecoin" vers USD/EUR regules sur Kraken/Coinbase, soit accepter un cout Gemini PAXG/USDC tres eleve.**
- **USDC reste le meilleur stablecoin de reserve prudent**, mais sans Binance il ne suffit pas a executer PAXG a bas cout.
- **Si du PAXG est deja chez Kraken : ne pas le bouger par defaut.** Kraken devient pertinent comme custody/revente fiat ou PAXG existant, meme s'il n'est pas optimal pour acheter du nouveau PAXG depuis stablecoin.
- **La piste wrapped/on-chain est reelle, surtout `USDC -> cbBTC` sur Base/Solana.** Pour PAXG, le signal le plus interessant n'est pas un wrapped non officiel : c'est le PAXG officiel Paxos sur Solana, avec frais reseau faibles mais liquidite encore a surveiller.

Dernier snapshot public rejoue le 2026-10-08 a 19:18 Europe/Paris :

- Binance `BTC/USDC` avec paiement BNB : 7,126 bps pour 1k et 5k.
- Binance `ETH/USDC` avec paiement BNB : 7,146 bps pour 1k et 5k.
- Binance `PAXG/USDT` avec paiement BNB : 7,512 bps pour 1k ; 8,017 bps pour 5k.
- Binance `PAXG/USDC` avec paiement BNB : 7,501 bps pour 1k ; 9,486 bps pour 5k.
- Route `USDC -> USDT -> PAXG` Binance avec BNB : 14,687 bps pour 1k ; 15,192 bps pour 5k.

Lecture : si la reserve est deja en USDC, convertir vers USDT pour acheter PAXG n'est pas rentable aux tailles testees. L'avantage du carnet `PAXG/USDT` ne compense pas le second fee de trading.

Controle de stabilite rejoue a 23:21-23:22 Europe/Paris avec `--samples 3 --interval-seconds 20 --sizes 50,1000,5000` :

| Route | Cout 1k min/median/max | Cout 5k min/median/max | Lecture |
|---|---:|---:|---|
| Binance `PAXG/USDT`, BNB | 7,512 / 7,512 / 7,512 bps | 7,518 / 7,518 / 7,519 bps | Meilleur cout pur et carnet stable sur le mini-run. |
| Binance `PAXG/USDC`, BNB | 8,618 / 8,618 / 8,618 bps | 8,753 / 8,753 / 8,753 bps | Meilleure route USDC directe, mais clairement derriere USDT sur ce run. |
| Binance `USDC -> USDT -> PAXG`, BNB | 14,687 / 14,687 / 14,687 bps | 14,693 / 14,693 / 14,694 bps | Toujours non competitif face au direct `PAXG/USDC` si la reserve est USDC. |
| OKX `PAXG/USDT`, regular X-Perps | 10,137 / 10,162 / 10,163 bps | 11,126 / 11,190 / 11,209 bps | Bon fallback USDT hors Binance, mais plus cher. |
| KuCoin `PAXG/USDT`, VIP0 base | 10,012 / 10,012 / 10,139 bps | 10,404 / 10,404 / 10,538 bps | Meilleur fallback PAXG hors Binance sur ce mini-run. |
| Bitget `PAXG/USDT`, base | 10,257 / 10,327 / 10,411 bps | 11,554 / 11,576 / 11,657 bps | Fallback correct, mais derriere KuCoin ici. |
| Crypto.com `PAXG/USDT`, Level 1 public | 50,012 / 50,012 / 50,012 bps | 51,038 / 51,040 / 51,043 bps | Carnet correct mais frais retail publics disqualifiants. |

Limite : trois samples ne prouvent pas la stabilite intraday ; ils reduisent seulement le risque de conclure sur un carnet aberrant au tick unique.

Snapshot de routage rejoue a 19:18 Europe/Paris avec le bloc "Best route by profile" de l'outil :

| Profil | BTC | ETH | PAXG 1k | PAXG 5k | Lecture |
|---|---|---|---|---|---|
| Cout minimal stablecoin | Binance `BTC/USDC` 7,126 bps | Binance `ETH/USDC` 7,146 bps | Binance `PAXG/USDC` 7,501 bps | Binance `PAXG/USDT` 8,017 bps | La meilleure route PAXG change selon taille/profondeur : USDC gagne a 1k, USDT gagne a 5k. |
| UE prudent USDC | Binance `BTC/USDC` 7,126 bps | Binance `ETH/USDC` 7,146 bps | Binance `PAXG/USDC` 7,501 bps | Binance `PAXG/USDC` 9,486 bps | Route coherente si USDC est la reserve principale. |
| Fallback USDT sans Binance | OKX `BTC/USDT` 10,006 bps | OKX `ETH/USDT` 10,021 bps | Bitget `PAXG/USDT` 10,012 bps | Bitget `PAXG/USDT` 10,012 bps | Fallback utilisable ; la meilleure venue peut differer selon l'actif/taille. |

Conclusion pratique : **il ne faut pas coder une route PAXG fixe**. TradeIO5 doit recalculer un devis juste avant l'ordre et comparer au moins `PAXG/USDC` vs `PAXG/USDT` quand les deux sont autorises.

Verification supplementaire a 19:18 Europe/Paris avec `--sizes 50,1000,5000` : la ladder `LOWEST_COST_STABLECOIN` choisit Binance `PAXG/USDC` a 50 et 1 000, puis Binance `PAXG/USDT` a 5 000. En profil `EU_CONSERVATIVE_USDC`, Binance `PAXG/USDC` reste entre 7,137 bps a 50 et 9,486 bps a 5 000. Ce n'est pas une contradiction avec les snapshots precedents ; c'est le comportement attendu d'un carnet PAXG plus fin : la route optimale peut changer en quelques minutes.

### Recalcul sans Binance

Snapshot rejoue le 2026-10-09 a 00:01 Europe/Paris, apres exclusion de Binance :

| Profil sans Binance | BTC 1k/5k | ETH 1k/5k | PAXG 1k | PAXG 5k | Lecture |
|---|---:|---:|---:|---:|---|
| USDT accepte | OKX `BTC/USDT` 10,006 / 10,006 bps | OKX `ETH/USDT` 10,020 / 10,020 bps | KuCoin `PAXG/USDT` 10,180 bps | KuCoin `PAXG/USDT` 10,945 bps | Meilleur profil non-Binance observe, mais depend d'USDT. |
| USDC reserve -> USDT -> PAXG | - | - | Bitget 20,474 bps ; KuCoin 20,679 bps ; OKX 20,886 bps | OKX 21,411 bps ; KuCoin 21,444 bps ; Gate.io 21,796 bps | Convertir USDC en USDT juste pour PAXG coute environ 2x le direct USDT. |
| USDC strict hors Binance | OKX `BTC/USDC` 10,006 / 10,006 bps | OKX `ETH/USDC` 10,412 / 10,800 bps | Gemini `PAXG/USDC` : impact marche seul ~34,916 bps avant frais | Gemini `PAXG/USDC` : impact marche seul ~34,916 bps avant frais | Pas competitif pour PAXG ; a eviter sauf absence totale d'autre choix. |

Conclusion sans Binance : la meilleure solution economique actuelle n'est plus "USDC partout". C'est **USDT comme stablecoin d'execution pour PAXG**, avec une reserve USDC possible seulement si TradeIO5 accepte de convertir ponctuellement, en sachant que la conversion double presque le cout PAXG. Si la contrainte MiCA interdit USDT, le meilleur chantier restant est Bitpanda Fusion avec une cle API, car les carnets publics non-USDT ne donnent pas de route PAXG acceptable.

## Questions ouvertes pour Clem

- Montant typique par ordre : 10 EUR, 50 EUR, 100 EUR, 500 EUR, 1 000 EUR, plus ?
- Frequence : DCA quotidien, hebdomadaire, mensuel ; ventes rares ou potentiellement frequentes ?
- Capital de depart : EUR bancaire, stablecoin deja detenu, ou crypto deja sur exchange ?
- Custody : fonds laisses sur exchange ou retrait systematique vers wallet externe ?
- Exchanges acceptables : Binance, Kraken, OKX, Coinbase, Bitstamp, Bybit, KuCoin, Gate.io, Bitget, autres ?
- Contraintes reglementaires : preference MiCA/UE, ou priorite stricte au cout ?
- Stablecoins acceptables : uniquement fiat-backed conservateurs, ou aussi synthetiques/crypto-backed ?
- PAXG strictement requis, ou exposition or tokenisee alternative acceptable ?

## Hypotheses provisoires tant que les reponses manquent

- Profil utilisateur : resident UE/France, KYC accepte.
- Usage : spot uniquement, pas de marge, pas de produits derives.
- API requise : REST/WebSocket documentee, cle API utilisable pour ordres limites et lecture carnet.
- Priorite : minimiser le cout complet d'execution, pas seulement le taux de fee.
- PAXG est requis comme actif spot.
- Les retraits on-chain ne sont pas supposes apres chaque ordre ; ils seront chiffres separement si necessaire.

## Methode de recherche

1. Lister les exchanges qui proposent les trois actifs BTC, ETH et PAXG en spot contre stablecoin.
2. Pour chaque exchange candidat, relever les paires disponibles, les frais maker/taker officiels, les limites d'ordre, et la disponibilite API.
3. Mesurer la liquidite actuelle des paires candidates via carnets publics : spread, profondeur achetable/vendable a 10/50/100/500/1000/5000 USD, slippage theorique.
4. Comparer les stablecoins disponibles : capitalisation, volumes, depeg historique/recent, emetteur, reserves, risques reglementaires/custodial.
5. Proposer une strategie concrete : exchange principal, stablecoin principal, fallback PAXG, type d'ordre, seuils de liquidite, et integration TradeIO5.

### Snapshot reproductible

Un outil local a ete ajoute pour rejouer la partie "carnets publics" sans cle API :

```powershell
python tools/research/stablecoin_trade_cost_snapshot.py
```

ou, pour une verification de stabilite rapide :

```powershell
python tools/research/stablecoin_trade_cost_snapshot.py --samples 3 --interval-seconds 30
```

ou, pour tester les tailles d'ordre reelles de Clem quand elles seront connues :

```powershell
python tools/research/stablecoin_trade_cost_snapshot.py --sizes 50,100,500,1000,5000
```

ou, pour produire une sortie consommable par un futur routeur :

```powershell
python tools/research/stablecoin_trade_cost_snapshot.py --json --sizes 50,1000,5000
```

Il sort une table Markdown avec :

- spread instantane ;
- profondeur ask/bid dans les 10 bps du mid ;
- slippage theorique d'achat pour 1 000 et 5 000 quote, mesure contre le meilleur ask ;
- buy impact pour 1 000 et 5 000 quote, mesure contre le mid-price, donc incluant le demi-spread.
- sell impact pour `USDC/USDT`, afin de chiffrer les routes de conversion stablecoin ;
- cout total taker single-leg par scenario de frais connu : `frais_taker_bps + buy_impact_bps`.
- cout total d'une route deux legs `USDC -> USDT -> PAXG`.
- meilleure route par profil (`LOWEST_COST_STABLECOIN`, `EU_CONSERVATIVE_USDC`, `BINANCE_UNAVAILABLE_USDT`) pour `BTC`, `ETH`, `PAXG`.
- ladder de meilleure route PAXG pour chaque taille configuree par `--sizes`.
- si `--samples > 1`, un agregat min/mediane/max sur les routes PAXG, les couts single-leg et les routes deux legs.

Limites volontaires : pas de passage d'ordre, pas de compte authentifie, pas de fee account-specific. Les frais restent a relever par source officielle et, plus tard, par API authentifiee du compte cible.

Un second outil local couvre la partie stablecoins :

```powershell
python tools/research/stablecoin_risk_snapshot.py
```

Il existe aussi en sortie machine-readable :

```powershell
python tools/research/stablecoin_risk_snapshot.py --json
```

Il utilise DefiLlama pour sortir market cap, prix, ecart au peg en bps, variation d'offre 1j/7j/30j, mecanisme indique et principales chains de circulation. Limite : cela ne remplace pas une analyse juridique ou une preuve de reserves ; c'est une mesure de taille/peg/concentration a l'instant T. Les variations d'offre peuvent aussi refleter des changements de classification/indexation par chain dans la source, pas uniquement des mint/burn economiques.

Un troisieme outil local inventorie les paires spot publiques :

```powershell
python tools/research/exchange_pair_inventory.py
```

ou :

```powershell
python tools/research/exchange_pair_inventory.py --json
```

Il verifie, pour chaque exchange candidat, les quotes disponibles sur `BTC`, `ETH` et `PAXG`, puis calcule les stablecoins communs aux trois actifs. C'est volontairement separe de la liquidite : une paire peut exister et rester mauvaise si le carnet est trop large ou trop fin.

Un quatrieme outil local couvre les pistes wrapped/on-chain :

```powershell
python tools/research/wrapped_asset_liquidity_snapshot.py --sizes 50,1000,5000
```

ou :

```powershell
python tools/research/wrapped_asset_liquidity_snapshot.py --json --sizes 50,1000,5000
```

Il lit DexScreener pour la liquidite DEX observable et Jupiter pour des quotes Solana sur `USDC -> PAXG` et `USDC -> cbBTC`. Limite : les quotes EVM Base/Arbitrum/Ethereum doivent encore etre ajoutees via 0x/1inch/Uniswap/Aerodrome pour obtenir un cout total routeur + gas vraiment comparable.

## Resultats provisoires

Collecte realisee le 2026-10-08 vers 18:24-18:29 Europe/Paris via APIs publiques de carnets/tickers. Les chiffres de liquidite sont des instantanes : a rafraichir automatiquement avant toute decision d'execution.

### Sources principales

- Binance fee schedule : https://www.binance.com/en/fee/trading
- Binance Spot API order endpoint : https://github.com/binance/binance-spot-api-docs/blob/master/rest-api.md
- Kraken fee schedule : https://www.kraken.com/features/fee-schedule
- Kraken stablecoins EEA : https://support.kraken.com/articles/stablecoin-offerings-for-eea-clients
- OKX EEA fee rules : https://www.okx.com/en-gb/help/what-are-the-new-trading-fees-for-eea-users
- OKX EEA spot-only fee adjustment : https://www.okx.com/en-us/help/important-notice-upcoming-spot-fee-adjustment-eea
- OKX order endpoint : https://www.okx.com/docs-v5/en/
- Coinbase Advanced overview/API/fees : https://help.coinbase.com/en-gb/coinbase/trading-and-funding/advanced-trade/what-is-advanced-trade
- KuCoin VIP fee schedule : https://www.kucoin.com/support/48142946141635
- KuCoin add order endpoint : https://www.kucoin.com/en-au/docs-new/rest/spot-trading/orders/add-order
- Bybit spot fees : https://www.bybit.com/en-GB/help-center/article/Bybit-Spot-Fees-Explained
- Bitget spot fee FAQ : https://www.bitget.com/support/articles/12560603820584
- Gate.io fee schedule : https://www.gate.com/fee
- MEXC 0-fee spot announcement : https://blog.mexc.com/press-release/mexc-upgrades-0-fee-spot-trading-to-cover-all-pairs/
- Bitstamp API/fees : https://www.bitstamp.net/api/
- Bitpanda Fusion API : https://www.bitpanda.com/en/fusion/api
- Bitpanda Fusion Developer Platform : https://docs.fusion.bitpanda.com/
- Bitpanda Fusion fees : https://support.bitpanda.com/hc/en-us/articles/16663481714844-Bitpanda-Fusion
- Crypto.com Exchange API : https://crypto.com/exchange-pro/en-US/api/
- Crypto.com Exchange currency networks API : https://exchange-developer.crypto.com/exchange/v1/docs/api/rest/private-get-currency-networks
- Crypto.com Exchange fees : https://crypto.com/exchange/document/fees-limits
- Gemini marketplace/API : https://www.gemini.com/marketplace
- Gemini REST API : https://developer.gemini.com/rest
- Binance Wallet capital API : https://developers.binance.com/docs/wallet/capital/all-coins-info
- Coinbase Exchange withdrawal minimums/fees : https://help.coinbase.com/en/exchange/crypto-transfers/are-there-withdrawal-minimums-and-fees
- Coinbase Global Exchange network fees : https://help.coinbase.com/en/global-exchange/trading-deposits-withdrawals/network-fees
- Kraken withdrawal info API : https://docs.kraken.com/api/docs/rest-api/get-withdrawal-information
- DefiLlama stablecoin API : https://stablecoins.llama.fi/stablecoins?includePrices=true
- Circle MiCA USDC/EURC : https://www.circle.com/circle-eea
- Paxos PAXG : https://www.paxos.com/pax-gold
- Paxos PAXG transparency : https://www.paxos.com/paxg-transparency
- Paxos PAXG on Solana : https://www.paxos.com/blog/bringing-paxg-to-solana
- Coinbase cbBTC : https://www.coinbase.com/cbbtc
- Coinbase wrapped assets support : https://help.coinbase.com/en/coinbase/trading-and-funding/sending-or-receiving-cryptocurrency/coinbase-wrapped-btc
- Coinbase Prime multi-network deposits : https://docs.cdp.coinbase.com/prime/concepts/transactions/multinetwork
- WBTC overview/transparency : https://docs.wbtc.network/overview/wbtc-overview et https://www.wbtc.network/transparency
- tBTC : https://tbtc.network/
- DexScreener API : https://docs.dexscreener.com/api/reference
- Jupiter Swap API : https://api.jup.ag/swap/v1/quote
- ESMA stablecoins non-MiCA, 2026-10-08 : https://www.esma.europa.eu/press-news/esma-news/esma-sets-out-supervisory-expectations-services-related-unauthorised
- AMF fin de periode transitoire MiCA France : https://www.amf-france.org/en/news-publications/news/amf-reminds-digital-asset-service-providers-transitional-period-allowing-them-continue-providing
- Binance MiCA Europe update : https://www.binance.com/fr/blog/all/4457979419755346760

### Fee schedules observes

| Exchange | Frais spot retail pertinents | Remarque API/integration |
|---|---:|---|
| Binance | Regular 0,100 % maker / 0,100 % taker ; avec paiement BNB 0,075 % / 0,075 %. Colonne USDC : taker 0,095 % hors BNB, 0,07125 % avec BNB, maker indique "Standard". | API Spot mature, endpoint `POST /api/v3/order`, deja proche du code existant du projet. |
| OKX EEA avec compte derivatives ouvert | Regular 0,0800 % maker / 0,1000 % taker. | API v5 propre, endpoint `POST /api/v5/trade/order`. |
| OKX EEA spot-only apres 2026-09-25 | Regular "All pairs" 0,100 % maker / 0,200 % taker ; stablecoins 0,000 % maker / 0,050 % taker. | Le statut du compte change fortement le cout. |
| Kraken Pro | Tier 1 spot crypto 0,40 % maker / 0,80 % taker ; stablecoin/FX 0,20 % / 0,20 %. | API robuste, mais frais retail trop hauts pour l'objectif sauf volume/AoP important. |
| Coinbase Advanced | Jusqu'a 0,4 % maker / 0,6 % taker selon aide publique. | API disponible, mais frais de depart et paires stablecoin faibles. |
| KuCoin | VIP 0 Class A 0,1 % maker / 0,1 % taker ; remise KCS 20 %. | API spot disponible. |
| Bybit | Toutes paires spot : 0,1 % maker / 0,1 % taker. | API spot OK, mais PAXG non trouve dans la collecte. |
| Bitget | Base spot 0,1 % maker / 0,1 % taker ; BGB peut reduire. | API spot OK, PAXG/USDT trouve. |
| Gate.io | VIP0 spot public observe : 0,100 % maker / 0,100 % taker ; remise GT possible selon structure de frais. | API spot OK, mais PAXG/USDT moins profond que Binance et souvent derriere KuCoin/OKX/Bitget selon taille. |
| MEXC | Communication officielle : 0 % maker / 0 % taker sur spot. | Interessant pour BTC/ETH, mais PAXG/USDT non trouve via `exchangeInfo` lors de la collecte ; API/disponibilite regionale a verifier. |
| Crypto.com Exchange | Fee page publique : Level 1 < 10k USD 30j = 0,250 % maker / 0,500 % taker ; reductions via CRO/VIP. | API Exchange disponible ; PAXG/USDT trouve avec bon carnet, mais frais retail eleves. |
| Bitstamp | Exemple API fees : `btcusd` 0,150 % maker / 0,160 % taker ; endpoint fees par marche disponible. | Regule/serieux, mais PAXG tres peu liquide dans la collecte. |
| Gemini | Frais a verifier selon compte/region ; API REST trading disponible. | PAXG/USDC trouve, mais spread mesure ~100 bps : non competitif pour execution automatisee. |
| Bitpanda Fusion | Level 1 0,25 %, puis degressif jusqu'a 0,02 % ; API Fusion disponible ; liquidite agregee 12+ venues annoncee. | Option UE regulee interessante, mais pas un carnet CEX classique ; cout reel a verifier par devis/quote API authentifie. |

### Inventaire public des paires spot

Snapshot rejoue le 2026-10-08 a 19:07 Europe/Paris via `tools/research/exchange_pair_inventory.py`.

| Exchange | BTC quotes | ETH quotes | PAXG quotes | Stablecoin commun aux 3 | Lecture |
|---|---|---|---|---|---|
| Binance | EUR, FDUSD, TRY, USD, USDC, USDS, USDT | EUR, FDUSD, TRY, USD, USDC, USDS, USDT | TRY, USDC, USDT | USDC, USDT | Seul candidat public trouve avec USDC **et** USDT communs aux trois actifs. |
| OKX | EUR, TRY, USDC, USDT | EUR, TRY, USDC, USDT | TRY, USDT | USDT | Bon fallback USDT ; pas de PAXG/USDC public observe. |
| KuCoin | EUR, USDC, USDG, USDT | EUR, USDC, USDG, USDT | USDT | USDT | Couvre le triptyque en USDT uniquement. |
| Gate.io | GUSD, RLUSD, USD, USDC, USDT | GUSD, RLUSD, USD, USDC, USDT | USDT | USDT | Couvre le triptyque en USDT uniquement ; liquidite PAXG inferieure a Binance. |
| Bitget | EUR, USDC, USDE, USDT | EUR, USDC, USDE, USDT | USDT | USDT | Couvre le triptyque en USDT uniquement ; fallback utilisable. |
| Crypto.com Exchange | EUR, PYUSD, USD, USDT | EUR, PYUSD, USD, USDT | EUR, USD, USDT | USDT | Couvre le triptyque en USDT, mais frais Level 1 publics trop hauts. |
| Gemini | EUR, GBP, GUSD, RLUSD, USD, USDC, USDT | EUR, GBP, GUSD, RLUSD, USD, USDC, USDT | GUSD, RLUSD, USD, USDC | GUSD, RLUSD, USDC | Theoriquement tres interessant en stablecoin non-USDT, mais carnet PAXG/USDC mesure beaucoup trop large. |
| Coinbase Exchange | EUR, GBP, USD, USDT | EUR, GBP, USD, USDT | USD | - | Pas de route stablecoin commune aux trois. |
| Kraken | CHF, DAI, EUR, EURC, GBP, PYUSD, USD, USDC, USDT | CHF, DAI, EUR, EURC, GBP, PYUSD, USD, USDC, USDT | EUR, USD | - | Bon acteur fiat/regule et pertinent si PAXG deja detenu ; pas de route PAXG stablecoin. |
| Bitstamp | EUR, EURC, GBP, RLUSD, USD, USDC, USDT | EUR, EURC, GBP, RLUSD, USD, USDC, USDT | EUR, USD | - | PAXG fiat seulement, liquidite PAXG faible dans la collecte. |
| Bybit | EUR, RLUSD, TRY, USD, USDC, USDE, USDT | EUR, RLUSD, TRY, USD, USDC, USDE, USDT | - | - | Pas de PAXG spot public observe. |
| MEXC | EUR, USDC, USDE, USDT | EUR, USDC, USDE, USDT | - | - | Frais spot attractifs pour BTC/ETH, mais pas de PAXG public observe. |

Lecture : **la disponibilite PAXG filtre brutalement les options**. Les exchanges a tres bons frais ou nombreuses paires BTC/ETH ne suffisent pas s'ils n'ont pas PAXG. Parmi les routes stablecoin completes, Binance domine par le couple disponibilite + liquidite + frais ; Gemini est l'exception stablecoin non-USDT theorique, mais son carnet PAXG invalide la route dans les snapshots.

### Paires PAXG : liquidite mesuree

Metrices :

- spread bps = bid/ask instantane en points de base ;
- ask depth 10 bps = notional disponible cote achat a moins de 10 bps du mid ;
- slip 1k/5k = slippage theorique en bps pour acheter 1 000 / 5 000 quote en traversant le carnet, mesure contre le meilleur ask ;
- volume 24h = volume quote donne par l'API exchange, quand disponible.

| Exchange / paire | Spread bps | Ask depth 10 bps | Slip achat 1k | Slip achat 5k | Volume 24h quote | Lecture |
|---|---:|---:|---:|---:|---:|---|
| Binance PAXG/USDT | 0,024 | 303 977 USDT | 0,012 | 0,089 | 12 708 837 USDT | Meilleur candidat PAXG stablecoin mesure. |
| Crypto.com PAXG/USDT | 0,024 | 125 826 USDT | 0,012 | 0,830 | N/A dans ce relevé | Bon carnet instantane, mais frais retail publics nettement plus hauts. |
| Binance PAXG/USDC | 0,024 | 32 417 USDC | 0,096 | 2,691 | 473 723 USDC | Correct pour petits ordres, nettement moins profond. |
| OKX PAXG/USDT | 0,727 | 101 518 USDT | 0,364 | 1,865 | 1 877 174 USDT | Bon fallback si tradable en EEA. |
| KuCoin PAXG/USDT | 0,024 | 25 747 USDT | 0,082 | 1,679 | 1 035 816 USDT | Spread tres serre, profondeur limitee. |
| Bitget PAXG/USDT | 0,024 | 86 753 USDT | 0,735 | 1,356 | 839 637 USDT | Profondeur utile moyenne. |
| Gate.io PAXG/USDT | 0,921 | 47 357 USDT | 0,460 | 1,390 | 569 090 USDT | Fallback possible, spread plus large. |
| Kraken PAXG/USD | 0,146 | 82 018 USD | 0,800 | 0,915 | 1 106 605 USD | Pas stablecoin, mais fiat USD ; frais retail eleves. |
| Kraken PAXG/EUR | 1,303 | 58 972 EUR | 0,678 | 1,393 | 376 577 EUR | Pas stablecoin ; possible si depart EUR. |
| Coinbase PAXG/USD | 1,747 | 85 087 USD | 0,873 | 1,900 | 3 148 608 USD | Pas stablecoin ; frais retail eleves. |
| Bitstamp PAXG/USD | 16,029 | 1 100 USD | 9,698 | 11,850 | 10 309 USD | Non competitif dans ce releve. |
| Gemini PAXG/USDC | 99,733 | 0 USDC | 49,866 | 49,866 | 143 322 USDC approx. | Stablecoin direct mais spread prohibitif. |

Conclusion PAXG provisoire : si USDT est autorise dans le compte cible, Binance PAXG/USDT reste le meilleur **candidat structurel** par profondeur et simplicite. Mais le meilleur **devis instantane** peut basculer vers Binance PAXG/USDC sur de petits ordres, car le fee taker USDC + BNB est plus bas. Si USDT n'est pas autorise ou pas souhaite, Binance PAXG/USDC devient le chemin stablecoin le plus propre mais doit etre limite en taille ou surveille par carnet avant ordre.

### Cout total taker PAXG, snapshot rejoue

Snapshot rejoue le 2026-10-08 a 18:42:54 Europe/Paris (`tools/research/stablecoin_trade_cost_snapshot.py`). Formule utilisee :

```text
cout_taker_vs_mid_bps = frais_taker_bps + buy_impact_bps
```

Hors frais de depot/retrait et hors conversion stablecoin prealable.

| Route | Frais taker utilises | Buy impact 1k | Cout total 1k | Buy impact 5k | Cout total 5k | Lecture |
|---|---:|---:|---:|---:|---:|---|
| Binance PAXG/USDT, paiement BNB | 7,500 bps | 0,012 | 7,512 | 0,055 | 7,555 | Meilleur cout pur mesure, mais depend d'USDT et de l'acces Binance. |
| Binance PAXG/USDT, sans BNB | 10,000 bps | 0,012 | 10,012 | 0,055 | 10,055 | Toujours tres bon, mais perd l'avantage fee. |
| Binance PAXG/USDC, paiement BNB | 7,125 bps | 1,569 | 8,694 | 2,543 | 9,668 | Meilleure route stablecoin prudente trouvee, mais profondeur plus fine. |
| Binance PAXG/USDC, sans BNB | 9,500 bps | 1,569 | 11,069 | 2,543 | 12,043 | Correct pour petits ordres ; moins bon que USDT. |
| OKX PAXG/USDT, compte avec X-Perps ouvert | 10,000 bps | 0,242 | 10,242 | 1,333 | 11,333 | Bon fallback USDT si OKX disponible. |
| KuCoin PAXG/USDT, VIP0 base | 10,000 bps | 0,012 | 10,012 | 1,756 | 11,756 | Bon, mais moins robuste en profondeur. Avec KCS, taker theorique 8 bps. |
| Bitget PAXG/USDT, base | 10,000 bps | 0,768 | 10,768 | 1,469 | 11,469 | Carnet utilisable ; BGB peut ramener le fee theorique a 8 bps. |
| Crypto.com PAXG/USDT, Level 1 public | 50,000 bps | 0,012 | 50,012 | 1,380 | 51,380 | Carnet bon, frais retail trop hauts sans meilleur tier. |
| Kraken PAXG/USD | 80,000 bps | 0,212 | 80,212 | 1,003 | 81,003 | Pas stablecoin et frais retail disqualifiants pour le besoin cout minimal. |
| Coinbase PAXG/USD | 60,000 bps | 1,377 | 61,377 | 2,285 | 62,285 | Pas stablecoin ; route regulee mais trop chere. |
| Gemini PAXG/USDC | Non chiffre | 47,275 | >=47,275 avant frais | 47,283 | >=47,283 avant frais | Stablecoin direct mais spread/impact deja prohibitif. |

Lecture : en taker immediat, **Binance PAXG/USDT avec BNB reste souvent le moins cher**, surtout quand la taille augmente, mais **Binance PAXG/USDC avec BNB peut battre USDT sur de petits ordres** si son carnet est momentanement propre. Le revers : USDC a un carnet PAXG nettement moins profond ; il faut donc un seuil de blocage/fractionnement et une comparaison pre-trade systematique.

### BTC/ETH : cout single-leg USDT vs USDC

Snapshot rejoue le 2026-10-08 a 18:53:02 Europe/Paris. Ces lignes sont conservees comme benchmark historique avec Binance, mais le scenario cible de Clem exclut maintenant Binance. Pour BTC/ETH, la liquidite non-Binance reste suffisante ; l'ecart vient surtout du fee schedule.

| Route | Frais taker utilises | Buy impact 1k | Cout total 1k | Buy impact 5k | Cout total 5k | Lecture |
|---|---:|---:|---:|---:|---:|---|
| Binance BTC/USDT, paiement BNB | 7,500 bps | 0,001 | 7,501 | 0,001 | 7,501 | Excellent, mais USDT. |
| Binance BTC/USDC, paiement BNB | 7,125 bps | 0,001 | 7,126 | 0,001 | 7,126 | Meilleur cout et stablecoin plus prudent UE. |
| Binance ETH/USDT, paiement BNB | 7,500 bps | 0,021 | 7,521 | 0,021 | 7,521 | Excellent, mais USDT. |
| Binance ETH/USDC, paiement BNB | 7,125 bps | 0,021 | 7,146 | 0,021 | 7,146 | Meilleur cout et stablecoin plus prudent UE. |
| OKX BTC/USDT, compte avec X-Perps ouvert | 10,000 bps | 0,006 | 10,006 | 0,006 | 10,006 | Bon fallback. |
| OKX BTC/USDC, compte avec X-Perps ouvert | 10,000 bps | 0,887 | 10,887 | 1,190 | 11,190 | USDC moins profond que Binance sur ce snapshot. |
| OKX ETH/USDT, compte avec X-Perps ouvert | 10,000 bps | 0,021 | 10,021 | 0,021 | 10,021 | Bon fallback. |
| OKX ETH/USDC, compte avec X-Perps ouvert | 10,000 bps | 0,021 | 10,021 | 0,021 | 10,021 | Correct. |

Conclusion BTC/ETH : hors Binance, OKX ressort comme meilleur candidat public dans les snapshots, autour de 10 bps sur BTC/USDT et ETH/USDT. Le probleme ne vient pas de BTC/ETH ; il vient de PAXG.

### Conversion stablecoin : USDC reserve -> USDT execution ?

Snapshot rejoue le 2026-10-08 a 18:49:52 Europe/Paris. Question testee : si la reserve prudente est en USDC, faut-il convertir en USDT pour profiter du meilleur carnet PAXG/USDT ?

Resultat Binance historique :

| Route | Conversion impact | PAXG impact | Frais utilises | Cout total 1k | Cout total 5k | Lecture |
|---|---:|---:|---:|---:|---:|---|
| Direct `USDC -> PAXG` via PAXG/USDC, BNB fees | N/A | 1,330 / 2,668 bps | 7,125 bps | 8,455 bps | 9,793 bps | Meilleur si on part deja d'USDC. |
| `USDC -> USDT -> PAXG`, BNB fees | 0,100 bps | 0,012 / 0,022 bps | 7,125 + 7,500 bps | 14,737 bps | 14,747 bps | Plus liquide mais plus cher a cause du deuxieme trade. |
| `USDC -> USDT -> PAXG`, fees standard | 0,100 bps | 0,012 / 0,022 bps | 9,500 + 10,000 bps | 19,612 bps | 19,622 bps | Non competitif pour petits/moyens ordres. |

Conclusion historique avec Binance : **si la reserve est USDC, la route directe PAXG/USDC etait moins chere que convertir vers USDT**, tant que le carnet PAXG/USDC restait dans les seuils de profondeur/slippage. Hors Binance, ce constat ne tient plus : les routes non-Binance `USDC -> USDT -> PAXG` sortent autour de 20,5-21,8 bps dans le snapshot du 2026-10-09, et aucune route PAXG/USDC publique liquide ne remplace Binance. La route deux legs ne devient interessante que si :

- `PAXG/USDC` est temporairement trop fin ou bloque ;
- un programme de frais/promotion rend la conversion quasi gratuite ;
- l'ordre est beaucoup plus gros que les tailles testees, au point que le manque de profondeur USDC domine les frais supplementaires.

### Frais de depot/retrait et custody

Les tableaux ci-dessus chiffrent le **cout d'execution spot** : trading fee + spread/impact carnet + conversion stablecoin eventuelle. Ils ne prouvent pas le cout total si la strategie retire les actifs apres chaque achat.

Ce point change fortement le verdict :

| Mode de custody | Frais de retrait dans le cout par ordre | Lecture |
|---|---:|---|
| Fonds laisses sur exchange | 0 par ordre | Cout minimal, mais risque custody/exchange/reglementaire. |
| Retrait groupe, par exemple mensuel | `frais_retrait_actif_reseau / nombre_ordres_groupes` | Souvent le meilleur compromis si self-custody souhaitee. |
| Retrait apres chaque achat | `frais_retrait_actif_reseau` sur chaque ordre | Peut dominer le trading fee, surtout sur petits ordres et actifs ERC-20 comme PAXG. |

Je ne fige pas ici de frais de retrait Binance/OKX/Crypto.com/Kraken comme s'ils etaient constants : ils dependent de l'actif, du reseau, du pays/entite KYC, de l'adresse, du compte, et parfois de la congestion. Pour rester "chiffres reels", TradeIO5 devra les demander au moment du devis.

Endpoints utiles pour chiffrer sans estimation :

| Exchange | Endpoint | Chiffres recuperables | Remarque |
|---|---|---|---|
| Binance | `GET /sapi/v1/capital/config/getall` | `withdrawFee`, `withdrawMin`, `withdrawMax`, `depositEnable`, `withdrawEnable`, par coin/reseau | Endpoint signe. C'est le bon endroit pour le retrait exact Binance avant arbitrage custody. |
| OKX | `GET /api/v5/asset/currencies` | `minWd`, `minFee`, `maxFee`, disponibilite depot/retrait, par devise/reseau | Authentifie et lie a l'entite KYC du compte. |
| Crypto.com Exchange | `private/get-currency-networks` | `withdrawal_fee`, `min_withdrawal_amount`, `withdraw_enabled`, `deposit_enabled` | Authentifie ; utile si Crypto.com reste fallback PAXG/USDT. |
| Kraken | `POST /0/private/WithdrawInfo` | `fee`, `limit`, montant net pour un asset, une adresse configuree et un montant | Demande une adresse de retrait deja configuree ; bon pour verifier le vrai cout juste avant sortie. |
| Coinbase Exchange | API publique `/currencies/{asset}` + UI/API de retrait | Minimums de retrait publics ; frais reseau dynamiques divulgues au moment de la transaction | Coinbase indique que les retraits USDC sont gratuits sur reseaux supportes ; autres assets = estimation reseau affichee au retrait. |

Verifications publiques Coinbase realisees le 2026-10-08 :

| Asset Coinbase | Reseau par defaut / reseaux utiles | Minimum retrait public observe | Fee public exploitable |
|---|---|---:|---|
| BTC | `bitcoin`, aussi `base`, `arbitrum`, `ethereum`, `solana` selon actif wrap/support | 0,0001 BTC sur Bitcoin ; 0,00002 BTC sur certains reseaux alternatifs | Fee dynamique, divulgue au retrait. |
| ETH | `ethereum`, aussi `base`, `arbitrum`, `optimism`, `polygon`, `unichain` | 0,0001 ETH | Fee dynamique, divulgue au retrait. |
| PAXG | Ethereum uniquement | 0,0001 PAXG | Fee dynamique, divulgue au retrait. |
| USDC | nombreux reseaux dont Ethereum/Base/Arbitrum/Optimism/Polygon/Solana | 1 USDC sur Ethereum/Solana/Polygon ; 0,01 USDC sur Base/Arbitrum/Optimism/Unichain | Coinbase Global Exchange indique USDC withdrawals free sur tous les reseaux supportes. |

Consequence pour la strategie : sans retrait systematique, la meilleure solution reste une optimisation CEX/carnet. Avec retrait systematique, surtout vers Ethereum, il faut ajouter un `WithdrawalQuote` a `ExecutionQuote` et probablement regrouper les sorties au lieu de retirer apres chaque DCA.

### Maker vs taker : economie et risque d'execution

Pour un DCA non urgent, un ordre `post-only` peut ameliorer le cout mais ajoute un risque de non-remplissage et d'adverse selection. Le bon raisonnement n'est pas "maker toujours meilleur", mais :

```text
cout_maker_attendu ~= frais_maker_bps - demi_spread_bps + cout_non_fill + cout_adverse_selection
cout_taker_immediat ~= frais_taker_bps + buy_impact_bps
```

Sur les paires tres serrees comme `BTC/USDT`, `ETH/USDT` ou `PAXG/USDT` Binance, le demi-spread est quasi nul : le vrai gain maker vient surtout d'un fee maker plus bas, pas du prix. Sur `PAXG/USDC`, le spread et la profondeur bougent davantage ; le maker peut aider, mais seulement si l'ordre peut rester en carnet sans rater le signal.

Strategie recommandee :

- signaux DCA ordinaires : tenter `LIMIT_MAKER`/post-only sur une fenetre courte configurable ;
- si non rempli : annuler et recalculer un devis ; ne jamais transformer automatiquement en market ;
- signal expirant ou execution necessaire : `LIMIT IOC` avec prix plafond derive du devis et `maxTotalCostBps` ;
- PAXG : seuil plus strict que BTC/ETH, car la liquidite est l'element fragile.

Seuils de depart a discuter :

| Actif | Profil route | Bloquer/fractionner si... | Idee |
|---|---|---|---|
| BTC/ETH | CEX majeur USDT/USDC | `buyImpactBps > 2` ou depth 10 bps < 20x ordre | Devrait presque toujours passer en retail ; sinon carnet anormal. |
| PAXG/USDT | Binance/OKX/KuCoin/Bitget | `buyImpactBps > 3` ou depth 10 bps < 10x ordre | Evite de traverser un carnet fin pendant une micro-secheresse. |
| PAXG/USDC | Binance USDC | `buyImpactBps > 5` ou depth 10 bps < 5x ordre | Accepte un peu plus de cout pour rester en stablecoin UE-prudent. |
| DEX PAXG/USDC | On-chain uniquement | devis routeur > CEX + retrait/gas ou price impact > 10 bps | A utiliser seulement si fonds deja on-chain ou CEX indisponible. |

### BTC/ETH : liquidite mesuree sur USDT

BTC/ETH ne sont pas le facteur limitant : plusieurs carnets absorbent facilement un DCA retail. Extraits utiles :

| Exchange / paire | Ask depth 10 bps | Slip achat 1k | Volume 24h quote | Lecture |
|---|---:|---:|---:|---|
| Binance BTC/USDT | 7 712 106 USDT | 0,001 bps | 1 593 104 013 USDT | Excellent. |
| OKX BTC/USDT | 2 801 597 USDT | 0,006 bps | 679 423 572 USDT | Excellent. |
| Bitget BTC/USDT | 7 587 039 USDT | 0,001 bps | 324 212 813 USDT | Excellent carnet instantane. |
| KuCoin BTC/USDT | 64 773 USDT | 0,019 bps | 631 333 650 USDT | Suffisant retail, profondeur top-of-book plus faible. |
| Binance ETH/USDT | 2 551 822 USDT | 0,021 bps | 957 059 542 USDT | Excellent. |
| OKX ETH/USDT | 1 751 058 USDT | 0,021 bps | 460 311 625 USDT | Excellent. |
| Bitget ETH/USDT | 507 060 USDT | 0,021 bps | 173 331 914 USDT | Tres bon. |

### Stablecoins : taille, peg, risques

Donnees DefiLlama au moment de la collecte, rejouees le 2026-10-08 a 23:19 Europe/Paris via `tools/research/stablecoin_risk_snapshot.py` :

| Stablecoin | Market cap approx. | Prix | Ecart peg | Offre 30j | Mecanisme | Principales chains | Lecture risque/liquidite |
|---|---:|---:|---:|---:|---|---|---|
| USDT | 184,3 Md USD | 0,999289 | -7,109 bps | +0,551 % | fiat-backed | Tron 92,6 Md ; Ethereum 76,6 Md ; BSC 9,2 Md | Liquidite crypto maximale, mais risque reglementaire EEA/MiCA eleve : Kraken indique USDT deliste pour clients EEA. |
| USDC | 66,2 Md USD | 0,999616 | -3,836 bps | -11,084 % | fiat-backed | Ethereum 45,8 Md ; Solana 6,8 Md ; Base 4,4 Md | Meilleur compromis risque reglementaire UE/liquidite ; Circle presente USDC et EURC comme MiCA-compliant. Variation d'offre a surveiller, possiblement influencee par changements de classification par chain. |
| USDS/Sky Dollar | 7,0 Md USD | 0,999095 | -9,053 bps | +5,636 % | crypto-backed | Ethereum 6,9 Md ; Arbitrum 99,7 M ; Solana 5,7 M | Taille importante mais moins universel pour les paires spot CEX ciblees. |
| USDe | 4,8 Md USD | 0,999260 | -7,401 bps | +10,259 % | crypto-backed/synthetique | Ethereum 3,8 Md ; Solana 496,7 M ; Robinhood Chain 367,6 M | Liquidite croissante, mais risque structurel different d'un fiat-backed ; pas ideal comme reserve d'execution conservative. |
| DAI | 4,8 Md USD | 0,999945 | -0,555 bps | -0,979 % | crypto-backed | Ethereum 4,7 Md ; Arbitrum 16,9 M ; OP Mainnet 13,6 M | Stable historique, mais paires directes BTC/ETH/PAXG insuffisantes pour l'objectif. |
| USDG | 1,7 Md USD | 0,999873 | -1,267 bps | -48,277 % | fiat-backed | Robinhood Chain 692,3 M ; Solana 626,7 M ; Ethereum 336,6 M | Watchlist fiat-backed, mais la forte variation d'offre et l'adoption CEX/paires insuffisante le rendent trop fragile pour ce besoin. |
| PYUSD | 2,9 Md USD | 0,999510 | -4,902 bps | +0,075 % | fiat-backed | Ethereum 1,8 Md ; Solana 701,0 M ; Arbitrum 391,6 M | Plus petit, integration paires insuffisante. |
| RLUSD | 2,5 Md USD | 0,999927 | -0,734 bps | +1,196 % | fiat-backed | Ethereum 1,3 Md ; XRPL 1,2 Md | Bon peg instantane et taille en croissance, mais routes BTC/ETH/PAXG encore insuffisantes. |
| TUSD | 0,48 Md USD | 0,998833 | -11,670 bps | 0,000 % | fiat-backed | Ethereum 313,4 M ; Tron 168,3 M ; BSC 0,9 M | Taille et peg moins convaincants ; a eviter comme reserve principale. |
| FDUSD | 0,27 Md USD | 0,997125 | -28,748 bps | -20,001 % | fiat-backed | Ethereum 214,2 M ; Sui 43,5 M ; Solana 8,5 M | Tres utile historiquement pour promotions Binance, mais market cap et peg instantane defavorables ; pas reserve principale. |

Conclusion stablecoin provisoire : USDC est le meilleur actif de reserve prudent pour un resident UE ; USDT est le meilleur actif d'execution pur si accessible et accepte en risque ; USDG/RLUSD/PYUSD sont a surveiller mais pas encore assez connectes aux paires utiles ; FDUSD/TUSD/USDe/DAI ne ressortent pas comme meilleurs pour ce besoin precis.

Note : DefiLlama remonte aussi des actifs top market cap comme `USYC`, `USDY` ou `BUIDL`. Ils sont utiles comme actifs tokenises/rendement/treasury mais ne ressortent pas comme reserve transactionnelle simple pour trader `BTC/ETH/PAXG` en spot CEX ; leurs prix peuvent volontairement s'ecarter de 1 USD, donc le calcul "peg bps vs 1" n'est pas interpretable comme un depeg classique.

### Classement risque/liquidite des stablecoins pour TradeIO5

Classification orientee usage, pas notation financiere generale :

| Stablecoin | Role recommande | Liquidite utile pour le besoin | Risque stable/dangereux | Decision provisoire |
|---|---|---|---|---|
| USDC | Reserve principale UE/conservative | Tres bonne sur BTC/ETH ; PAXG/USDC existe surtout chez Binance mais moins profond que PAXG/USDT | Risque faible a moyen : fiat-backed, Circle indique USDC MiCA-compliant ; risques restent issuer/custody/chain/gel legal | **A privilegier comme reserve** si l'objectif est durable France/UE. |
| USDT | Stablecoin d'execution opportuniste | Meilleure liquidite globale et meilleure route PAXG mesuree | Risque moyen a eleve pour un resident UE : non-MiCA dans les sources consultees, delistings EEA, risque de coupure de service | **Ne pas utiliser comme socle UE**, mais garder en route active si le compte y a acces et si le risque est accepte. |
| EURC | Reserve EUR/MiCA possible | Faible utilite directe : pas de route PAXG/BTC/ETH competitive observee contre EURC | Risque reglementaire faible cote MiCA, mais liquidite crypto moindre | Bon actif de parking EUR, pas route de trading principale pour ce besoin. |
| DAI / USDS | Reserve crypto-backed secondaire | Direct pairs insuffisantes pour BTC/ETH/PAXG sur CEX cibles | Risque structurel plus complexe : collateral crypto, gouvernance/protocole, dependance DeFi | **A eviter comme reserve d'execution** pour ce cas precis. |
| USDe | A eviter en reserve execution conservative | Croissance forte mais paires directes insuffisantes pour le triptyque | Risque structurel synthetique/delta-hedged, different d'un stablecoin fiat-backed | Eventuellement rendement/speculation, pas reserve de trading ici. |
| PYUSD / USDG / RLUSD | Watchlist | Taille interessante mais paires directes insuffisantes | Fiat-backed mais adoption CEX cible encore trop faible pour ce triptyque | A surveiller, pas meilleur aujourd'hui. |
| FDUSD / TUSD | A exclure sauf promotion ponctuelle | Paires insuffisantes et capitalisation/peg defavorables dans le snapshot DefiLlama | Risque liquidite/peg plus eleve que USDC/USDT | Ne pas choisir comme reserve principale. |

Decision d'architecture stablecoin : stocker une preference explicite par profil utilisateur (`EU_CONSERVATIVE`, `LOWEST_COST_ACCEPTS_USDT`, `ONCHAIN_USDC`) plutot que choisir un stablecoin globalement. Le stablecoin "meilleur" depend du couple cout/reglementation.

### Contrainte UE/MiCA au 2026-10-08

Cette contrainte peut renverser la decision economique pure.

- ESMA a publie le 2026-10-08 une opinion demandant aux CASP autorises MiCA de cesser les services lies aux stablecoins non conformes MiCA pour les clients de l'Union europeenne, y compris trading platforms, exchange services, execution d'ordres, transferts, custody et gestion de portefeuille.
- AMF rappelle que les acteurs qui fournissent des services crypto en France devaient obtenir une autorisation MiCA pour continuer apres le 1er juillet 2026.
- Kraken indique deja que plusieurs stablecoins, dont USDT, sont delistes en EEA et que les depots de ces actifs ne sont pas recommandes.
- Binance indique avoir retire sa demande MiCA en Grece et contacter les utilisateurs europeens selon leur pays/statut de compte ; les fonds restent accessibles, mais le statut operationnel doit etre verifie compte par compte.
- Circle presente USDC et EURC comme MiCA-compliant.

Lecture : pour un resident France/UE qui veut une solution robuste et durable, il faut eviter de construire TradeIO5 autour d'USDT comme reserve principale. USDT peut rester une route de meilleur prix si le compte y a encore acces, mais ce doit etre une route opportuniste/desactivee par configuration, pas le socle de l'architecture.

### On-chain / DEX

La question "wrapped coin" merite une branche separee. Elle ne reduit pas magiquement le cout : elle deplace le probleme depuis les frais CEX vers un couple `routeur DEX + frais reseau + risque wrapper/bridge`. Mais elle peut devenir meilleure si les fonds sont deja en USDC on-chain, ou si le wrapper est accepte comme actif final.

Un quatrieme outil local a ete ajoute :

```powershell
python tools/research/wrapped_asset_liquidity_snapshot.py --sizes 50,1000,5000
```

Il interroge DexScreener pour la liquidite DEX observable et Jupiter pour les quotes Solana disponibles publiquement. Snapshot du 2026-10-09 01:03 Europe/Paris :

| Route wrapped/on-chain | Liquidite observee | Volume 24h observe | Quote routeur | Lecture |
|---|---:|---:|---|---|
| Base `cbBTC/USDC` Aerodrome | 4,99 M USD | 54,08 M USD | A chiffrer via 0x/1inch/Uniswap/Aerodrome | Tres bon signal BTC wrapped ; liquidite/volume comparables a une vraie route d'execution retail. |
| Base `cbBTC/USDC` Uniswap | 9,54 M USD | 18,11 M USD | A chiffrer via routeur EVM | Bonne profondeur ; utile si Coinbase/Base fait partie du workflow. |
| Base `cbBTC/USDC` Aerodrome Slipstream | 6,72 M USD | 18,02 M USD | A chiffrer via routeur EVM | Confirme que cbBTC Base n'est pas une route marginale. |
| Solana `cbBTC/USDC` Orca | 5,19 M USD | 16,92 M USD | Jupiter : 50 USDC -> 0,00061088 cbBTC ; 1k -> 0,01221823 ; 5k isole -> 0,06107228 | Piste tres interessante pour BTC si l'on accepte cbBTC et Coinbase comme issuer/custodian. |
| Base `WETH/USDC` Uniswap scan | 0,96 M USD | 21,47 M USD | A chiffrer via routeur EVM | Utile pour exposition ETH on-chain ; moins decisif car CEX ETH est deja liquide et simple. |
| Solana `PAXG/USDC` Raydium | 0,13 M USD | 94,16 k USD | Jupiter : 50 USDC -> 0,012047 PAXG ; 1k -> 0,240930 ; 5k -> 1,204464 | Piste PAXG officielle et peu chere en frais reseau, mais liquidite plus fine que cbBTC ; a surveiller avant d'en faire une route par defaut. |

Interpretation par actif :

- **BTC** : `cbBTC` est le meilleur candidat wrapped actuel. Coinbase indique que cbBTC represente BTC 1:1 et que les reseaux supportes incluent Base, Ethereum, Solana et Arbitrum ; Coinbase Prime documente qu'un depot de `1 cbBTC` sur Base dans le trading balance augmente le solde BTC de `1 BTC`. C'est donc une vraie passerelle possible entre on-chain et solde BTC Coinbase, mais le risque devient Coinbase issuer/custody + reseau.
- **ETH** : `WETH` sur Base/Arbitrum est techniquement le wrapper le plus naturel, mais le gain n'est pas aussi evident que pour BTC/PAXG. Acheter `ETH/USDC` ou `ETH/USDT` sur OKX reste autour de 10 bps dans les snapshots ; la route WETH doit battre ce cout apres gas, router fee, bridge/depot eventuel et risque L2.
- **PAXG** : le fil interessant n'est pas un clone wrapped inconnu. Paxos a lance PAXG sur Solana en 2026 et indique un bridge depuis Ethereum via Paxos ou LayerZero/Stargate. Cela donne une route `USDC Solana -> PAXG Solana` avec frais reseau faibles, mais le pool direct observe reste beaucoup plus petit que cbBTC. Il faut donc un devis Jupiter juste avant chaque ordre et un seuil de liquidite strict.
- **WBTC/tBTC** : a garder en comparaison BTC, pas en premier choix pour ce besoin. WBTC a une tres grande presence DeFi et une preuve de reserves publique, mais ajoute un modele custodian/merchant. tBTC est plus decentralise dans l'esprit, mais le mint/burn est moins fluide pour du petit DCA retail et demande plus d'operations.

Conclusion wrapped provisoire : **oui, il y a une piste "plus light en frais", surtout pour BTC via cbBTC et peut-etre pour PAXG via PAXG officiel Solana**. Elle ne remplace pas encore la strategie CEX non-Binance, parce qu'il manque les devis routeur EVM exacts et le cout de conversion custody. Dans TradeIO5, cette piste doit etre un profil distinct `ONCHAIN_USDC_WRAPPED`, pas un fallback silencieux du profil CEX.

### Alternatives europeennes/regulees

**Bitpanda Fusion**

- Points forts : acteur UE, API REST, liquidite agregee annoncee sur 12+ venues, frais degressifs jusqu'a 0,02 %.
- API annoncee : tickers, live order books agreges, candles, instruments, passage d'ordres market/limit/stop, cancel, balances, fee tier et volume 30j.
- Docs API verifiees le 2026-10-08 : base URL `https://api.fusion.bitpanda.com`, authentification par header `x-api-key`, `GET /v1/pairs` pour les paires/contraintes, `GET /v1/orderbook/{pair}` pour le carnet. Les lectures utiles demandent une cle Fusion, donc elles ne peuvent pas etre chiffrees publiquement dans ce repo sans compte.
- Frais : Level 1 = 0,25 % jusqu'a 100 kEUR de volume 30j ; degressif jusqu'a 0,02 % au Level 7. Bitpanda indique ne pas facturer de frais de depot/retrait sur Fusion, mais des couts tiers/conversion/taxes peuvent rester applicables.
- Point faible : frais de depart 0,25 %, pas de carnet exchange unique ; Bitpanda agit comme contrepartie principale et agrege des carnets externes. Pour chiffrer vraiment PAXG/USDC ou USDC -> PAXG, il faut utiliser l'API/devis authentifiee ou l'interface Fusion.
- Statut : bonne option de confort/regulation, pas encore prouvee meilleure en cout que Binance/OKX/Crypto.com.

**Crypto.com Exchange**

- Points forts : API Exchange, PAXG/USDT trouve, carnet instantane tres correct : spread 0,024 bps, ask depth 10 bps ~125 826 USDT, slippage 5k ~0,830 bps.
- Point faible : fees retail publiques beaucoup plus hautes que Binance/OKX/KuCoin au niveau 1.
- Statut : excellent fallback PAXG/USDT si un compte Crypto.com Exchange a un meilleur fee tier ou des fee credits ; mauvais choix retail pur si Level 1 a 0,25/0,50 %.

**Kraken avec PAXG deja detenu**

- Kraken liste publiquement `PAXG/EUR`, `PAXG/USD`, `PAXG/XBT` et `PAXG/ETH` ; l'API `AssetPairs` indique ces marches en ligne et un minimum d'ordre `0.001 PAXG`.
- Pour un resident EEA, Kraken indique que `USDT` et plusieurs autres stablecoins sont delistes : ils ne peuvent plus etre trades/achetes/vendus en EEA, seulement retires/deposes selon conditions. Kraken n'est donc pas une solution `USDT -> PAXG` pour Clem.
- Snapshot du 2026-10-09 a 00:55 Europe/Paris : `PAXG/EUR` a spread 0,785 bps, ask depth 10 bps ~60 738 EUR, buy impact 1k 0,579 bps et 5k 1,278 bps. Le carnet lui-meme est correct.
- Le probleme est le fee retail Kraken Pro Tier 1 : 0,40 % maker / 0,80 % taker d'apres le fee schedule public, soit 40 / 80 bps avant impact carnet. Le snapshot chiffre donc `PAXG/EUR` a ~80,579 bps pour 1k et ~81,278 bps pour 5k en taker Tier 1.
- Decision pratique : **ne pas transferer le PAXG existant hors Kraken juste pour optimiser**, sauf si un ordre/retrait precis justifie les frais et risques. Pour acheter du nouveau PAXG a bas cout, Kraken n'est pas le meilleur chemin ; pour conserver, revendre en EUR/USD, ou arbitrer un PAXG deja la, il est pertinent.

**Gemini / Bitstamp**

- Gemini a PAXG/USDC, PAXG/USD, PAXG/GUSD, mais spread mesure autour de 100 bps et profondeur 10 bps nulle : non competitif pour execution automatisee.
- Bitstamp a PAXG/USD/EUR, mais liquidite mesuree faible et pas de paire PAXG stablecoin exploitable : non competitif pour ce besoin.

## Matrice de decision par scenario

| Scenario | Reserve stablecoin | Route BTC/ETH | Route PAXG | Cout attendu | Risque cle | Lecture |
|---|---|---|---|---:|---|---|
| Sans Binance, USDT autorise | USDT | OKX BTC/USDT + ETH/USDT | KuCoin/OKX/Gate.io/Bitget PAXG/USDT selon devis | ~10 a 11 bps spot hors retrait dans les derniers snapshots | USDT / MiCA / disponibilite compte France | Meilleur chemin non-Binance chiffre aujourd'hui. |
| Sans Binance, reserve USDC mais execution USDT autorisee | USDC parking, conversion USDT ponctuelle | OKX BTC/USDC + ETH/USDC | USDC -> USDT -> PAXG/USDT | ~20,5 a 21,8 bps pour PAXG hors retrait | Double fee, USDT quand meme implique | Possible, mais beaucoup moins economique qu'une reserve USDT pour PAXG. |
| Sans Binance, USDT refuse / MiCA strict | USDC/EURC/EUR | OKX/Coinbase/Kraken/Bitpanda selon acces | Bitpanda Fusion a tester ; sinon Gemini PAXG/USDC non competitif ou PAXG/USD/EUR fiat | Non satisfaisant en stablecoin public ; Gemini impact marche seul >30 bps dans le dernier snapshot | Absence de paire PAXG stablecoin liquide | Viable seulement si Bitpanda Fusion prouve un bon devis, ou si on accepte fiat. |
| PAXG deja detenu sur Kraken | PAXG existant | Acheter BTC/ETH ailleurs selon cout ; eviter conversion inutile | Garder/revendre PAXG sur Kraken via EUR/USD si besoin | Pas de cout d'achat si deja detenu ; revente taker Tier 1 Kraken ~80 bps + impact | Frais Kraken Pro retail ; custody Kraken | Kraken devient utile comme lieu de position existante, pas comme route d'achat PAXG low-cost. |
| Fonds deja on-chain / wrapped accepte | USDC Base/Solana | `cbBTC` Base/Solana ou `WETH` Base | PAXG officiel Solana via Jupiter, ou Ethereum si gas acceptable | A chiffrer par routeur ; cbBTC liquidite observee 5-9,5 M USD selon pool ; PAXG Solana quote Jupiter disponible mais pool ~130 k USD | Issuer/custody wrapper, smart contracts, bridge/reseau, rate limits routeur | Piste a fort potentiel pour BTC ; PAXG prometteur mais plus fragile en liquidite. |
| BTC/ETH seulement | USDT/USDC | MEXC 0 % ou OKX | Non couvert | Potentiellement minimal | PAXG absent, region/API a verifier | Ne repond pas au besoin complet, mais peut servir de route specialisee. |

Lecture dure : **le besoin PAXG impose la strategie**. BTC et ETH ont assez de liquidite partout ; l'optimisation se joue sur la paire PAXG disponible, son stablecoin quote, et la contrainte UE/MiCA.

## Decision provisoire

Decision provisoire, a valider avec les contraintes de Clem :

1. **Binance est exclu du plan cible.** Garder ses chiffres comme benchmark uniquement ; ne pas en faire un provider d'execution dans la roadmap TradeIO5 tant que son statut Europe/MiCA n'est pas resolu et verifie sur le compte cible.
2. **Si USDT est acceptable et tradable : strategie non-Binance recommandee = OKX pour BTC/ETH, puis routeur PAXG entre KuCoin/OKX/Gate.io/Bitget.** Le snapshot du 2026-10-09 donne OKX `BTC/USDT` ~10,006 bps, OKX `ETH/USDT` ~10,020 bps, et KuCoin `PAXG/USDT` ~10,180 bps a 1k / ~10,945 bps a 5k. Le routeur doit recalculer parce que PAXG bouge vite.
3. **Si USDT est refuse ou bloque par MiCA : aucune route PAXG stablecoin publique hors Binance n'est satisfaisante.** OKX/KuCoin/Gate.io/Bitget/Crypto.com ont PAXG surtout en USDT ; Gemini a `PAXG/USDC`, `PAXG/GUSD`, `PAXG/RLUSD`, mais `PAXG/USDC` a un spread/impact prohibitif dans les snapshots. Bitpanda Fusion devient alors le candidat principal a tester par API authentifiee.
4. **Si la reserve reste USDC mais que l'execution PAXG accepte USDT : ne convertir qu'au moment de l'ordre, et seulement si le cout total reste sous plafond.** Les routes `USDC -> USDT -> PAXG` hors Binance sortent autour de 20,5-21,8 bps dans le snapshot, donc elles sont nettement plus cheres qu'une reserve directement en USDT pour PAXG.
5. **MEXC peut etre une optimisation BTC/ETH uniquement**, grace a l'annonce 0 % spot, mais ne resout pas le triptyque car PAXG/USDT n'a pas ete trouve via l'API publique. A ne pas choisir comme exchange principal de la strategie PAXG sans verification compte + region + paire.
6. **Ajouter un profil wrapped/on-chain, mais explicite.** `USDC -> cbBTC` sur Base/Solana peut reduire fortement les couts reseau/custody pour BTC si cbBTC est accepte comme actif final ou redepose chez Coinbase. `USDC -> PAXG` sur Solana est une vraie piste parce que Paxos supporte officiellement PAXG Solana, mais elle doit rester sous seuil de liquidite/slippage et ne pas remplacer silencieusement PAXG natif CEX/Kraken.
7. **Strategie d'execution recommandee pour TradeIO5 : smart routing simple, pas market order aveugle.**
   - Lire carnet avant chaque ordre.
   - Calculer cout pre-trade : fee compte + demi-spread + slippage pour la taille reelle.
   - Poser d'abord un ordre limit maker/post-only quand le signal n'est pas urgent.
   - Basculer en taker limite IOC seulement si le signal expire ou si le cout reste sous un plafond configure.
   - Pour PAXG, bloquer ou fractionner si slippage estime > seuil (ex. 2 bps hors frais pour ordre retail ; seuil a definir).
8. **Integration cible : commencer par un `ExecutionCostEstimator` avant tout vrai passage d'ordre.** Le premier lot code devrait seulement comparer les venues/paires et produire un devis chiffre ; l'execution reelle vient apres.

## Implications API pour TradeIO5

Le projet a deja un contrat `MarketDataApiClient` pour les candles publiques et un `ProviderApiClient` pour les appels authentifies. Il a aussi un `BinanceOrderBookApiClient` public qui lit ponctuellement `GET /api/v3/depth` et mappe vers `OrderBookSnapshot`. L'execution spot ne devrait pas etre greffee directement sur ces contrats : il manque une couche dediee au devis et a l'ordre.

Cette etude s'inscrit dans le trou documente par `docs/known-gaps/decision-to-order-gap.md` : TradeIO5 produit des decisions et des `ActionStep`, mais ne transforme pas encore ces intentions en ordres reels. Verification code au 2026-10-08 : `ActionStep` porte une action et une quantite, `ExecutionResult` existe mais reste minimal, et `ActionStepExecutedCause` ne transporte pas encore le resultat d'execution. La premiere brique a ajouter ne doit donc pas etre "placer un ordre", mais **produire un devis execution-safe**.

Proposition de decoupage futur :

- `OrderBookClient` public : lit les carnets spot normalises par venue/paire, proche de `BinanceOrderBookApiClient` mais multi-exchange.
- `ExecutionCostEstimator` pur : prend un carnet, une taille, un fee schedule et calcule spread, slippage, frais, cout total bps, min/max fill.
- `TradingFeeProvider` : expose les frais effectifs du compte quand l'exchange le permet ; sinon fallback configure/documente.
- `SpotExecutionClient` authentifie : place/cancel/list ordres, separe de la lecture carnet.
- `ExecutionRouter` : choisit la route active selon cout, contraintes stablecoin, region, seuils de risque, taille d'ordre et disponibilite exchange.

Les scripts de recherche prefigurent ces composants :

| Script | Futur composant inspire | Donnees utiles |
|---|---|---|
| `stablecoin_trade_cost_snapshot.py --json` | `ExecutionCostEstimator` + `ExecutionRouter` | Carnets publics, impact par taille, cout total bps, meilleure route par profil. |
| `exchange_pair_inventory.py --json` | `InstrumentCatalog` / `TradablePairRegistry` | Paires actives par venue, stablecoins communs a BTC/ETH/PAXG, symboles exchange. |
| `stablecoin_risk_snapshot.py --json` | `StablecoinRiskProvider` | Market cap, peg bps, variation d'offre, mecanisme, concentration par chain. |
| `wrapped_asset_liquidity_snapshot.py --json` | `DexQuoteProbe` / `WrappedAssetRouteRegistry` | Liquidite DEX observable, quotes Jupiter Solana, risques wrapper/chain. |

Endpoints authentifies a exploiter pour remplacer les frais publics par les frais reels du compte :

| Exchange | Endpoint/API utile | Usage |
|---|---|---|
| Binance | `GET /api/v3/account/commission` et `POST /api/v3/order/test?computeCommissionRates=true` | Obtenir la commission effective du symbole et simuler les frais d'un ordre precis sans l'envoyer au matching engine. |
| OKX | Account API `get_fee_rates(instType=SPOT, instId=...)` | Obtenir les maker/taker fees applicables au compte et a l'instrument. |
| KuCoin | `GET /api/v1/trade-fees` | Obtenir l'actual fee rate de la paire pour le compte. |
| Bitstamp | Endpoint API fees par marche | Verifier le fee tier reel par symbole si Bitstamp reste dans les fallbacks. |

Point d'architecture : le cout affiche a l'utilisateur doit toujours venir d'un `ExecutionQuote` calcule juste avant ordre, pas d'une constante de documentation.

Contrat minimal suggere pour le futur `ExecutionQuote` :

| Champ | Role |
|---|---|
| `venue` / `symbol` / `baseAsset` / `quoteAsset` | Route choisie. |
| `side` / `quoteAmount` / `baseQuantityEstimate` | Intention chiffree. |
| `bestBid` / `bestAsk` / `midPrice` | Etat du carnet a l'instant du devis. |
| `spreadBps` / `buyImpactBps` / `sellImpactBps` | Cout de marche hors frais. |
| `makerFeeBps` / `takerFeeBps` / `totalCostBps` | Cout complet estime. |
| `liquidityDepth10Bps` / `maxFillUnderLimit` | Garde-fou profondeur/fill. |
| `stablecoinRiskProfile` / `routeRiskFlags` | USDT opportuniste, USDC conservative, DEX, fiat fallback, etc. |
| `wrapperRiskProfile` | Aucun, cbBTC/Coinbase, WBTC/custodian, PAXG/Paxos Solana, WETH/L2, tBTC/protocol. |
| `expiresAt` | Interdit d'executer un vieux devis. |
| `recommendedOrderType` | `LIMIT_MAKER`, `LIMIT_IOC`, `BLOCKED`, `SPLIT`. |

Si les retraits font partie du workflow, ajouter un second objet au devis :

| Champ `WithdrawalQuote` | Role |
|---|---|
| `asset` / `network` / `venue` | Actif final, reseau de sortie, exchange source. |
| `withdrawFeeAsset` / `withdrawFeeQuoteEquivalent` | Fee exact dans l'actif et equivalent stablecoin au prix du devis. |
| `withdrawMin` / `withdrawMax` / `withdrawEnabled` | Validite operationnelle. |
| `addressAllowlisted` / `travelRuleRequired` | Blocages possibles avant execution. |
| `effectiveCostBpsOnOrder` | Fee de retrait ramene au montant de l'ordre, pour comparer retrait immediat vs groupe. |
| `fetchedAt` / `expiresAt` | Les frais reseau doivent etre frais, comme le carnet. |

Garde-fous a documenter avant execution reelle :

- pas de `market order` aveugle ;
- `post-only` prioritaire si la strategie peut attendre ;
- `limit IOC` seulement avec plafond `maxTotalCostBps` ;
- blocage automatique si le carnet PAXG est sous un seuil de profondeur ou si le slippage simule depasse le seuil ;
- USDT desactive par defaut en profil `EU_CONSERVATIVE`, activable explicitement en profil `LOWEST_COST_ACCEPTS_USDT` ;
- route wrapped desactivee par defaut sauf profil `ONCHAIN_USDC_WRAPPED` ou acceptation explicite du risque wrapper ;
- en profil `EU_CONSERVATIVE`, si `PAXG/USDC` depasse le plafond de cout, la decision doit etre `BLOCKED` ou `SPLIT`, pas bascule silencieuse vers `PAXG/USDT` ;
- en profil `LOWEST_COST_ACCEPTS_USDT`, comparer `PAXG/USDC`, `PAXG/USDT` et eventuellement les fallbacks USDT avant chaque ordre, puis choisir le cout total le plus bas sous seuil.
- en profil `ONCHAIN_USDC_WRAPPED`, comparer routeur DEX, gas, frais bridge/depot/retrait, et cout d'unwrap/depot CEX avant de declarer la route meilleure qu'un CEX.

## Prochaines verifications

- Repondre aux questions de Clem : taille d'ordre, juridiction exacte du compte, acceptation USDT, acceptation Binance/Crypto.com/Bitpanda, custody exchange ou retrait.
- Tester Bitpanda Fusion en conditions reelles si un compte/API key est disponible : quote USDC -> BTC/ETH/PAXG, frais inclus, tailles 100/500/1000/5000.
- Verifier dans le compte cible les fees effectifs via API quand disponible (`account trade fee`, `test order computeCommissionRates`, historique fill).
- Verifier les frais de retrait exacts via endpoint authentifie avant toute conclusion "cout total custody incluse".
- Ajouter les devis routeur EVM exacts pour Base/Arbitrum/Ethereum (`0x`, `1inch`, `Uniswap/Aerodrome`) sur `USDC -> cbBTC`, `USDC -> WETH`, et eventuellement `USDC -> PAXG`.
- Stabiliser les quotes Jupiter Solana avec retry/rate-limit propre, puis comparer `USDC -> cbBTC` et `USDC -> PAXG` aux CEX sur 50/100/500/1000/5000 USDC.
- Refaire le snapshot de carnets plusieurs fois dans la journee pour mesurer la stabilite, surtout PAXG.
