# Incohérences et points d'attention vérifiés dans le code

Vérifié le : 2026-09-12. Liste de faits ponctuels, confirmés en lisant le code, qui peuvent surprendre ou mener à une mauvaise conclusion s'ils sont ignorés. Ne pas confondre avec [`decision-to-order-gap.md`](decision-to-order-gap.md) (gap fonctionnel majeur, sa propre page).

## Sécurité — chaîne de filtres permissive

`WebSecurityConfig` : `.requestMatchers("/**").permitAll()` rend `.anyRequest().authenticated()` sans effet pratique. La protection réelle vient de `@PreAuthorize` par méthode/classe. Détail : [`architecture/07-security.md`](../architecture/07-security.md).

## `OpinionScope.EXTERNAL` — deux implémentations concurrentes

`ExternalMarketOpinion` (LLM en direct) et `MediaMarketOpinion` (veille média) sont toutes deux enregistrées sous `OpinionScope.EXTERNAL`. La façade résout la première trouvée, logue un warning. Ordre non garanti stable (dépend de l'ordre d'enregistrement Spring). Détail : [`architecture/02-tree-pipeline.md`](../architecture/02-tree-pipeline.md#3-opinion).

## `MarketDataSource` — 4 valeurs d'enum non implémentées

`COINBASE`, `UNISWAP`, `SUSHISWAP`, `CHAINLINK` existent dans l'enum mais n'ont pas de factory dans `MarketDataProviderRegistry`. Ne pas les présenter comme des sources supportées. Détail : [`architecture/04-market-data.md`](../architecture/04-market-data.md).

## Constantes codées en dur, candidates à l'externalisation (non bloquant)

Toutes recensées dans la revue de TODO du 2026-08-10 (`docs/suivi/point-avancement-2026-08-10.md`, archivé) :

- `DefaultMarketScenario.EXPIRATION_IDLE = 2h`.
- Poids de blend confidence `0.7/0.3` dans `DefaultMarketScenario`.
- `BalanceCacheManager.TTL_MS = 60s`.
- `Bucket.BASE_TIME_FRAME = H1`.
- URI Fear&Greed codée en dur dans `CoinstatsFearAndGreedClient`.
- Gestion d'erreur générique (`catch (Exception e)` / `RuntimeException`) sur les chemins wallet/balance de `BinanceApiClient`/`KrakenApiClient`, à harmoniser avec la hiérarchie `MarketDataProviderException` existante.
- `ProviderApiService` lève `IllegalArgumentException` pour un provider inconnu, alors qu'une `NotFoundException` existe déjà dans le projet.
- `IndicatorParameterService#loadParameters` retourne toujours `credential=null` **par conception** (le credential est résolu par l'appelant, pas stocké dans le set de paramètres) — un TODO à ce sujet dans le code est obsolète.

## Indicateurs "recadrés, pas invalidés"

Verdicts de calibration statistique parfois mal lus comme "inutilisable" alors qu'ils signifient "pas d'edge directionnel autonome, utilisable autrement" (revue du 2026-08-10) :

- **`RejectionZoneIndicator`** (technique "consolidation") : pas d'edge validé par le test statistique générique, mais détection qualitativement cohérente avec une lecture manuelle. Verdict : utilisable en pondération basse (modulateur secondaire) — pas encore branché au 2026-08-10.
- **`EtfFlowConfidenceStrategy`** : le test de calibration évaluait à tort un edge directionnel autonome, ce qui n'est pas le rôle d'un `CONFIDENCE_MODULATOR`. Rôle réel : valider si le flux institutionnel confirme/contredit le mouvement de prix récent, en utilisant toujours une donnée J-1 (jamais le jour courant, explicite dans le javadoc). Verdict : utilisable telle quelle.

Détail complet des verdicts : `docs/calibration/*.md` (non ré-audité dans ce lot).

## Tests connus en échec, sans rapport avec le pipeline de décision

`DxyIndicatorCacheSharingTest` (2 tests, cache de l'indicateur DXY/Twelve Data) — signalé comme échec pré-existant au 2026-08-17, package non touché par les lots qui l'ont documenté. À reconfirmer si ce fichier de doc est lu longtemps après le 2026-09-12 (peut avoir été corrigé depuis).
