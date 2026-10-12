# Prompt — Analyse : passerelle entre actifs NON stables (cas PAXG), USDT exclu

> À coller tel quel dans une nouvelle discussion (Claude Code / Cowork sur TradeIO-5). **Mission = ANALYSE + proposition + questions, aucun code et AUCUN ORDRE tant que Clem n'a pas validé.** Suite du lot B (`etudes/etude-chemin-achat-execution.md`, `prompt-roadmap-chemin-achat-execution.md`).

## 0. Démarrage
1. Lis `docs/README.md`, puis `architecture/08-rainbow-live.md`, `architecture/10-execution-plan.md`, `architecture/10-execution-reelle.md`, `known-gaps/rainbow-dca-chantiers-ouverts.md` (§5c), `etudes/etude-chemin-achat-execution.md`, `etudes/etude-trading-stablecoin-frais-btc-eth-paxg.md`, `etudes/etude-liens-strategie-provider-actif.md`, `etudes/etude-ux-admin-execution.md`, `docs/CODING_RULES.md`, `glossary.md`.
2. Lis le code : `service/market/instrument/` (`PathFinder`, `FeeTest`, `LegMarketSource`, catalogue d'instruments), `service/dca/atr/binding/` (`BindingCheck`, statuts), `service/execution/plan/OrderPlanner`, `service/execution/run/` (`SpotOrderRouter`, exécuteur), clients `KrakenSpotOrderClient` / OKX, `AssetGroupInitializer` + `AssetGroup`, `RealPortfolioSource`, `CashLedger`, `LiveSizing`, `LiveBlockReason` / `PlanBlockReason`.
3. Réponds en français, court et technique (flèches/symboles). Un point flou → **pose la question avant d'avancer** (AskUserQuestion).

## 1. Objectif
Pouvoir **acheter et vendre PAXG sur Kraken** (aujourd'hui `NOT_TRADABLE_WITHOUT_FIAT`) en passant par une **passerelle crypto non stable** : `USDC -> BTC (XBT) ou ETH -> PAXG` (paires Kraken `PAXG/XBT`, `PAXG/ETH` + `XBT/USDC`, `ETH/USDC`). Généraliser : le moteur de chemin doit accepter des **nœuds intermédiaires non stables**, avec le **Fee Test** comme juge du coût.

## 2. Faits connus (à re-vérifier par API publique avant de s'y fier)
- Kraken : PAXG coté seulement en `USD`, `EUR`, `XBT`, `ETH` ; BTC/ETH ont une paire USDC (déjà utilisée pour le 1er ordre réel côté OKX ; côté Kraken **jamais testé en réel**).
- OKX (compte EEA de Clem) : `PAXG-USDT` refusée (code 51155) ⇒ OKX n'est pas une route PAXG.
- `PathFinder` valorise aujourd'hui chaque jambe au **même montant nominal USD** parce que les intermédiaires sont des stables (`LegMarketSource#accepts`) ; ce n'est plus vrai avec un intermédiaire BTC/ETH.

## 3. Décisions de Clem — ne pas les remettre en question
- **Fiat toujours interdit** (taxation) : le chemin ne passe jamais par USD/EUR.
- **USDT est exclu** (considéré illégal en UE) : jamais utilisé comme source, passerelle, cotation préférée ni stock tampon. **Le groupe d'actifs USD est conservé** (mécanisme générique) mais ne contient que **USDC** pour l'instant. **Toute route/binding passant par USDT doit être supprimé ou rendu impossible** (catalogue de routes, `PathFinder`, `BindingCheck` / `TRADABLE_VIA_BRIDGE`, plans, bindings déjà en base). Les décisions du 2026-10-10 « produit de vente forcé en USDT » et « stock USDT » sont **caduques** : la vente revient en USDC. Audit exhaustif + plan de retrait propre (code, initializers, données existantes, statuts, docs, tests) sans rien casser.
- Ordres par défaut : limit IOC plafonné (jamais market aveugle). Fee Test d'un chemin multi-jambes = **somme brute** des coûts ; seuils en % : warning dès 0,2 %, zone rouge > 0,8 % ; RED **avertit seulement** pour l'instant (pas de blocage).
- Toujours dry-run/plan d'ordres avant le réel ; aucune sécurité contournée en test ; pas de retrait/transfert API.
- Cash par pool de wallet (par exchange) ; achat > cash disponible ⇒ tout-ou-rien `BLOCKED` + raison ; `bagPercent` dynamique ; un lien = (user, provider, actif).
- On attend de voir ce que donne le Fee Test avant d'automatiser quoi que ce soit : l'analyse doit **chiffrer** (en %, sur les tailles réelles de DCA) le coût `USDC->BTC->PAXG` et `USDC->ETH->PAXG` à partir des frais Kraken réels + carnets, pour que Clem juge.

## 4. Ce que tu dois produire
1. Reformulation (10 lignes max) + coquilles/ambiguïtés/questions.
2. **État des lieux vérifié** : ce que `PathFinder`/`FeeTest`/`BindingCheck`/`OrderPlanner` supposent de « stable » ; où USDT est encore présent (liste exhaustive fichier par fichier) ; ce qu'il faut côté Kraken (paires `XBT`/`ETH`, nommage `XBT` vs `BTC`, `ordermin`, `lot_decimals`, frais `TradeVolume`, `validate=true`).
3. **Valorisation d'un intermédiaire non stable** : conversion nominal USD -> quantité d'intermédiaire par les prix ask/bid de chaque jambe (plus de « même nominal » par jambe) ; coût d'une jambe en % du montant de départ ; arrondis lot/tick/min en cascade (poussière d'intermédiaire résiduelle) ; profondeur de carnet des paires croisées (`PAXG/XBT`, `PAXG/ETH` souvent peu liquides) et slippage.
4. **Risques propres à l'intermédiaire** : exposition en transit (prix BTC/ETH qui bouge entre jambe 1 et 2) ; échec partiel (jambe 1 faite, jambe 2 refusée ⇒ BTC/ETH résiduel) : que devient-il, comment ne pas fausser `bagPercent` / la position du lien BTC ou ETH / le cash du pool ; reprise idempotente (`clOrdId`) ; garde-fou si l'intermédiaire est lui-même un actif suivi (BTC/ETH sur le même provider).
5. **Vente PAXG** : chemin inverse `PAXG -> ETH/BTC -> USDC` ; faut-il garder l'intermédiaire (évite 2 frais) ou revenir en USDC ? Impact sur le Fee Test, le cash du pool et la comptabilité.
6. **Choix de route** : liste des routes candidates par (provider, actif) affichées côte à côte avec Fee Test + warnings (liquidité, passage par intermédiaire exposé) ; le lien mémorise la route choisie (cf. idée « chemin d'achat par actif et provider » dans `known-gaps` §5c) ; que se passe-t-il si le Fee Test passe ROUGE après coup.
7. **Fiscalité** : Clem part du principe qu'un échange crypto->crypto n'est pas le fiat qu'elle veut éviter. Signale-le comme **hypothèse à confirmer par elle/un professionnel**, sans l'affirmer.
8. **Impact UX Admin** (onglets Catalogue & routes, Bindings, Testeur d'ordre) et statuts (`TRADABLE_VIA_BRIDGE` -> nouveau nom pour « via intermédiaire crypto » ?).
9. **Feuille de route par lots** petits et testables, point avec Clem après chacun. Minimum : (a) retrait d'USDT du groupe (USDC seul) + suppression des routes/bindings via USDT + glossaire/docs ; (b) `PathFinder` + `FeeTest` avec intermédiaires non stables, **lecture seule** (`path-quote` PAXG Kraken chiffré) ; (c) plan d'ordres dry-run multi-jambes ; (d) `validate=true` Kraken sur chaque jambe puis ordre réel minimal sous contrôle de Clem ; (e) page.
10. **Docs** : la doc doit rester à jour (architecture, known-gaps §5c, glossaire, flyway si schéma) ; supprimer ce qui n'est plus valable (USDT, stock USDT, `NOT_TRADABLE_WITHOUT_FIAT` pour PAXG Kraken si levé), sans historiser. Une fois l'étude validée : écrire `docs/etudes/etude-passerelle-actif-non-stable.md` puis un prompt d'implémentation.
