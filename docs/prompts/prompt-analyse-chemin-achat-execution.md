# Prompt B — Analyse : moteur de chemin d'achat via API (passerelle USDT), plan d'ordres, Fee Test — puis exécution réelle

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). **Mission = ANALYSE + proposition + questions, aucun code et AUCUN ORDRE tant que Clem n'a pas validé.** Prérequis : le lot A (`prompt-implementation-groupes-actifs-usd-usdt.md`) est livré et le wallet réel vérifié (clé OKX « Read »).

## 0. Démarrage
1. Lis `docs/README.md`, puis `architecture/03-ownership-and-lifecycle.md`, `05-external-providers.md`, `07-security.md`, `08-rainbow-bench-grandeur-nature.md`, `known-gaps/rainbow-dca-chantiers-ouverts.md` (§5, §5c, §8), `known-gaps/decision-to-order-gap.md`, `etudes/etude-wallet-reel-rainbow.md`, `etudes/etude-trading-stablecoin-frais-btc-eth-paxg.md`, `docs/CODING_RULES.md`, `glossary.md`.
2. Lis le code : `service/connector/ProviderApiService` + `apiclient/*` (OKX, Kraken, Binance : ce qui existe pour lire, et la **suppression volontaire** de `buy/sell`), `ConnectorNoOrderMethodTest`, `service/dca/atr/bench/` (`RealPortfolioSource`, `CashLedger`, `LiveSizing`, `RainbowLiveExecutionService`, `RainbowTrendLiveService`, blocs `t2355_live_*`), `service/dca/atr/binding/*` (`BindingCheck`, `SpotInstrumentChecker`), les groupes d'actifs du lot A, `model/entity/currency/Wallet`, `ApiCredential`, `Transaction` / `TransactionService`.
3. Réponds en français, court et technique (flèches/symboles bienvenus). Un point flou → **pose la question avant d'avancer** (AskUserQuestion).

## 1. Objectif
Pouvoir **acheter un actif via API** même quand il n'a pas de paire directe avec le cash détenu, en cherchant un **chemin de paires spot** et en **mesurant son coût** (frais + spread) comme frein. Premier cas réel : **PAXG sur OKX** (pas de `PAXG-USDC` ; `PAXG-USDT` et `USDC-USDT` existent), cash détenu en USDC.

## 2. Faits vérifiés (2026-10-10, API publiques)
- OKX : `PAXG-USDT` live (minSz 0,001, lotSz 0,000001, tickSz 0,1) ; `USDC-USDT` live (minSz 1, tickSz 0,00001) ; `PAXG-USDC` absente.
- Kraken : PAXG uniquement en `USD`, `EUR`, `XBT`, `ETH` ⇒ **aucun chemin sans fiat** vers PAXG depuis USDC/USDT.
- Frais de `USDC-USDT` : annoncés 0 par Clem, **non vérifiés** (nécessite `GET /api/v5/account/trade-fee` avec clé).

