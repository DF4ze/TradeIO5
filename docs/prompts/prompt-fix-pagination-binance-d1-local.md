# Prompt — Analyser et corriger : historique D1 insuffisant (pagination Binance) sur base locale

> À coller dans une nouvelle discussion (TradeIO-5). Réponses courtes, techniques, en français. Lire `docs/README.md` et `docs/CODING_RULES.md` d'abord. Pas de secret affiché (ne pas lire `application-*.properties`). Doc `docs/` à jour, sans historique ; ce prompt sera supprimé après usage.

## Symptôme
Sur la base locale (profil `dev`, réinitialisée : table `candle` = 3000 lignes H1, aucune D1, pas de `backup/historical`) :
- `POST /api/admin/rainbow-live/run?pass=T2355&day=2026-10-09` ⇒ `processed:0, skipped:3` ; puis `POST /api/admin/execution/plan` ⇒ `skipped:2` (aucun run du jour ⇒ user ignoré), `rainbow_live_run` et `rainbow_live_order_plan` vides.
- Log : `Bench Rainbow : D1 PAXG chargé (84 bougies)`, `bougie D1 du 2026-10-09 absente pour PAXG (dernière : 2020-11-19)` ; BTC/ETH : dernière bougie 2017-09-28.
- Log du cache : `CachingMarketDataApiClient ... Mismatch bougies attendues/reçues pour PAXGUSDT H1 source=BINANCE trou=[2020-10-09 .. 2026-10-10] : attendu=52625, reçu=1000` et `Binance klines ... GET /api/v3/klines?...&limit=52626`. `MarketDatasetEngine` : `Initial Limit 4000 D1 ⇒ Equiv Limit 96000 H1`.

## Hypothèse à vérifier (ne pas la supposer vraie)
Binance plafonne `/klines` à 1000 bougies par appel. `BinanceMarketDataApiClient#getCandles(symbol, timeFrame, since, until, limit)` envoie `limit` brut (>1000) sans boucler ; `CachingMarketDataApiClient` comble chaque trou avec un seul appel ⇒ seules les 1000 premières bougies du trou arrivent, le présent n'est jamais atteint. Sur le VPS, l'historique existait déjà (donc bug masqué).

## À faire
1. **Analyser (lecture seule d'abord)** : confirmer ou infirmer l'hypothèse dans `BinanceMarketDataApiClient`, `CachingMarketDataApiClient` (comblement de trous, politique « tout fetch stocké en base »), `MarketDatasetEngine#fetchDataForBucket`, le cache mémoire `Bucket`/`MarketDatasetState`, et pourquoi D1 est dérivé de H1 (resampling). Vérifier aussi KRAKEN/OKX (mêmes plafonds : Kraken 720, OKX 100/300) et le fallback multi-provider.
2. **Corriger** si confirmé : pagination par fenêtres de 1000 (ou plafond du provider) en avançant `startTime` jusqu'à `until`, sans doublon, ordre croissant, respect du rate limit (espacement, poids Binance, pas de ban), sans casser le cache DB ni la détection de trous. Un fetch partiel ne doit pas laisser le cache dans un état qui masque le trou restant. Prévoir une borne de sécurité (nombre max d'appels) et un log clair de progression.
3. **Tests** : unitaires purs sur la pagination (fixtures : 0, 1, 1000, 1001, 2500 bougies ; trou au milieu ; doublons) ; ne pas appeler le réseau. Suite complète verte (ssh-gateway, JDK 21, voir mémoire `ssh_gateway_mvn_java_home_gotcha`).
4. **Valider en réel (local, une fois, avec accord de Clem car coûteux en requêtes)** : après redémarrage, `POST /api/admin/rainbow-live/run?pass=T2355&day=2026-10-09` ⇒ `processed:3`, D1 ≈ 3000-4000 bougies, puis `POST /api/admin/execution/plan?pass=T2355&day=2026-10-09` ⇒ `planned`/`blocked` ≥ 1 ; contrôler `rainbow_live_run` et `rainbow_live_order_plan`. Surveiller les codes 429/418.
5. **Docs** : `architecture/04-market-data.md` (pagination, plafonds par provider), `known-gaps` si un reste subsiste. Rien d'historique.

## Contraintes
- Aucun ordre réel, aucun changement de `mode`/kill switch/credentials dans cette session. Le kill switch est engagé, LIVE verrouillé ; ne pas y toucher.
- Style : isEmpty()/getFirst(), constantes métier mutualisées (plafonds par provider en un seul endroit), pas de variables redondantes, `DomainClock`.
- Ne pas committer sans demande ; supprimer les fichiers temporaires.
