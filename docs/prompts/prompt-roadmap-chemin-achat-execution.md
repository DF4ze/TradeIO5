# Roadmap — Chemin d'achat via API (passerelle USDT), plan d'ordres, Fee Test, exécution réelle

Séquence le chantier (lot B) ; analyse, architecture et constats : [`../etudes/etude-chemin-achat-execution.md`](../etudes/etude-chemin-achat-execution.md) (§3 architecture, §4 lots, §5-§6 décisions — source de vérité). **Un point avec Clem à la fin de chaque étape** avant de rédiger/lancer la suivante. Une étape = compilée + testée + doc `docs/` à jour (sans historique) + cette roadmap mise à jour. **Aucun ordre réel avant l'étape d**, et seulement avec Clem.

| # | Étape | Statut |
|---|---|---|
| 0 | Étude (lecture seule) | **fait (2026-10-10)**, décisions validées |
| a | Catalogue d'instruments (cache DB), frais réels (`trade-fee`, clé Read), graphe + recherche de chemin (nœuds fiat interdits, ≤ 2 sauts), coût + Fee Test. **Aucun ordre.** | **fait et validé en réel (2026-10-10)** : PAXG/OKX via USDC→USDT→PAXG |
| b | Plan d'ordres dry-run enregistré (entités plan/étapes, bilan virtuel USDC/USDT, arrondis, `clOrdId`), job désactivé, affichage | **implémenté (2026-10-10)**, suite verte, plan réel local relu avec Clem |
| c | `ApiCredential.scope` + chiffrement ; `SpotOrderPort` + client OKX **désactivé par défaut** (package dédié) ; faux exchange ; machine d'états, réconciliation, kill switch, plafonds ; tests d'architecture réécrits — prompt `prompt-implementation-chemin-achat-c.md` (C1) | prompt rédigé ; à lancer après validation de l'étape b |
| d | Clé Trade créée avec Clem ; **1ᵉʳ ordre réel de montant minimal** (pont `USDC→USDT` seul), double validation — session guidée `prompt-guide-chemin-achat-d.md` ; ensuite exécution manuelle quelques jours avant tout cron | prompt rédigé ; après C |
| e | Page : plan, coût, warnings, interrupteurs, audit — même prompt (C2, après point sur C1) | prompt rédigé ; après C1 |

## Décisions de Clem à respecter (2026-10-10)
- **Fiat interdit** dans tout chemin (taxation) ; nœuds exclus configurables ; sans chemin ⇒ plan `BLOCKED` + warning, jamais d'ordre (Kraken PAXG).
- Flux d'achat : USDT dispo ? oui ⇒ achat `A/USDT` ; sinon manque ⇒ `USDC→USDT` puis achat ; demande enregistrée. Vente : produit **forcé en USDT** (`X-USDT`) quand la paire existe ; ce stock USDT sert de bag tampon.
- **Ordres limit IOC plafonné**, jamais de market aveugle (post-only optionnel plus tard) ; tolérance de slippage 0,05-0,1 %.
- **Fee Test** : seuils 0,2 % warning / 0,8 % rouge, configurables ; chemin multi-jambes = **somme brute** des coûts ; WARNING ⇒ alerte, RED ⇒ BLOCKED. Frais lus via `trade-fee` (jamais supposés), rejoués à chaque plan et stockés ; slippage réel enregistré après exécution.
- **Dry-run toujours avant l'exécution** : mode global `OFF|DRY_RUN|LIVE` (défaut DRY_RUN), `executionEnabled` par binding (défaut false), kill switch sans redéploiement, plafonds par ordre et par jour (= 1 / 2 × `baseAmount`) + plafond absolu en constante, double validation du 1ᵉʳ ordre réel (admin arme, user confirme). Admin arme, user active son binding.
- Idempotence : `clOrdId` déterministe (user/actif/jour/passe/étape) ; réconciliation avant tout renvoi (`UNKNOWN`). Échec partiel : le stock USDT reste, aucun retour arrière automatique ; reliquat d'un achat partiel reporté au cycle suivant. Re-devis avant chaque étape : arrêt si coût rouge ou > plan + 0,05 pt ; frais relus à chaque plan (cache 24 h).
- Spread : carnet (profondeur au montant), repli ticker. BTC/ETH sur OKX : paires USDC directes à revérifier par `instruments` ; l'option de préférer `X-USDT` pour consommer le stock USDT est décidée au lot a (paramètre du chemin).
- Credentials : `ApiCredential.scope` (READ|TRADE), unique (user, provider, scope), chiffrement au repos AES-GCM (clé maître en variable d'environnement du VPS), jamais withdraw, IP VPS whitelistée ; **Clem accompagnée** pour créer la clé Trade. Fiat : liste de devises interdites en config/constante mutualisée. Stock USDT → USDC (ordre inverse) : hors lot.
- Hors lot, à ne pas coder : token natif (remise de frais), découverte du wallet, alerte % de liquidité, compte Funding OKX, **retrait/transfert API jamais**.
- Style : `docs/CODING_RULES.md`, isEmpty()/getFirst(), constantes métier mutualisées, pas de variables redondantes, assertTrue/False directs, `DomainClock`. Credentials rattachées au User en base (jamais Flyway ni binaire).

## Étape a — Catalogue, frais, graphe, Fee Test (sans ordre)
- `InstrumentCatalog` : table `exchange_instrument` (provider, inst_id, base, quote, state, min_sz, lot_sz, tick_sz, fetched_at) alimentée par un seul `instruments?instType=SPOT` ≤ 1×/jour (+ refresh sur erreur) ; `BindingCheck` lit le catalogue (fin des appels unitaires).
- `TradingFeeProvider` OKX (`GET /api/v5/account/trade-fee`, clé Read, signature de `OkxBalanceReader` à mutualiser, maker/taker signés normalisés en bps).
- `PathFinder` : graphe par provider, Dijkstra au coût, nœuds interdits, profondeur ≤ 2 ; `PathQuote` : frais + demi-spread + slippage (carnet public), arrondis lot/tick/min ; `FeeTest` pur (seuils configurables).
- Lecture seule exposée (endpoint/DTO), warning utilisateur si `NO_PATH`.
- Critères : PAXG/OKX chiffré par frais réels ; PAXG/Kraken = `NO_PATH` ; `ConnectorNoOrderMethodTest` et `RainbowLiveNoExchangeDependencyTest` verts ; tests purs sur fixtures. **Point.**

## Étapes b à e
Contenu et critères d'acceptation : étude §3-§4. Chaque étape est rédigée en prompt d'implémentation après le point précédent (poser les points ambigus à Clem avant de rédiger).
