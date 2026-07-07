package fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.market.CandleEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.market.CandleRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

/**
 * Décorateur transparent devant un {@link MarketDataApiClient} réel (Binance/Kraken/OKX) :
 * persiste en base les bougies déjà récupérées et ne rappelle le réseau que pour les plages
 * (trous) encore manquantes. Cf. docs/etude-cache-db-candles-h1.md.
 * <p>
 * Chaque instance est dédiée à UN exchange (le délégué injecté au constructeur) — pas de
 * multiplexage interne. Voir {@link fr.ses10doigts.tradeIO5.configuration.MarketDataCachingConfig}
 * pour le branchement d'une instance par exchange.
 * <p>
 * Ne met en cache (lecture ou écriture) que les {@link TimeFrame} "fixes" ({@link TimeFrame#isFixed()},
 * ex: H1) : la logique de grille ({@link #floorToGrid}) suppose une durée exacte, ce que
 * {@link java.time.temporal.ChronoUnit#getDuration()} ne peut pas fournir pour les TimeFrame
 * calendaires (M1, W1...). Ces derniers passent directement au délégué, sans cache — sans
 * conséquence aujourd'hui puisqu'aucun {@link MarketDataApiClient} réel ne supporte nativement
 * autre chose que H1 (cf. {@code BinanceMarketDataApiClient#NATIVE_INTERVALS} et équivalents).
 */
public class CachingMarketDataApiClient implements MarketDataApiClient {

    private static final Logger log = LoggerFactory.getLogger(CachingMarketDataApiClient.class);

    private final MarketDataApiClient delegate;
    private final CandleRepository repository;
    private final DomainClock clock;

