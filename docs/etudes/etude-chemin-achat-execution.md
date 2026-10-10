# Étude — chemin d'achat via API (passerelle USDT), plan d'ordres, Fee Test, exécution réelle

Date : 2026-10-10. Statut : **analyse seule** (aucun code, aucun ordre, aucun appel authentifié). Prérequis lot A (groupes d'actifs USD = USDC + USDT, `BindingCheck`, `ExecutionGuard`) : **présent dans le code** (vérifié). Vérification réelle du wallet OKX (clé Read) : **pas encore faite** (cf. `known-gaps/rainbow-dca-chantiers-ouverts.md` §5).

## 1. Reformulation

- But : acheter un actif A par API même sans paire directe avec le cash détenu, en cherchant un chemin de paires spot, **sans nœud fiat** (taxation), coût (frais + spread) = frein (Fee Test 0,2 % warning → 0,8 % rouge).
- Flux d'achat : USDT dispo ≥ montant ? oui → achat `A/USDT` ; sinon → échange du **manque** `USDC→USDT`, puis achat `A/USDT` ; demande enregistrée.
- Vente : produit reste en USDT (bag tampon). Cash = groupe USD, valorisé nominal 1:1.
- Dry-run **obligatoire** avant tout ordre : le moteur enregistre/affiche un plan non envoyé. Kill switch, plafonds, audit, double validation du 1er ordre réel.
- Cas réels : OKX PAXG (`PAXG-USDT` + `USDC-USDT`), Kraken PAXG = bloqué + warning (aucun chemin sans fiat).
- Hors lot : token natif, découverte wallet, alerte liquidité, Funding OKX, **retrait/transfert API jamais**.

### Coquilles / ambiguïtés relevées
1. « Fee Test déjà fixé » : les seuils sont une *proposition* de Clem (`etude-wallet-reel-rainbow.md`, « par ordre d'un seul côté »). Question : **chemin à 2 jambes** = somme des coûts, ou coût pondéré (la jambe pont ne porte que `manque/montant`) ?
2. **Seuil 0,2 % = taux taker OKX EEA « All pairs »** (0,100 % maker / 0,200 % taker, étude frais §Fee schedules) → un achat `PAXG-USDT` taker est *déjà* en warning avant spread. Seuil inclusif ? Maker obligatoire pour rester vert ?
3. « Produit de vente reste en USDT » : pour BTC/ETH, si la paire directe est `BTC-USDC`, vendre donne de l'USDC. Faut-il **forcer la vente sur `X-USDT`** (si paire existe) ou accepter le quote naturel ?
4. « USDC→USDT sans frais » : étude frais = `trade-fee` renvoie 0 % pour `USDC-USDT` sur le compte (mesuré) ; toujours lu via `trade-fee`, jamais supposé.
5. `BindingCheck` choisit le membre préféré (position 0 = USDC) : pour BTC/ETH il retourne `OK` via `X-USDC`, donc le flux « USDT d'abord » ne s'applique qu'à PAXG. Le moteur de chemin doit-il pouvoir préférer `X-USDT` pour consommer le stock USDT (même si USDC est préféré) ?
6. Le prompt cite `RECOMMENDED_NOT_EXECUTED` comme statut actuel : c'est **uniquement un libellé UI** (`rainbow-live.js`), pas un enum. Les enums actuels : `RainbowLiveAction {BUY,SELL,NONE,BLOCKED}`, `LiveBlockReason {NONE,INSUFFICIENT_CASH,UNAVAILABLE,NOT_TRADABLE_WITHOUT_FIAT}`, `PortfolioStatus {OK,STALE,UNAVAILABLE,NOT_TRADABLE}`.

## 2. État des lieux vérifié

### Code
| Élément | Constat |
|---|---|
| Ordres | **Aucun.** `ProviderApiService#buy/sell` supprimés. `ProviderApiClient` = soldes, prix, trades. `OkxApiClient#getTradesSince` = liste vide. |
| Garde « 0 ordre » | `ConnectorNoOrderMethodTest` : échoue si une méthode `buy|sell|placeorder|neworder|createorder|cancelorder` existe dans `service.connector.**` ; `RainbowLiveNoExchangeDependencyTest` : seules les impl. de `RainbowPortfolioSource` touchent l'exchange dans le bench. |
| OKX lecture | `OkxBalanceReader` : `GET /account/balance`, signature HMAC maison (`sign` package-private), `availBal`, cache 60 s, codes 501xx → `CredentialRejectedException`. Réutilisable pour signer `trade-fee`/ordres. |
| Instruments | `SpotInstrumentChecker` (OKX `instruments?instId=`, Kraken `AssetPairs`) : booléen seulement, **ni minSz/lotSz/tickSz, ni cache**, 1 appel réseau par paire à chaque `BindingCheck` (chaque passe) — contraire à la politique cache DB. |
| Groupes | `asset_group`/`asset_group_member` (USD : USDC pos 0, USDT pos 1). `Asset` n'a **aucun indicateur fiat** → liste de nœuds interdits à créer. |
| Cash | `PortfolioReading.cashByMember` (USDC/USDT séparés, persisté `live_cash_detail`) ; `CashLedger` = pool **sommé** ; positions limitées à BTC/ETH/PAXG. |
| Garde exécution | `ExecutionGuard.requireExecutable` : statut persisté **== OK** seulement ⇒ `TRADABLE_VIA_BRIDGE` refusé (voulu tant que le lot n'existe pas). |
| Action live | `LiveSizing` : `liveActionAmountUsdc` (USD nominal), `liveActionQuantity = montant/close`, figés au 23:55 (rejeu : action d'origine conservée). |
| Credentials | `ApiCredential` : **unique (user, webProvider)**, `apiKey/secretKey/passphrase` **en clair**, un seul `credential` par `Wallet`. |
| Trades | `Transaction` (`externalTransactionId` unique, `fee` sans devise de frais), `TransactionSyncInitializer`. |
| DTO | `ExecutionResult(orderId, executedQuantity, averagePrice, fees)` (branche décisionnelle, minimal). |

### Clés
- Clé OKX actuelle = **Read** (props dev gitignorées → base par `ApiCredentialInitializer` si absente). Elle suffit pour : `trade-fee`, `balance`, endpoints publics. **Elle ne peut pas passer d'ordre.**
- ⚠ **Bloquant de modèle** : une 2ᵉ clé (Trade) pour le même (user, OKX) viole `uk_credential_user_provider`, et `Wallet` ne référence qu'une credential. Il faut un `scope` (READ|TRADE) sur `ApiCredential` + contrainte (user, provider, scope) et un lien wallet→credential de trading (ou résolution par scope). Impact Flyway (V1 non activé : cf. `operations/flyway.md`) et règle des défauts d'Initializer.
- Secrets en clair en base : acceptable en lecture seule, **plus pour un droit Trade** → chiffrement au repos (AES-GCM, clé maître hors base) à décider.

### OKX (API v5, à reconfirmer dans la doc officielle au moment du lot — pas d'accès réseau pendant cette analyse)
- Instruments (public) : `PAXG-USDT` live (minSz 0,001 / lotSz 0,000001 / tickSz 0,1) ; `USDC-USDT` live (minSz 1 / tickSz 0,00001) ; `PAXG-USDC` absente (faits du prompt, 2026-10-10). `BTC-USDC`, `ETH-USDC` : présents dans le snapshot de l'étude frais du 2026-10-09 (OKX BTC/USDC ≈ 10,0 bps, ETH/USDC ≈ 10,4 bps) — à revérifier par `instruments`. Champs utiles : `minSz`, `lotSz`, `tickSz`, `maxMktSz`, `maxLmtSz`, `state`.
- Frais : `GET /api/v5/account/trade-fee?instType=SPOT&instId=…` (auth, Read). Valeurs maker/taker **signées** (négatif = frais payé, positif = rebate) → normaliser. Cadence : à rejouer au plan, pas à chaque seconde (limite faible, ~5 req/2 s).
- Ordre : `POST /api/v5/trade/order` (`instId`, `tdMode=cash`, `side`, `ordType` ∈ market | limit | post_only | fok | ioc, `sz`, `px`, `tgtCcy`, `clOrdId`). Spot market buy : `sz` en devise de cotation par défaut (`tgtCcy=quote_ccy`), sell en base. Réponse = **accusé asynchrone** (`ordId`, `sCode`) : le remplissage se lit via `GET /api/v5/trade/order` (états `live`, `partially_filled`, `filled`, `canceled`) et `GET /api/v5/trade/fills` (`fillPx`, `fillSz`, `fee`, `feeCcy`). Annulation : `POST /api/v5/trade/cancel-order`.
- `clOrdId` : alphanumérique, ≤ 32 car., fourni par nous ⇒ **clé d'idempotence** (doublon rejeté, retrouvable par `clOrdId`).
- **Frais prélevés en général dans l'actif reçu** (achat : en base ⇒ quantité nette < `sz`/prix ; vente : en quote). À vérifier sur un relevé (déjà noté dans `etude-wallet-reel-rainbow.md`).
- Solde : `availBal` vs `frozenBal` (ordre ouvert gèle les fonds). Trading seulement (Funding exclu).
- Taille minimale : `minSz` en base ; montant minimal en quote **non exposé par `instruments`** → déduire `minSz × prix` + erreurs `sCode` d'ordre (à tester en dry-run démo).
- **Pas d'endpoint « test order » spot** : le dry-run côté exchange passe par le **trading démo OKX** (clés démo séparées + en-tête `x-simulated-trading: 1`) — à confirmer ; sinon dry-run = pur calcul local.
- Rate limits (ordre de grandeur, à confirmer) : ordre ~60/2 s par instrument, balance 10/2 s, instruments 20/2 s, books 40/2 s ; coût négligeable pour 3 actifs/jour mais **à mettre derrière un throttle** (règle CODING_RULES : boucle + provider externe).
- Carnet public : `GET /api/v5/market/books?instId=…&sz=…` (profondeur → slippage estimé) ; `/market/ticker` pour bid/ask rapide (déjà utilisé par `OkxApiClient#getMarketPrice`).

### Kraken (pour le jour où une paire existe)
`POST /0/private/AddOrder` (`userref`, `cl_ord_id`, **`validate=true` = dry-run natif**), `QueryOrders`/`TradesHistory`, frais `TradeVolume` (`fee-info`), contraintes `AssetPairs` (`ordermin`, `lot_decimals`, `costmin`, `tick_size`). PAXG : seulement USD/EUR/XBT/ETH ⇒ aucune route sans fiat depuis USDC/USDT (`NOT_TRADABLE_WITHOUT_FIAT`, confirmé par le prompt). Hors cible immédiate. Binance : exclu du plan (étude frais), pas de wallet.

### Ce qui change dans les gardes
- `ConnectorNoOrderMethodTest` : le client d'ordres **ne peut pas** vivre dans `service.connector` sans le casser. Placer dans un package dédié (ex. `service.execution.exchange`) et réécrire le test en deux gardes : (1) `service.connector.**` reste sans ordre ; (2) seules les classes de `service.execution.exchange` ont des méthodes d'ordre, et **aucune classe hors `service.execution` ne les appelle** (ArchUnit-like par scan, comme l'existant).
- `RainbowLiveNoExchangeDependencyTest` : le bench ne doit toujours rien savoir des ordres ⇒ le plan vit **hors** du package bench et lit les snapshots `live*` en base.
- `ExecutionGuard` : étendre (pas affaiblir) — exiger `OK` **ou** `TRADABLE_VIA_BRIDGE` seulement si le chemin est calculé, le flag d'exécution actif et le kill switch levé.
- Doc `07-security.md` (« clé Read uniquement, jamais Trade ») et `decision-to-order-gap.md` à mettre à jour au lot (c).

## 3. Architecture proposée

Découpage (un package `service/execution/`, jamais importé par le bench) :

```
InstrumentCatalog (DB) ─┐
TradingFeeProvider ─────┼─> PathFinder ─> PathQuote(coût bps) ─> OrderPlanner ─> OrderPlan (DB, dry-run)
OrderBookClient (public)┘                                              │
                                      KillSwitch/Limits ─> OrderExecutor ─> SpotOrderPort (OKX | Fake)
```

### 3.1 Graphe de paires (par provider ; par user seulement pour les frais)
- **Nœuds** = devises ; **arêtes** = paires spot `state=live` (orientées : acheter = quote→base au *ask*, vendre = base→quote au *bid*). Nœuds interdits = ensemble de devises fiat (config + constante mutualisée ; `Asset` n'a pas de flag fiat — ajouter une liste `FORBIDDEN_NODES` côté code/groupe ou un champ `Asset.kind`; question ouverte).
- **Sources** = membres du groupe USD détenus (solde `cashByMember`), **cible** = actif. Dijkstra à coût minimal (bps), **profondeur ≤ 2 sauts** (borne dure, 3 refusé sans décision explicite). Ex. PAXG/OKX : `USDT→PAXG` (1 saut) ; `USDC→USDT→PAXG` (2). Sans chemin ⇒ `NO_PATH` ⇒ BLOCKED + warning utilisateur.
- **Cache instruments en base** (politique cache DB de Clem) : table `exchange_instrument(provider, inst_id, base, quote, state, min_sz, lot_sz, tick_sz, fetched_at)`, un seul appel `instruments?instType=SPOT` (toutes paires) rafraîchi ≤ 1×/jour ou sur erreur d'ordre ; remplace aussi les appels unitaires de `SpotInstrumentChecker`/`BindingCheck`. Le graphe est reconstruit depuis la base (pas de requête réseau pour le chercher).

### 3.2 Coût d'un chemin
`coût_bps(jambe) = frais_effectif_bps(taker|maker) + demi-spread_bps + slippage_bps(montant)`, `coût_chemin = Σ coût_bps(jambe)` — **somme brute** (décision Clem 2026-10-10 : frais pont + achat additionnés en entier, sans pondération par `manque/montant`).
- Frais : `trade-fee` du compte (jamais supposés), normalisés en bps ; maker vs taker selon `ordType`.
- Spread/slippage : parcours du carnet (`books`) au montant réel (prix moyen simulé vs mid) ; repli ticker (bid/ask) si carnet indisponible → marqué `estimation=TICKER`.
- Arrondis : `sz` arrondi au `lotSz` (⌊⌋ à l'achat en base, ⌈⌉ pour couvrir un manque), prix au `tickSz`, rejet si `< minSz` ou montant en quote sous le minimum ⇒ jambe `BELOW_MIN` ⇒ plan BLOCKED (pas de gonflage silencieux).
- Fee Test (service pur, seuils configurables, défauts 0,2 % / 0,8 %) : `GREEN < warn ≤ WARNING ≤ red < RED`. WARNING ⇒ plan produit + alerte ; RED ⇒ BLOCKED. Rejoué à chaque plan et stocké (dérive visible). Slippage **réellement subi** = `fillPx` moyen vs mid au moment du plan, enregistré après exécution (seul vrai slippage mesurable).

### 3.3 Plan d'ordres (entité persistée, dry-run par défaut)
Plan **par utilisateur et par passe**, pas par actif : BTC (USDC direct) puis PAXG (via USDT) se partagent les soldes ⇒ le planner simule un **bilan virtuel par membre** (`USDC`, `USDT`) en parcourant les actifs par `priority`, comme `CashLedger`.
- Étapes types : `S1 SWAP USDC→USDT` (vente `USDC-USDT`, `sz` = ⌈manque/prix⌉ borné par `minSz`/`lotSz`, + marge de frais) ; `S2 BUY A/USDT` (`tgtCcy=quote_ccy`, montant = `liveActionAmountUsdc`) ; vente : `SELL A/quote` (produit conservé tel quel).
- Entités : `rainbow_live_order_plan` (user, run/jour/passe, statut, mode DRY_RUN|LIVE, coût bps, niveau Fee Test, `expires_at`, hash des entrées) + `rainbow_live_order_step` (rang, `inst_id`, side, `ord_type`, `sz`, `px`, `cl_ord_id`, statut, `ord_id`, qtés/prix/frais réels, `fee_ccy`, slippage réel, réponse brute tronquée). Journal d'audit = ces lignes **immuables** (append des transitions, pas d'update destructif).
- **Idempotence** : `clOrdId` déterministe `t5` + userId(base36) + actif + `yyyyMMdd` + pass + étape (alphanum ≤ 32). Rejouer le plan = même ids ⇒ OKX rejette le doublon ⇒ on **réconcilie** par `GET order?clOrdId=` au lieu de renvoyer. Jamais de renvoi avant d'avoir lu l'état (statut `UNKNOWN` après timeout).
- **Machine d'états** (plan) : `PLANNED → (APPROVED) → EXECUTING → EXECUTED | PARTIAL | FAILED`, + `BLOCKED` (Fee rouge, pas de chemin, sous-minimum, plafond, kill switch), `EXPIRED` (plan > TTL), `CANCELLED`. Étape : `PENDING → SUBMITTED → FILLED | PARTIAL | REJECTED | CANCELED | UNKNOWN`.
- **Échec partiel** : `S1` rempli, `S2` KO ⇒ le stock USDT **reste** (aucun retour arrière automatique, décision Clem) ; plan `PARTIAL`, alerte, `S2` ne se réessaie que par nouvelle décision explicite (même `clOrdId`, après réconciliation) ou au cycle suivant (le stock USDT sera alors vu comme cash). Achat partiellement exécuté : politique à trancher (question).
- Re-devis juste avant chaque étape (`ExecutionQuote` à `expiresAt` court, jamais un vieux devis) ; coût > plan + tolérance ou > rouge ⇒ arrêt de l'étape suivante.
- Ordres : défaut proposé = **limit IOC plafonné** (prix = ask/bid du devis ± tolérance slippage) pour exécution « maintenant » ; `post_only` possible pour réduire à maker (0,10 % vs 0,20 % EEA) avec fenêtre courte puis annulation. Jamais de `market` aveugle (cohérent avec l'étude frais).

### 3.4 Comptabilité du stock USDT
- Aucune nouvelle logique de cash : le pool reste **Σ membres** (nominal, `CashLedger` inchangé) ; la répartition USDC/USDT n'importe qu'au **planner** (qui consomme `cashByMember`). Après exécution, les soldes réels (lus à la passe suivante) reflètent le stock USDT.
- Vente : produit crédité dans la quote de la paire vendue ; non utilisable le jour même (règle existante : ventes non créditées le jour même). `bagPercent` ne s'applique qu'à la **position** de l'actif, pas au stock USDT.
- Risque assumé : peg USDT≠USDC ignoré (valorisation nominale) ; le spread `USDC-USDT` est inclus dans le coût du chemin donc visible.

### 3.5 Sécurités
- Clé **Trade seulement, jamais Withdraw**, IP VPS whitelistée, `ApiCredential.scope` (cf. §2), chiffrement au repos, aucun secret loggué (règle existante).
- Plafonds : par ordre (`maxNotionalUsd`), par jour et par user, plafond **absolu en constante** non surchargeable par la base. Dépassement ⇒ BLOCKED.
- **Interrupteurs** : global (`tradeio.execution.mode = OFF|DRY_RUN|LIVE`, **défaut DRY_RUN**, `OFF` coupe aussi le dry-run), par binding (`executionEnabled`, défaut false). Kill switch lisible sans redéploiement (flag en base + endpoint admin).
- **Double validation** du 1ᵉʳ ordre réel d'un binding : montant minimal, admin arme explicitement (`firstLiveApprovedAt`), puis confirmation du user ; seulement après, les plafonds normaux.
- Qui active : ADMIN pour le LIVE global ; user pour son propre binding une fois armé (question).
- Presets non-live/mock : impossible par construction — le planner ne lit que `LiveSlot`/binding ; l'exécuteur appelle `ExecutionGuard` + `SpotOrderPort` seul point d'ordre ; test d'archi (§2).
- Audit : plans/étapes immuables + réponses exchange (sans secret).

### 3.6 Interaction avec le bench
- Le bench (23:55) **fige l'action recommandée** (inchangé, aucune dépendance ordre). Un **job séparé** `ExecutionPlanJob` (cron, désactivé par défaut comme les autres) lit les runs du jour, construit le plan (dry-run) **juste après 23:55** (prix proche du close utilisé pour dimensionner ; l'action 00:05 reste diagnostic, jamais exécutée). Exécution (si LIVE) par `ExecutionJob`, TTL court ; sinon `EXPIRED`.
- Rejeu 23:55 = action d'origine conservée ⇒ plan identique (mêmes `clOrdId`) ⇒ idempotent.
- Statuts : les enums actuels (`RainbowLiveAction`, `LiveBlockReason`) décrivent la **recommandation** et restent tels quels ; ajouter `NO_PATH`/`COST_TOO_HIGH` à `LiveBlockReason` n'est pas nécessaire (porté par le plan). Nouveaux statuts d'exécution (`PLANNED`, `EXECUTED`, `PARTIAL`, `FAILED`, `BLOCKED`, `EXPIRED`) sur le **plan**, pas dans `RainbowLivePassBlock` (déjà large : colonnes `t2355_live_*`).
- Page : bloc « Plan d'exécution » du live-wallet (plan, coût, niveau Fee Test, warnings, interrupteurs) ; l'étiquette « recommandée, non exécutée » devient dépendante du statut du plan.

## 4. Feuille de route proposée (point avec Clem après chaque étape)

| Lot | Contenu | Critères d'acceptation |
|---|---|---|
| **a** | `InstrumentCatalog` (cache DB, 1 appel/jour) ; `TradingFeeProvider` OKX (`trade-fee`, clé Read) ; graphe + `PathFinder` (nœuds interdits, ≤ 2 sauts) ; coût + `FeeTest` pur ; endpoint de lecture. **Aucun ordre.** | PAXG/OKX = `USDT→PAXG` ou `USDC→USDT→PAXG` avec coût chiffré par frais réels ; PAXG/Kraken = `NO_PATH` + warning ; `BindingCheck` lit le catalogue (plus d'appel unitaire) ; garde « 0 ordre » intacte ; tests purs sur fixtures. |
| **b** | `OrderPlanner` + entités plan/étapes, bilan virtuel, arrondis lot/tick/min, `clOrdId`, `ExecutionPlanJob` désactivé ; affichage page. **Dry-run enregistré, rien envoyé.** | Plan reproductible et idempotent au rejeu 23:55 ; cas manque partiel/BLOCKED/sous-minimum couverts ; aucun import de `SpotOrderPort`. |
| **c** | `ApiCredential.scope` + chiffrement ; `SpotOrderPort` + `OkxSpotOrderClient` (package dédié) **désactivé par défaut**, `FakeExchange` de test ; machine d'états, réconciliation, kill switch, plafonds ; nouveaux tests d'architecture. | Suite testée sur faux exchange (rempli, partiel, rejet, timeout/UNKNOWN, doublon) ; `OFF/DRY_RUN` n'émettent jamais ; garde archi à jour. |
| **d** | Clé Trade guidée avec Clem ; éventuel essai démo OKX ; **1ᵉʳ ordre réel au montant minimal**, sous contrôle de Clem (double validation). | Ordre visible côté OKX, fills + frais réels réconciliés, slippage réel mesuré, `Transaction` cohérente. |
| **e** | Page : plan, coût, warnings, interrupteurs ; historique/audit. | Rendu testé comme `MainControllerUserPageTest`. |

Hors feuille : sync `fills` → `Transaction` (frais avec devise), token natif, alerte liquidité.

## 5. Décisions de Clem (2026-10-10, après analyse)

- Ordre par défaut : **limit IOC plafonné** (jamais market aveugle).
- Fee Test d'un chemin multi-jambes : **somme brute** des coûts (seuils 0,2 % / 0,8 % appliqués à la somme).
- Vente : **produit forcé en USDT** (`X-USDT`) quand la paire existe.
- Clé Trade : modèle `ApiCredential.scope` (READ|TRADE) + chiffrement au repos (solution 1) ; **Clem veut être accompagnée** pour la création/sécurisation de la clé (lot d).

## 6. Questions ouvertes — défauts proposés **validés par Clem (2026-10-10)**, repris dans `prompts/prompt-roadmap-chemin-achat-execution.md`

1. Tolérance de slippage du limit IOC ? (0,05–0,1 %).
2. Achat partiellement exécuté : accepter le partiel, ou annuler le reliquat ? (accepter + journaliser, reliquat reporté au cycle suivant).
3. Plafond par ordre / par jour ? (par ordre = 1 `baseAmount` ; jour = 2×).
4. Qui active le réel : ADMIN seul, ou user sur son binding une fois armé ? (admin arme, user active).
5. Spread : carnet (profondeur au montant) ou ticker ? (carnet, repli ticker).
6. BTC/ETH sur OKX : paires USDC directes ? (snapshot du 09/10 : oui, à revérifier par `instruments`) ; et faut-il y préférer `X-USDT` pour consommer le stock USDT ?
8. Convertir un jour le stock USDT en USDC (ordre inverse `USDT→USDC`) ? (non dans ce lot).
9. Fréquence de relecture des frais ? (à chaque plan + cache 24 h).
10. Frais qui montent entre plan et exécution : arrêt si > rouge ou > plan + X ? (re-devis avant chaque étape ; stop si rouge ou > plan + 0,05 pt).
11. Fee Test : seuil 0,2 % inclusif ? maker exigé pour rester vert ? compte OKX EEA confirmé (0,2 % taker, 0,1 % maker).
12. Clé maître de chiffrement : variable d'environnement du VPS ? (oui, à cadrer avec Clem au lot c/d).
13. Fiat : liste de devises interdites en config/constante, ou champ `Asset.kind` ?
14. Dry-run exchange : essayer le trading démo OKX (clés démo à créer) ou rester sur calcul local jusqu'au lot (d) ?
