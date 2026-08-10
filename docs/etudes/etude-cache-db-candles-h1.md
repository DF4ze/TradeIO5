# Étude — Cache DB des bougies H1 requêtées

**Statut : Implémenté (2026-07-07)** — `CandleEntity`/`CandleRepository`, décorateur
`CachingMarketDataApiClient` (branché sur Binance/Kraken/OKX via `MarketDataCachingConfig`),
`TreeAnalysisFacade` migré sur le client Binance caché, batching Hibernate configuré. Suivi
mis à jour dans `BackLog.ods` (tâche 3.2.9 : TODO → DONE).

Objectif : persister en base les bougies H1 déjà récupérées auprès des exchanges (Binance/Kraken/OKX), pour éviter de rappeler les API publiques à chaque requête et constituer, au fil de l'eau, un dataset réutilisable pour des backtests/benchmarks.

## 1. Verdict rapide

Pas overkill — c'est un trou déjà anticipé dans le code, pas une nouvelle direction architecturale. `MarketDataSource.DATABASE` existe dans l'enum et `InDatabaseMarketDataProvider` existe comme classe, mais c'est un stub à 100% (`fullLoad`/`loadSince`/`fetchMarketData` lèvent tous `UnsupportedOperationException`). La volumétrie n'est pas un problème (dizaines de milliers de lignes par symbole sur plusieurs années, MySQL gère ça sans effort). La vraie décision d'architecture est **où** brancher le cache : pas comme `MarketDataSource.DATABASE` (une source alternative que l'appelant doit choisir explicitement), mais comme un **décorateur transparent** devant chaque `MarketDataApiClient` (Binance/Kraken/OKX) — pour que tout code existant (chaîne `Indicator/Strategy/Opinion`, futur tool DCA) en profite automatiquement sans rien changer à ses appels.

## 2. Ce qui existe déjà vs. ce qui manque

| Existe déjà | Manque |
|---|---|
| `MarketDataSource.DATABASE` dans l'enum (type `HISTORICAL`) | Une entité JPA pour la bougie (`MarketData` aujourd'hui n'est qu'un DTO `@Builder`, pas un `@Entity`) |
| `InDatabaseMarketDataProvider` (stub, jamais implémenté) | Un `CandleRepository` (`JpaRepository`) |
| `MarketDataProviderRegistry` sait déjà router vers `MarketDataSource.DATABASE` | La logique de cache elle-même (lecture-avant-réseau, détection des trous, écriture) |
| `MarketDataApiClient` (interface commune Binance/Kraken/OKX), découplée de `ApiCredential` — donc pas liée à un `User` | — |
| `service/scheduler/` (dossier vide) — pourrait accueillir un job de pré-chauffage optionnel plus tard | — |

## 3. Principe : décorateur, pas provider alternatif

`MarketDataApiClient` a une interface à 2 méthodes :

```java
public interface MarketDataApiClient {
    MarketDataSource getSource();
    List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit);
}
```

Proposition : une classe `CachingMarketDataApiClient` qui implémente la même interface, wrap un `MarketDataApiClient` réel (le délégué Binance/Kraken/OKX), et :