    public CachingMarketDataApiClient(MarketDataApiClient delegate, CandleRepository repository, DomainClock clock) {
        this.delegate = delegate;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public MarketDataSource getSource() {
        return delegate.getSource();
    }

    @Override
    public List<MarketData> getCandles(String symbol, TimeFrame timeFrame, Instant since, Instant until, int limit) {
        if (until == null || !timeFrame.isFixed()) {
            // Pas de borne haute exploitable pour le cache, ou TimeFrame calendaire (jamais
            // utilisé nativement par les clients exchange aujourd'hui) : passthrough réseau pur,
            // sans lecture ni écriture de cache.
            return delegate.getCandles(symbol, timeFrame, since, until, limit);
        }

        Instant untilGrid = floorToGrid(until, timeFrame);
        Instant sinceGrid = resolveSinceGrid(since, untilGrid, timeFrame, limit);

        if (sinceGrid == null || sinceGrid.isAfter(untilGrid)) {
            // Ni since ni limit exploitable pour borner la lecture cache : passthrough réseau pur
            // (on tente quand même de persister ce qui est fermé, en best-effort).
            List<MarketData> fetched = delegate.getCandles(symbol, timeFrame, since, until, limit);
            persistClosedOnly(fetched, timeFrame);
            return fetched;
        }

        MarketDataSource source = delegate.getSource();

        List<MarketData> cached = repository
                .findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(source, symbol, timeFrame, sinceGrid, untilGrid)
                .stream()
                .map(CachingMarketDataApiClient::toMarketData)
                .toList();

        List<Range> gaps = findGaps(cached, sinceGrid, untilGrid, timeFrame);

        if (gaps.isEmpty()) {
            log.info("Cache HIT complet pour {} {} [{} .. {}] : {} bougie(s) servie(s) depuis la base, aucun appel réseau à {}.",
                    symbol, timeFrame, sinceGrid, untilGrid, cached.size(), source);
        } else {
            log.info("Cache PARTIEL pour {} {} [{} .. {}] : {} bougie(s) en base, {} trou(s) à combler via {} (réseau).",
                    symbol, timeFrame, sinceGrid, untilGrid, cached.size(), gaps.size(), source);
        }

        List<MarketData> merged = new ArrayList<>(cached);
        for (Range gap : gaps) {
            int gapLimit = gapSize(gap, timeFrame);
            log.info("Appel réseau {} : fetch {} {} de {} à {} (limit={})", source, symbol, timeFrame, gap.since(), gap.until(), gapLimit);
            List<MarketData> fetched = delegate.getCandles(symbol, timeFrame, gap.since(), gap.until(), gapLimit);
            persistClosedOnly(fetched, timeFrame);
            merged.addAll(fetched);
        }

        List<MarketData> result = merged.stream()
                .sorted(Comparator.comparing(MarketData::getTimestamp))
                .distinct()
                .toList();

        if (limit > 0 && result.size() > limit) {
            result = result.subList(result.size() - limit, result.size());
        }
        return result;
    }

    /**
     * Ne persiste que les bougies déjà closes (cf. étude section 5) : une bougie H1 en cours
     * (celle qui contient "maintenant") ne doit jamais être écrite en cache, sous peine de la
     * figer avec des valeurs (high/low/close/volume) encore susceptibles de bouger.
     * <p>
     * Tente d'abord un {@code saveAll} en lot (rapide, cas nominal sans concurrence). En cas de
     * violation de contrainte unique (deux appels concurrents ont fetché le même trou), retombe
     * sur une écriture ligne à ligne où seules les lignes réellement dupliquées sont ignorées —
     * le contenu est de toute façon identique, la contrainte unique garantit juste qu'on ne
     * duplique pas (cf. étude section 6).
     */
    private void persistClosedOnly(List<MarketData> fetched, TimeFrame timeFrame) {
        if (fetched == null || fetched.isEmpty()) {
            return;
        }
        Instant now = clock.now();
        MarketDataSource source = delegate.getSource();

        List<CandleEntity> toPersist = fetched.stream()
                .filter(candle -> isClosed(candle, timeFrame, now))
                .map(candle -> toEntity(candle, source))
                .toList();

        if (toPersist.isEmpty()) {
            return;
        }

        try {
            repository.saveAll(toPersist);
        } catch (DataIntegrityViolationException e) {
            log.debug("Batch insert of {} candle(s) hit the unique constraint, retrying row by row: {}",
                    toPersist.size(), e.getMessage());
            persistRowByRow(toPersist);
        }
    }

    private void persistRowByRow(List<CandleEntity> candles) {
        for (CandleEntity candle : candles) {
            try {
                repository.saveAndFlush(candle);
            } catch (DataIntegrityViolationException ignored) {
                // Bougie déjà en cache (insérée entre temps par un appel concurrent) : ignorée.
            }
        }
    }

    /** Cf. étude section 5 : close(candle) = addTo(timestamp) ; close <= now => fermée. */
    static boolean isClosed(MarketData candle, TimeFrame timeFrame, Instant now) {
        return !timeFrame.addTo(candle.getTimestamp()).isAfter(now);
    }

    /**
     * Identifie les sous-plages manquantes en comparant la grille attendue
     * ({@code sinceGrid, sinceGrid+step, ..., untilGrid}) aux timestamps déjà en cache. Même
     * principe que le calendrier DCA de l'étude précédente (`TimeFrame.addTo` pas à pas).
     */
    static List<Range> findGaps(List<MarketData> cached, Instant sinceGrid, Instant untilGrid, TimeFrame timeFrame) {
        TreeSet<Instant> present = new TreeSet<>();
        for (MarketData candle : cached) {
            present.add(candle.getTimestamp());
        }

        List<Range> gaps = new ArrayList<>();
        Instant gapStart = null;
        Instant cursor = sinceGrid;

        while (!cursor.isAfter(untilGrid)) {
            boolean isPresent = present.contains(cursor);
            if (!isPresent && gapStart == null) {
                gapStart = cursor;
            } else if (isPresent && gapStart != null) {
                gaps.add(new Range(gapStart, timeFrame.removeTo(cursor, 1)));
                gapStart = null;
            }
            cursor = timeFrame.addTo(cursor);
        }
        if (gapStart != null) {
            gaps.add(new Range(gapStart, timeFrame.removeTo(cursor, 1)));
        }
        return gaps;
    }

    private static int gapSize(Range gap, TimeFrame timeFrame) {
        long stepSeconds = stepSeconds(timeFrame);
        long spanSeconds = Duration.between(gap.since(), gap.until()).getSeconds();
        return (int) (spanSeconds / stepSeconds) + 1;
    }

    /**
     * since explicite -> alignement sur la grille. since absent -> dérivé de {@code untilGrid}
     * et de {@code limit} (cas d'appel réel de {@code BinanceMarketDataProvider#fullLoad}, qui
     * n'utilise jamais {@code since}). Si ni l'un ni l'autre n'est exploitable, {@code null}
     * (pas de plage bornée possible : le cache est court-circuité par l'appelant).
     */
    private static Instant resolveSinceGrid(Instant since, Instant untilGrid, TimeFrame timeFrame, int limit) {
        if (since != null) {
            return floorToGrid(since, timeFrame);
        }
        if (limit > 0) {
            return timeFrame.removeTo(untilGrid, limit - 1L);
        }
        return null;
    }

    /**
     * Aligne un instant sur la grille de bougies du TimeFrame (multiples de sa durée depuis
     * epoch). Les bougies H1/H4/H12/D1/MIN1/MIN5 renvoyées par les exchanges sont alignées sur
     * cette même grille (ex: Binance klines 1h ouvrent pile à l'heure UTC) — sans ce floor, une
     * requête bornée par {@code now()} (jamais pile sur la grille) ferait systématiquement
     * "trou" là où le cache est en réalité complet.
     */
    static Instant floorToGrid(Instant instant, TimeFrame timeFrame) {
        long stepSeconds = stepSeconds(timeFrame);
        long epochSeconds = instant.getEpochSecond();
        long flooredEpochSeconds = Math.floorDiv(epochSeconds, stepSeconds) * stepSeconds;
        return Instant.ofEpochSecond(flooredEpochSeconds);
    }

    private static long stepSeconds(TimeFrame timeFrame) {
        return timeFrame.getUnit().getDuration().getSeconds() * timeFrame.getAmount();
    }

    private static CandleEntity toEntity(MarketData candle, MarketDataSource source) {
        return CandleEntity.builder()
                .source(source)
                .pair(candle.getPair())
                .timeFrame(candle.getTimeFrame())
                .timestamp(candle.getTimestamp())
                .open(candle.getOpen())
                .high(candle.getHigh())
                .low(candle.getLow())
                .close(candle.getClose())
                .volume(candle.getVolume())
                .build();
    }

    private static MarketData toMarketData(CandleEntity entity) {
        return MarketData.builder()
                .pair(entity.getPair())
                .timeFrame(entity.getTimeFrame())
                .timestamp(entity.getTimestamp())
                .open(entity.getOpen())
                .high(entity.getHigh())
                .low(entity.getLow())
                .close(entity.getClose())
                .volume(entity.getVolume())
                .build();
    }

    record Range(Instant since, Instant until) {
    }
}
