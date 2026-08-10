# Cache DB + historisation ETF_FLOW (2026-07-16)

> **Implémenté le 2026-07-16**, dans la foulée du branchement `CONFIDENCE_MODULATOR`
> (`docs/etudes/etude-branchement-etf-flow-confidence-modulator.md`). Demande de Clem : éviter un appel
> réseau SoSoValue à chaque évaluation (la donnée ne change qu'une fois par jour) **et** constituer
> une vraie historisation en base pour de futurs indicateurs de tendance sur le flux ETF — pas
> uniquement un cache de commodité. `CachingEtfFlowClient` + `EtfFlowSnapshotEntity`/`Repository` +
> `EtfFlowHistorizationJob` (cron quotidien). 10 nouveaux tests (359 → 369, 0 échec), vérifié en réel
> après redémarrage : BTC/ETH MISS au premier appel (table vierge), HIT au rappel suivant, valeurs
> identiques à la vérification précédente (BTC 107 804 553,80 $ / ETH 53 829 997,04 $).

## Constat de départ

`EtfFlowIndicator` → `SosoValueEtfFlowClient` faisait un appel HTTP live à chaque `evaluate()`, alors
que SoSoValue ne publie qu'une valeur par jour et par asset (post-clôture US). Chaque évaluation de
`EtfFlowConfidenceStrategy`/`get_indicator` retapait donc inutilement l'API (palier "Demo" limité à
20 appels/min), sans bénéfice.

## Design retenu : cache-aside DB, pas cron-only

Deux patrons existaient déjà dans le code comme précédents possibles :

- **`CachingMarketDataApiClient`** (candles) : décorateur cache-aside, lit la DB avant le réseau,
  n'appelle le réseau que pour combler les trous.
- **`MediaWatchIngestionJob`** (veille média) : cron pur, écrit en base à intervalles fixes.

Un cron seul a été écarté comme mécanisme *primaire* : si l'app est down au moment du cron (fréquent
en dev), la table reste vide pour la journée et le modulateur ETF_FLOW se retrouve silencieusement
invalide jusqu'au lendemain — point de défaillance unique sur une donnée qui alimente un signal de
confidence. Le design retenu combine les deux :