1. Cherche en DB les bougies déjà connues pour `(source, symbol, timeFrame)` sur `[since, until]`.
2. Identifie les sous-plages manquantes (trous) en comparant à la séquence attendue (`TimeFrame.addTo` pas à pas, comme pour le calendrier DCA de l'étude précédente).
3. N'appelle le délégué réseau que pour ces trous — jamais pour une plage déjà entièrement en cache.
4. Persiste les nouvelles bougies **closes** récupérées (section 5).
5. Retourne la fusion cache + réseau, triée chronologiquement — le contrat de retour ne change pas pour l'appelant.

```java
@Component
@Primary
public class CachingMarketDataApiClient implements MarketDataApiClient {

    private final MarketDataApiClient delegate;
    private final CandleRepository repository;
    private final Clock clock; // pour déterminer ce qui est "clos"

    // ... getSource() délègue simplement

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        List<MarketData> cached = repository.findRange(delegate.getSource(), symbol, timeFrame, since, until);
        List<Range> gaps = findGaps(cached, since, until, timeFrame);
        for (Range gap : gaps) {
            List<MarketData> fetched = delegate.getCandles(symbol, timeFrame, gap.since(), gap.until(), limit);
            persistClosedOnly(fetched, timeFrame);
            cached = merge(cached, fetched);
        }
        return cached;
    }
}
```

Ce décorateur remplacerait l'injection directe de `BinanceMarketDataApiClient`/`KrakenMarketDataApiClient`/`OkxMarketDataApiClient` là où elles sont utilisées aujourd'hui (`TreeAnalysisFacade`) — soit via `@Primary`/qualifier Spring, soit en le branchant explicitement dans `MarketDataProviderRegistry` à la place du client brut. Aucune des couches au-dessus (`MarketDatasetEngine`, `Bucket`, `TreeAnalysisFacade`) n'a besoin de savoir que le cache existe.

## 4. Modèle de données

Nouvelle entité, à nommer `CandleEntity` (suffixe `Entity` pour éviter la collision avec le DTO `model.dto.market.MarketData` déjà existant — même convention que `EventEntity`/`ScenarioEventEntity` dans le projet) :

```java
@Entity
@Table(name = "candle",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_candle_source_pair_tf_ts",
           columnNames = {"source", "pair", "time_frame", "timestamp"}
       ))
@Getter @Setter @NoArgsConstructor
public class CandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketDataSource source;   // BINANCE, KRAKEN, OKX...

    @Column(nullable = false, length = 20)
    private String pair;               // BTCUSDT

    @Enumerated(EnumType.STRING)
    @Column(name = "time_frame", nullable = false, length = 10)
    private TimeFrame timeFrame;       // H1 aujourd'hui, extensible

    @Column(nullable = false)
    private Instant timestamp;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal open;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal high;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal low;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal close;
    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal volume;
}
```

Points de convention réutilisés du reste du projet :
- `precision = 30, scale = 10` sur les montants, comme `Transaction.quantity`/`Transaction.price`.
- Contrainte unique nommée, comme `IndicatorParameterSet` (`uk_indicatorparamset_code_name`) ou `Transaction` (`uk_transaction_ext_id`).
- **Pas de `User`/`Wallet` associé** : les candles sont publiques, une seule table partagée sert tous les utilisateurs de l'app — cohérent avec le commentaire déjà présent dans `MarketDataApiClient` ("volontairement découplé d'`ApiCredential`").

L'index composite de la contrainte unique (`source, pair, time_frame, timestamp`) sert aussi aux lectures : une requête `WHERE source=? AND pair=? AND time_frame=? AND timestamp BETWEEN ? AND ?` l'utilise nativement (préfixe gauche en égalité + range sur la dernière colonne) — pas besoin d'un index supplémentaire.

```java
public interface CandleRepository extends JpaRepository<CandleEntity, Long> {
    List<CandleEntity> findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
        MarketDataSource source, String pair, TimeFrame timeFrame, Instant since, Instant until
    );
}
```

## 5. Immutabilité : ne persister que les bougies closes

Règle non négociable : ne jamais écrire en cache une bougie H1 en cours (celle qui contient "maintenant"). Une bougie H1 n'est définitive qu'une fois son intervalle terminé :

```java
boolean isClosed(MarketData candle, TimeFrame timeFrame, Instant now) {
    return timeFrame.addTo(candle.getTimestamp()).isBefore(now); // ou = now, à la marge près
}
```

C'est ce qui rend ce cache beaucoup plus simple que `MarketDatasetCache`/`Bucket` (rolling window ancrée sur "now", avec le bug de cache-key déjà documenté dans `etude-tick-retrieval.md`) : une bougie close ne change plus jamais, donc aucune invalidation à gérer, aucune notion de fraîcheur — on écrit une fois, on relit indéfiniment.

Exception théorique à assumer explicitement : si un exchange republie une correction sur une bougie ancienne (rare, mais déjà arrivé sur certains exchanges), le cache ne le verra jamais puisqu'il ne réinterroge jamais une plage déjà en base. Risque accepté et documenté plutôt que résolu — pas de mécanisme de "force refresh" prévu au MVP, à ajouter seulement si le besoin se manifeste.

## 6. Performance d'écriture

Deux points à régler, sinon le premier backfill sera lent :

- **Pas de batching Hibernate configuré aujourd'hui** (`spring.jpa.properties.hibernate.jdbc.batch_size` absent des `application-*.properties`) — un `saveAll()` sur plusieurs milliers de bougies part en un `INSERT` par ligne. À ajouter : `hibernate.jdbc.batch_size=200` (ou plus) + `rewriteBatchedStatements=true` sur l'URL JDBC MySQL (sans ce dernier, le driver MySQL envoie quand même les inserts un par un malgré le batching Hibernate — piège classique).
- **Concurrence** : si deux appels tool MCP concurrents demandent la même plage encore non cachée, les deux vont fetcher et tenter d'insérer les mêmes bougies → violation de la contrainte unique sur le second. À gérer soit avec un upsert natif (`INSERT IGNORE` / `ON DUPLICATE KEY UPDATE` en `@Query(nativeQuery = true)`), soit en catchant `DataIntegrityViolationException` autour de l'écriture et en l'ignorant (le contenu est de toute façon identique, la contrainte unique garantit juste qu'on ne duplique pas).

