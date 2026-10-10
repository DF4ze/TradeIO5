# Prompt — Analyse : branchement du vrai wallet utilisateur au Rainbow DCA ATR (lecture seule, aucun ordre)

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). **Mission = ANALYSE + proposition + questions, aucun code tant que Clem n'a pas validé.**

## 0. Démarrage
1. Lis `docs/README.md`, puis `architecture/01-overview.md`, `03-ownership-and-lifecycle.md`, `04-market-data.md`, `07-security.md`, `08-rainbow-bench-grandeur-nature.md`, `known-gaps/rainbow-dca-chantiers-ouverts.md`, `known-gaps/decision-to-order-gap.md`, `docs/CODING_RULES.md`, `glossary.md`.
2. Lis le code : `model/entity/currency/Wallet`, `ApiCredential`, `WebProvider`, `WalletSource`, `service/WalletService`, `service/connector/ProviderApiService` + `apiclient/*` (Binance, BinanceTestnet, Kraken : pas d'OKX privé), `service/tree/opinion/WalletSnapshotService` (agrège déjà les soldes réels multi-wallets : vérifie s'il est réellement appelé, `known-gaps` dit que `WalletSnapshot` n'est jamais peuplé), `BalanceCacheManager`, et côté bench : `model/entity/dca/bench/RainbowLiveMockWallet`, `RainbowLivePreset`, `service/dca/atr/bench/*` (surtout `RainbowLiveExecutionService`, `RainbowLiveRunService`).
3. Réponds en français, court et technique (flèches/symboles bienvenus). Un point flou → **pose la question avant d'avancer** (AskUserQuestion).

## 1. Objectif
Brancher le **vrai wallet** (soldes réels, **lecture seule**) au bench Rainbow : le moteur dimensionne l'action quotidienne sur le vrai cash / la vraie position au lieu du wallet fictif, **sans jamais passer d'ordre**. C'est le début du **capital commun** (préalable au slider « Taux d'exposition », au sizing réel et à l'exécution).

## 2. Décisions de Clem (2026-10-09) — ne pas les remettre en question
- **Wallet agrégé** : plusieurs exchanges possibles (achats réels BTC/ETH sur **OKX**, PAXG sur Kraken ; Binance utilisé pour les prix/historique). Il faut donc prévoir un moyen de **paramétrer le provider par token (actif)**.
- À noter pour plus tard (ne pas implémenter) : **découverte du wallet** quand un utilisateur connecte un nouvel exchange (détecter automatiquement quels actifs/stablecoins il y détient).
- **Un seul preset « live » par actif** est branché au vrai wallet ; les autres presets du même actif restent des **simulations** (wallet fictif, affichées en live comme aujourd'hui).
- **Début du capital commun** dans ce lot : le cash (stablecoin) est partagé entre actifs ; un actif qui tire trop réduit ce qui reste aux autres.
- **Stablecoin : USDC seul** pour l'instant ; à upgrader quand on fera la découverte d'exchange de l'utilisateur.
- **Liquidité insuffisante** : si le vrai cash ne permet pas l'achat → **les achats à venir sont bloqués** (pas de saut silencieux, pas d'ordre). Les ventes restent possibles dans la limite de la position réelle.
- **Plus tard (hors lot, prévoir seulement le point d'accroche)** : alerte utilisateur quand la part de liquidité (stablecoin) du wallet atteint X %.
- **Aucun ordre réel** : `ProviderApiService#buy/sell` (stubs `return true`) ne doivent être appelés par aucun chemin ; vérifie et propose un garde-fou (par ex. suppression ou levée d'exception).
- Un seul utilisateur actif pour l'instant, mais ne pas coder en dur (clé user conservée).

## 3. Ce que tu dois produire
1. Reformulation (10 lignes max) + coquilles/ambiguïtés/questions.
2. **État des lieux vérifié** : ce qui existe pour lire des soldes réels (clients, caches, `WalletSnapshotService`), ce qui manque (client **OKX privé** lecture seule : auth, endpoints solde, rate limits ; PAXG sur Kraken : nom de l'asset/paire), mapping token ↔ exchange/wallet/credential (aujourd'hui `asset_provider` ne concerne que le market data), sécurité des clés API (lecture seule, stockage, secrets gitignorés).
3. **Architecture proposée** : abstraction « source de cash/position » (`Mock` | `Real`) derrière laquelle le moteur dimensionne (plafond dans le service, moteur inchangé) ; sélection du preset live par actif (modèle de données, contrainte « un seul live par actif », bascule) ; calcul du cash commun entre actifs (ordre de passage des actifs dans le job, réservation de cash, cohérence si deux actifs veulent acheter le même jour) ; comportement quand le vrai solde diverge de la position du moteur (état de la machine vs position réelle) ; fraîcheur/cache des soldes à 23:55 UTC et en cas d'API indisponible (échec → pas d'achat, trace explicite) ; stockage d'un instantané du solde utilisé dans chaque run (traçabilité) ; page `userPage` (affichage vrai wallet vs fictif, indicateur « live », blocage d'achat par manque de liquidité) ; tests (horloge injectable, exchange simulé, aucun appel d'ordre).
4. **Feuille de route par lots** petits et testables, critères d'acceptation, avec doc à mettre à jour (`docs/` sans historique). Prévois le lot « client OKX lecture seule » et le lot « mapping token → provider » séparément du lot « moteur sur vrai cash ».
5. Questions ouvertes à poser à Clem (devise de valorisation, USDC sur OKX/Kraken ou autre quote, sous-comptes OKX, frais, arrondis/pas de quantité minimale par exchange, que faire des soldes hors BTC/ETH/PAXG, etc.).

## 4. Règles
- Respecter `docs/CODING_RULES.md` et le style de Clem (isEmpty()/getFirst(), constantes métier mutualisées, pas de variables redondantes, assertTrue/False directs).
- Demander avant de deviner sur les points de conception ouverts.
- Livrable : `docs/etudes/etude-wallet-reel-rainbow.md` + questions posées à Clem. Aucune modification de code.