- **`CachingEtfFlowClient`** (patron `CachingMarketDataApiClient`, appliqué à une donnée ponctuelle
  quotidienne plutôt qu'à une série continue) : `fetch()` — utilisé par `EtfFlowIndicator` à chaque
  évaluation — regarde le dernier snapshot connu pour l'asset ; si son `fetchedAt` est déjà
  aujourd'hui (calendrier JVM), sert la valeur en cache sans réseau ; sinon appelle SoSoValue et
  persiste. Auto-guérisseur : fonctionne correctement même si le cron n'a jamais tourné.
- **`EtfFlowHistorizationJob`** (patron `MediaWatchIngestionJob`, cron `@Scheduled`) : rafraîchissement
  quotidien délibéré à 07h00 heure locale JVM (configurable, `tradeio.etf-flow.historization-cron`),
  BTC + ETH, isolation par asset. Appelle `CachingEtfFlowClient#refresh` — une deuxième méthode qui
  **bypasse** le gate quotidien de `fetch()` — pour garantir une ligne d'historique par jour même les
  jours où personne n'évalue ETF_FLOW/CONFIDENCE_MODULATOR. C'est ce job qui répond à la demande
  d'historisation continue, indépendamment du service de cache lui-même.

Créneau 07h00 choisi par hypothèse (pas de donnée empirique sur l'heure de publication SoSoValue,
contrairement au calage MediaWatch basé sur des horaires Cryptolyze observés) : à ajuster si un trou
est constaté en pratique.

## Schéma

Nouvelle table `etf_flow_snapshot` (`EtfFlowSnapshotEntity`) : `asset`, `date` (date de la donnée
telle que publiée par SoSoValue), `total_net_inflow`, `fetched_at` (date de l'appel réseau — sert au
gate du cache, distinct de `date`). Contrainte unique `(asset, date)`, upsert par
`CachingEtfFlowClient#persist`. Table créée automatiquement au démarrage (`ddl-auto=update`), aucune
migration manuelle nécessaire.

## Risque assumé, non résolu

Si SoSoValue ne publie pas de nouvelle donnée avant le passage du cron ET qu'aucun utilisateur
n'évalue ETF_FLOW ce jour-là, le jour reste sans ligne d'historique — pas de rattrapage automatique
(pas de requête d'historique multi-jours pour combler les trous a posteriori). Même choix assumé que
documenté sur `CandleEntity` ("correction tardive d'un exchange, risque assumé, non résolu"). À
réévaluer si des trous sont effectivement observés une fois assez d'historique accumulé.

## Wiring Spring

`SosoValueEtfFlowClient` n'est plus `@Component` (même traitement que `FarsideEtfFlowClient` en son
temps) — un seul bean `EtfFlowProvider` dans le contexte, `EtfFlowCachingConfig` l'enveloppe dans
`CachingEtfFlowClient`. Type de retour du `@Bean` volontairement concret (`CachingEtfFlowClient`, pas
l'interface) : à la fois injecté dans `EtfFlowIndicator` (via `EtfFlowProvider`) et dans
`EtfFlowHistorizationJob` (a besoin de `refresh()`, absent de l'interface).

## Fichiers

Créés : `EtfFlowSnapshotEntity`, `EtfFlowSnapshotRepository`, `CachingEtfFlowClient`,
`EtfFlowCachingConfig`, `EtfFlowHistorizationJob`, `CachingEtfFlowClientTest`,
`EtfFlowHistorizationJobTest`. Modifiés : `SosoValueEtfFlowClient` (retrait `@Component`),
`application-profile.properties.template` (property `tradeio.etf-flow.historization-cron` documentée).

## Addendum — fetch Binance inutile côté `get_indicator` (repéré par Clem, 2026-07-16)

En vérifiant le cache ETF_FLOW en réel, Clem a remarqué dans les logs qu'un `get_indicator ETF_FLOW`
déclenchait quand même un vrai appel réseau Binance. Cause distincte du cache ETF_FLOW lui-même :
`TreeAnalysisFacade#getIndicator` appelait systématiquement `MarketDatasetEngine#getDataset` (500 D1,
jusqu'à ~9000+ H1 équivalent) avant même de savoir si l'indicateur demandé en avait besoin — confirmé
en log, un appel `get_indicator ETF_FLOW` sur `ETHUSDT` avait déclenché un vrai
`GET https://api.binance.com/api/v3/klines?symbol=ETHUSDT&interval=1h&...&limit=9006`.

10 indicateurs déclarent `getRequiredData() == 0` et ne lisent jamais `IndicatorContext.marketDataset()` :
ETF_FLOW, FEAR_GREED, STABLECOIN_MARKET_CAP, DXY, SP500, NASDAQ, OPEN_INTEREST, FUNDING_RATE,
LIQUIDATIONS, ORDER_BOOK. Correctif : `getIndicator` saute `fetchDataset(...)` et construit un
`MarketDataset` vide quand `indicator.getRequiredData(parameters) == 0`. Scope volontairement limité
à `getIndicator` — `evaluateStrategy`/`getOpinion` ont leur propre logique de candles requises
(`Strategy#getRequiredCandles`/`MarketOpinion#getRequiredCandles`), déjà correctement dimensionnée à
l'usage réel (ex: `EtfFlowConfidenceStrategy` a légitimement besoin de candles D1 pour son calcul de
variation de prix, contrairement à `EtfFlowIndicator` seul).

371 tests (369+2, nouveau `TreeAnalysisFacadeGetIndicatorDatasetTest`), vérifié en réel après
redémarrage : deux appels `get_indicator ETF_FLOW` (BTC+ETH) ne produisent plus que les lignes
`CachingEtfFlowClient` dans le log, sans aucune ligne `MarketDatasetEngine`/`Bucket`/`Appel réseau
BINANCE` autour.

## Addendum — backfill historique (demande Clem, 2026-07-17)

Ajout de `SosoValueEtfFlowClient#fetchHistory`/`parseHistory` (variante liste de `fetch`/`parse`,
`limit` paramétrable), `CachingEtfFlowClient#upsert` (public, réutilisé par le backfill),
`EtfFlowBackfillService` (déclenché uniquement à la demande, jamais au démarrage — même principe que
`TransactionSyncInitializer`, dont l'`ApplicationRunner` automatique a été retiré le 2026-07-09 sur
décision explicite de Clem) et `EtfFlowAdminController` (`POST /api/admin/etf-flow/backfill`,
ROLE_ADMIN, même patron que `MediaWatchAdminController`). `EtfFlowCachingConfig` expose désormais
`SosoValueEtfFlowClient` comme bean à part entière (nécessaire pour `fetchHistory`) — `@Primary`
ajouté sur `cachingEtfFlowClient` pour lever l'ambiguïté `EtfFlowProvider` que ce second bean a
introduite (repéré par échec de démarrage réel, `NoUniqueBeanDefinitionException`, corrigé avant
tout redémarrage demandé à Clem). 386 tests (371+15), 0 échec.

**Constat empirique important, contredit la doc SoSoValue** : la doc de `summary-history` annonce
`limit` jusqu'à 300, avec seule la combinaison `start_date`/`end_date` documentée comme "limitée au
mois le plus récent". En réel (`limit=300` demandé, sans dates), l'API n'a renvoyé que **20 lignes
par asset** (BTC et ETH identiques : 2026-06-17 → 2026-07-16, soit tout juste un mois de trading).
La restriction "most recent 1 month" s'applique donc à l'endpoint entier sur le palier "Demo", pas
seulement aux requêtes par date explicite — le paramètre `limit` documenté jusqu'à 300 est
théorique, pas atteignable en pratique. Conséquence : le backfill ne peut pas dépasser ~1 mois de
recul par appel, bien loin des ~14 mois espérés (et encore plus loin des ~2,5 ans depuis le
lancement des ETF BTC). Rejouer ce backfill plus tard (relance manuelle de l'endpoint) ne fera que
glisser la fenêtre d'un mois, jamais remonter plus loin dans le passé — seule façon de couvrir plus
loin : laisser le cron quotidien (`EtfFlowHistorizationJob`) accumuler 1 ligne/jour dans la durée,
ou solliciter une source complémentaire (Farside, évoqué mais pas implémenté) pour l'historique
antérieur à juin 2026.

**Vérifié en réel** (2026-07-17, backfill déclenché par Clem via `POST /api/admin/etf-flow/backfill`) :
20 lignes upsertées pour BTC, 20 pour ETH, 0 erreur, table `etf_flow_snapshot` passée de 1-2 lignes
par asset à 20.

## Addendum — source complémentaire Farside pour l'historique profond (2026-07-17)

Suite au constat ci-dessus, Clem a demandé de rechercher une technique pour dépasser le mois
d'historique atteignable via SoSoValue. Recherche : le second endpoint SoSoValue
`/etfs/{ticker}/history` documente la même restriction ("most recent 1 month") — pas de
contournement côté SoSoValue, quel que soit l'endpoint. En revanche, Farside publie une page
"all data" par asset (`https://farside.co.uk/bitcoin-etf-flow-all-data/`,
`.../ethereum-etf-flow-all-data/`) qui remonte au lancement de chaque ETF (11 jan. 2024 pour BTC),
avec exactement la même structure de tableau HTML que la page live déjà scrapée par
`FarsideEtfFlowClient` avant la bascule vers SoSoValue du 2026-07-16.

**Implémentation** : `EtfFlowAsset` porte désormais un second chemin (`historyPath`, page "all
data") en plus de `path` (page live). `FarsideEtfFlowClient#fetchHistory`/`parseHistory` (variante
multi-lignes de `fetch`/`parse`, écrite indépendamment de `parse()` pour ne prendre aucun risque sur
ses tests existants — même principe que `SosoValueEtfFlowClient#parseHistory` en son temps) parse
toutes les lignes de données de cette page, en excluant les jours non publiés (fériés, toutes les
cases émetteur à "-") plutôt que de les persister à 0 — un jour sans donnée n'est pas un jour à flux
nul réel. `IndicatorCredentialResolver` gagne une méthode `resolveWebProvider(WebProviderCode)` de
résolution directe par provider (extraite de `resolve(IndicatorType)`) : la credential FARSIDE
n'avait jamais été supprimée en base (page publique, `apiKey` vide) — seul son routing via `resolve`
avait changé le 2026-07-16, donc rien à recréer, juste une nouvelle façon d'y accéder. `EtfFlowCachingConfig`
expose de nouveau `FarsideEtfFlowClient` comme bean (3e candidat `EtfFlowProvider` dans le contexte,
sans casser le `@Primary` déjà en place sur `cachingEtfFlowClient`).

`EtfFlowBackfillService` combine désormais les deux sources : **Farside d'abord** (historique
profond), **SoSoValue ensuite** (dernier mois, a le dernier mot sur les dates en commun — contrainte
unique `(asset, date)`, simple réécriture). Chaque source est indépendamment optionnelle : si une
seule credential est résolue, le backfill se poursuit avec celle-là seule plutôt que d'échouer
entièrement.

394 tests (386+8), 0 échec. **Vérifié en réel** (2026-07-17, backfill relancé par Clem après
redémarrage) :

```
FARSIDE: 629 lignes pour BTC (couverture 2024-01-11 -> 2026-07-16)
SOSOVALUE: 20 lignes pour BTC (couverture 2026-06-17 -> 2026-07-16)
=> 629 lignes distinctes en base pour BTC

FARSIDE: 497 lignes pour ETH (couverture 2024-07-23 -> 2026-07-16)
SOSOVALUE: 20 lignes pour ETH (couverture 2026-06-17 -> 2026-07-16)
=> 497 lignes distinctes en base pour ETH
```

Couverture cohérente avec les dates de lancement réelles (BTC : 11 jan. 2024 ; ETH : 23 jul. 2024).
Objectif initial de Clem ("cacher en DB la totalité des data") atteint dans les faits : le seul
angle mort restant est la fenêtre antérieure au lancement de chaque ETF, qui n'existe simplement pas.