## 7. Bénéfice concret pour les backtests/DCA

C'est le vrai gain recherché : le tool DCA proposé dans `etude-dca-tool-mcp.md` fait un fetch en bloc paginé de la série H1 sur toute la période demandée. Avec ce cache :
- Premier appel sur une période donnée → pagination réseau classique (~27 appels pour 3 ans, comme calculé dans l'étude DCA), puis écriture en cache.
- Tout appel suivant sur une période chevauchante (autre benchmark, re-run, ajustement de paramètres) → lecture DB pure, plus aucun appel réseau, quasi instantané.

Le dataset se construit donc naturellement à l'usage, sans job de synchronisation dédié à prévoir pour le MVP — chaque appel `get_indicator`/`evaluate_strategy`/`get_opinion`/futur `get_dca_stats` alimente le cache au passage. Un job de pré-chauffage périodique (`service/scheduler/`, actuellement vide) reste une option pour plus tard si on veut proactivement garder certains symboles à jour, mais n'est pas nécessaire pour que l'idée fonctionne.

## 8. Risques et points d'attention

- **Schéma non versionné** : le projet n'a pas de Flyway/Liquibase (`ddl-auto=update` partout) — la table `candle` sera auto-créée par Hibernate comme le reste. Pas un risque nouveau introduit par cette feature, c'est déjà l'état du projet, mais bon à garder en tête si le besoin de migrations versionnées se fait sentir un jour.
- **Corrections d'exchange non propagées** une fois une bougie cachée (section 5) — risque accepté, pas résolu.
- **Batching à configurer** avant le premier backfill volumineux, sinon écriture lente sans que ce soit un bug (section 6).
- **Contrainte unique doit inclure `source`** : Binance/Kraken/OKX n'ont pas exactement le même OHLCV pour "la même" heure — sans `source` dans la clé, un `INSERT` sur un exchange écraserait/entrerait en conflit avec les données d'un autre.

## 9. Prochaine étape

Implémentation dans l'ordre : (1) `CandleEntity` + `CandleRepository`, (2) `CachingMarketDataApiClient` (méthode `getCandles` uniquement, `getSource()` délègue), (3) config Hibernate batch + `rewriteBatchedStatements`, (4) branchement en remplacement des 3 clients bruts partout où ils sont injectés aujourd'hui (`TreeAnalysisFacade`, futur `DcaCalculatorService`). Aucune dépendance sur le futur tool DCA : ce cache est utile indépendamment, dès aujourd'hui, pour `get_indicator`/`evaluate_strategy`/`get_opinion`.