## 3. Décisions de Clem (2026-10-10) — ne pas les remettre en question
- **Interdit : tout chemin passant par une monnaie fiat** (conversion fiat = événement taxable pour elle). Le moteur de chemin doit pouvoir exclure des nœuds (fiat) ; sans chemin ⇒ **bloqué + warning utilisateur**, jamais d'ordre (cas Kraken PAXG).
- **Flux voulu** pour un achat d'actif A : (1) le fonds nécessaire est-il disponible dans la devise de cotation directe (ex. USDT) ? oui ⇒ on l'utilise ; (2) non ou insuffisant ⇒ échange du **manque** `USDC→USDT` ; (3) achat `A/USDT` ; (4) demande d'achat enregistrée.
- **À la vente, le produit reste en USDT** (pas de retour en USDC) pour éviter les échanges multiples ; ce stock USDT sert de « bag » grossier aux achats suivants.
- **Les groupes d'actifs** (lot A) font du cash un groupe **USD = USDC + USDT** : le moteur de chemin opère à l'intérieur de ce groupe.
- **Le coût du chemin (frais + spread) est un frein** : au-delà des seuils du **Fee Test** (déjà fixés par Clem, en % : warning dès **0,2 %**, jusqu'à **0,8 %**, **zone rouge > 0,8 %**), on alerte puis on bloque.
- Surveiller le **slippage** en live si l'information est récupérable.
- **Toujours un dry-run avant l'exécution réelle** : le moteur produit d'abord un **plan d'ordres** enregistré et affiché, non envoyé.
- Règles du wallet réel à conserver : cash par pool de wallet, achat > cash disponible ⇒ tout-ou-rien `BLOCKED` + raison, panne ⇒ aucune action, `bagPercent` dynamique, mock conservé en parallèle, Earn compté comme disponible, un seul preset live par actif, rejeu 23:55 = action d'origine conservée.
- Credentials rattachées au **User** en base (jamais dans Flyway ni le binaire) ; clés de dev via properties gitignorées poussées en base si absentes.
- Hors lot, à ne pas coder : token natif d'exchange (remise de frais), découverte automatique du wallet, alerte % de liquidité, compte Funding OKX, transferts entre exchanges (**jamais de retrait/transfert API**).

## 4. Ce que tu dois produire
1. Reformulation (10 lignes max) + coquilles/ambiguïtés/questions.
2. **État des lieux vérifié** : ce qui existe pour passer des ordres (rien : `buy/sell` supprimés), clés actuelles (droits), endpoints OKX utiles (`POST /api/v5/trade/order`, `GET /api/v5/account/trade-fee`, `GET /api/v5/public/instruments`, ordre de marché vs limite, `clOrdId`, états d'ordre, solde disponible vs gelé), contraintes de taille (minSz, lotSz, tickSz, montant minimal en quote), rate limits ; équivalents Kraken (pour le jour où une paire existe) ; ce qui change dans le garde-fou « 0 ordre » et dans les tests d'architecture.
3. **Architecture proposée** :
   - **Graphe de paires** par (provider, utilisateur) : nœuds = devises, arêtes = paires spot `live` ; **recherche de chemin** de coût minimal avec nœuds interdits (fiat) ; cache des instruments en base (politique de cache DB de Clem) ; cas à 1 ou 2 sauts, borne de profondeur.
   - **Coût d'un chemin** : frais maker/taker par arête (lus via `trade-fee`, pas supposés), spread/book (profondeur) ou prix estimé, arrondis lot/tick ; agrégation en % du montant ; lien avec les seuils du Fee Test.
   - **Plan d'ordres** : séquence d'ordres (échange du manque `USDC→USDT`, achat `A/USDT`), quantités calculées avec arrondi au `lotSz`, `minSz` et montant minimal ; **idempotence** (`clOrdId` déterministe par user/actif/jour/étape), états, **reprise** après échec partiel (ex. `USDC→USDT` exécuté, achat échoué ⇒ le stock USDT reste, aucun retour arrière automatique), annulation, timeouts.
   - **Comptabilité du stock USDT** : lien avec le cash du pool (`CashLedger`), le groupe USD, `bagPercent`, l'achat tout-ou-rien ; vente ⇒ produit en USDT.
   - **Sécurités** : clé API dédiée (droits trading seulement, **jamais withdraw**), IP whitelistée, plafonds par ordre / par jour, interrupteur global et par preset (kill switch), journal d'audit complet (plan, ordres, réponses), mode dry-run par défaut, double validation avant le premier ordre réel, impossibilité pour le mock/les presets non-live d'envoyer un ordre.
   - **Interaction avec le bench** : où le plan s'insère (après la passe 23:55 qui fige l'action ? job séparé ?), statut `BLOCKED` / `RECOMMENDED_NOT_EXECUTED` actuel vs nouveaux statuts (`PLANNED`, `EXECUTED`, `PARTIAL`, `FAILED`).
4. **Feuille de route par lots** petits et testables, critères d'acceptation, avec un point avec Clem après chaque étape. Prévoir au minimum : (a) lecture des frais et graphe de paires + Fee Test **sans ordre** ; (b) plan d'ordres dry-run enregistré et affiché ; (c) client d'ordres derrière un port, **désactivé par défaut**, testé sur un faux exchange ; (d) premier ordre réel de **montant minimal** sous contrôle de Clem ; (e) page : plan, coût, warnings, interrupteurs.
5. **Questions ouvertes** à poser à Clem — au minimum : ordres **marché ou limite** (et tolérance de slippage) ; que faire d'un achat partiellement exécuté ; plafond par ordre / par jour ; qui peut activer l'exécution réelle (admin seulement ?) ; mesure du spread (carnet ou ticker) ; BTC/ETH sur OKX : paires USDC directes ? (à vérifier) ; faut-il convertir le stock USDT en USDC un jour (donc ordre inverse) ; fréquence de vérification des frais ; comportement si les frais montent entre le plan et l'exécution.

## 5. Règles
- Respecter `docs/CODING_RULES.md` et le style de Clem (isEmpty()/getFirst(), constantes métier mutualisées, pas de variables redondantes, assertTrue/False directs, `DomainClock`).
- Aucune modification de code ni aucun appel d'ordre pendant l'analyse. Les appels réseau de l'analyse se limitent aux endpoints **publics** (`instruments`) ; toute lecture authentifiée se fait avec la clé « Read » de Clem et son accord.
- Livrable : `docs/etudes/etude-chemin-achat-execution.md` (+ roadmap `docs/prompts/prompt-roadmap-chemin-achat-execution.md` après validation) + questions posées à Clem.
