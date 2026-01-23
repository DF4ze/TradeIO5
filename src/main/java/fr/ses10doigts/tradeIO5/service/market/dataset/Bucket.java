package fr.ses10doigts.tradeIO5.service.market.dataset;// Bucket.java
// Composant bas niveau : stockage TF natif + vues agrégées déterministes
// - TF natif unique (ex: 1H)
// - Alignement temporel sémantique (jour, semaine…)
// - Dernière bougie agrégée potentiellement incomplète (live-like)

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;


public class Bucket {

    private static final Logger logger = LoggerFactory.getLogger(Bucket.class);
    private static final TimeFrame BASE_TIME_FRAME = TimeFrame.H1; // TODO : parametrize

    private final Deque<MarketData> buffer;

    @Getter
    private final TimeFrame baseTimeFrame; // ex: 1H

    @Getter
    private final int maxSize;

    // Cache simple des vues (clé = TF cible)
    // Invalidation dès qu'une nouvelle bougie est ajoutée
    private final Map<TimeFrame, CachedView> viewsCache = new EnumMap<>(TimeFrame.class);

    public Bucket(int maxSize) {
        this.baseTimeFrame = BASE_TIME_FRAME;
        this.maxSize = maxSize;
        this.buffer = new ArrayDeque<>();
    }

    public Bucket(TimeFrame baseTimeFrame, int maxSize) {
        this.baseTimeFrame = Objects.requireNonNull(baseTimeFrame);
        this.maxSize = maxSize;
        this.buffer = new ArrayDeque<>();
    }

    /* ================= INGESTION ================= */

    public void append(MarketData data) {
        if( !data.getTimeFrame().equals(baseTimeFrame) ){
            throw new IllegalArgumentException("Given MarketData TimeFrame differs from Bucket base TimeFrame");
        }

        if (buffer.isEmpty()) {
            buffer.addLast(data);
            invalidateViews();
            return;
        }

        MarketData last = buffer.peekLast();

        // forward-only strict
        if (!data.getTimestamp().isAfter(last.getTimestamp())) {
            logger.error("Given MarketData before last Bucket data");
            return;
        }

        buffer.addLast(data);

        if( buffer.size() > maxSize ){
            logger.warn("Total buffer size is oversize({} for {}), removing last data", buffer.size(), maxSize);
        }

        while (buffer.size() > maxSize) {
            buffer.removeFirst();
        }

        invalidateViews();
    }

    /* ================= VIEWS ================= */

    public BucketView view(TimeFrame targetTimeFrame) {
        if (targetTimeFrame.equals(baseTimeFrame)) {
            return List.copyOf(buffer);
        }

        if (!targetTimeFrame.isMultipleOf(baseTimeFrame)) {
            throw new IllegalArgumentException(
                    "Target timeframe must be a multiple of base timeframe"
            );
        }

        CachedView cached = viewsCache.get(targetTimeFrame);
        Instant lastBaseTs = buffer.isEmpty() ? null : buffer.peekLast().getTimestamp();

        if (cached != null && Objects.equals(cached.lastBaseTimestamp, lastBaseTs)) {
            return cached.data;
        }

        List<MarketData> aggregated = aggregate(targetTimeFrame);
        viewsCache.put(targetTimeFrame, new CachedView(lastBaseTs, aggregated));
        return aggregated;
    }

    /* ================= AGGREGATION ================= */

    private BucketView aggregate(TimeFrame targetTimeFrame) {
        Map<Instant, List<MarketData>> groups = new LinkedHashMap<>();

        for (MarketData data : buffer) {
            Instant bucketTs = alignTimestamp(data.getTimestamp(), targetTimeFrame);
            groups.computeIfAbsent(bucketTs, k -> new ArrayList<>()).add(data);
        }

        List<MarketData> result = new ArrayList<>(groups.size());

        for (Map.Entry<Instant, List<MarketData>> entry : groups.entrySet()) {
            List<MarketData> candles = entry.getValue();

            MarketData first = candles.get(0);
            MarketData last = candles.get(candles.size() - 1);

            BigDecimal high = candles.stream()
                    .map(MarketData::getHigh)
                    .max(BigDecimal::compareTo)
                    .orElse(first.getHigh());

            BigDecimal low = candles.stream()
                    .map(MarketData::getLow)
                    .min(BigDecimal::compareTo)
                    .orElse(first.getLow());

            BigDecimal volume = candles.stream()
                    .map(MarketData::getVolume)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            result.add(MarketData.builder()
                    .pair(first.getPair())
                    .timeFrame(targetTimeFrame)
                    .timestamp(entry.getKey())
                    .open(first.getOpen())
                    .close(last.getClose())
                    .high(high)
                    .low(low)
                    .volume(volume)
                    .build());
        }

        return result;
    }

    /* ================= ALIGNMENT ================= */

    private Instant alignTimestamp(Instant ts, TimeFrame tf) {
        ZonedDateTime zdt = ts.atZone(ZoneOffset.UTC);

        return switch (tf) {
            case H1, H4 -> {
                long seconds = tf.getDuration().getSeconds();
                long aligned = (ts.getEpochSecond() / seconds) * seconds;
                yield Instant.ofEpochSecond(aligned);
            }
            case D1 -> zdt.toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            case W1 -> zdt.with(DayOfWeek.MONDAY)
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
            default -> throw new IllegalStateException("Unsupported timeframe: " + tf);
        };
    }

    /* ================= CACHE ================= */

    private void invalidateViews() {
        viewsCache.clear();
    }

    private static class CachedView {
        private final Instant lastBaseTimestamp;
        private final List<MarketData> data;

        private CachedView(Instant lastBaseTimestamp, List<MarketData> data) {
            this.lastBaseTimestamp = lastBaseTimestamp;
            this.data = List.copyOf(data);
        }
    }
}
