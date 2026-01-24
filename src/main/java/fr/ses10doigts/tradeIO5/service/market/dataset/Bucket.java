package fr.ses10doigts.tradeIO5.service.market.dataset;// Bucket.java
// Composant bas niveau : stockage TF natif + vues agrégées déterministes
// - TF natif unique (ex: 1H)
// - Alignement temporel sémantique (jour, semaine…)
// - Dernière bougie agrégée potentiellement incomplète (live-like)

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;


public class Bucket {

    private static final Logger logger = LoggerFactory.getLogger(Bucket.class);

    private static final TimeFrame BASE_TIME_FRAME = TimeFrame.H1; // TODO : parametrize
    static final int BASE_MAX_ITEMS = 5000;

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
    public BucketView view(TimeFrame targetTimeFrame, Instant now) {

        if (buffer.isEmpty()) {
            logger.debug("Buffer Empty...");
            return new BucketView(List.of(), CompletenessLevel.INCOMPLETE, targetTimeFrame);
        }

        List<MarketData> data = null;
        if( targetTimeFrame == baseTimeFrame ){
            logger.debug("Target and Base TF are same.");
            data = buffer.stream()
                    .sorted(Comparator.comparing(MarketData::getTimestamp))
                    .toList();

        }else {

            if (!canAggregate(baseTimeFrame, targetTimeFrame)) {
                logger.debug("Can't aggregate {} to {}", baseTimeFrame, targetTimeFrame);
                return new BucketView(List.of(), CompletenessLevel.INCOMPLETE, targetTimeFrame);
            }else{
                logger.debug("Ok Aggregate.");
            }

            // récupération ou calcul de la vue
            data = getOrBuildView(targetTimeFrame);
        }

        // 2️⃣ calcul de la complétude
        CompletenessLevel completeness =
                computeCompleteness(data, targetTimeFrame, now);

        return new BucketView(
                data,
                completeness,
                targetTimeFrame
                );
    }

    private List<MarketData> getOrBuildView(TimeFrame tf) {

        if (tf.equals(baseTimeFrame)) {
            logger.debug("Same TF, just copy...");
            return List.copyOf(buffer);
        }

        Instant lastBaseTs = null;
        if (buffer.peekLast() != null) { // for "null warning"
            lastBaseTs = buffer.peekLast().getTimestamp();
            logger.debug("Retrieve last data ts : {}", lastBaseTs);
        }

        CachedView cached = viewsCache.get(tf);
        if (cached != null && cached.lastBaseTimestamp.equals(lastBaseTs)) {
            logger.debug("Data found in cache.");
            return cached.data;
        }

        List<MarketData> aggregated = aggregate(tf);
        viewsCache.put(tf, new CachedView(lastBaseTs, aggregated));
        logger.debug("Cached ++ : {}", viewsCache.size());

        return aggregated;
    }



    /* ================= AGGREGATION ================= */

    private List<MarketData> aggregate(TimeFrame targetTimeFrame) {

        Map<Instant, List<MarketData>> groups = new LinkedHashMap<>();

        for (MarketData data : buffer) {
            Instant bucketTs = alignTimestamp(data.getTimestamp(), targetTimeFrame);
            groups.computeIfAbsent(bucketTs, k -> new ArrayList<>()).add(data);
        }
        logger.debug("AlignTs, nb group: {}", groups.size());

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
        logger.debug("Aggregated Data : {}", result.size());

        return result;
    }

    /* ================= ALIGNMENT ================= */

    private Instant alignTimestamp(Instant ts, TimeFrame tf) {

        ZoneId zone = TimeFrame.DEFAULT_ZONE;
        ZonedDateTime zdt = ts.atZone(zone);

        return switch (tf.getUnit()) {

            // ===== FIXED =====

            case MINUTES -> zdt
                    .withSecond(0)
                    .withNano(0)
                    .minusMinutes(zdt.getMinute() % tf.getAmount())
                    .toInstant();

            case HOURS -> zdt
                    .withMinute(0)
                    .withSecond(0)
                    .withNano(0)
                    .minusHours(zdt.getHour() % tf.getAmount())
                    .toInstant();

            case DAYS -> zdt
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant();

            // ===== CALENDAR =====

            case WEEKS -> zdt
                    .with(DayOfWeek.MONDAY)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant();

            case MONTHS -> zdt
                    .withDayOfMonth(1)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant();

            case YEARS -> zdt
                    .withDayOfYear(1)
                    .toLocalDate()
                    .atStartOfDay(zone)
                    .toInstant();

            default -> throw new IllegalArgumentException("Unsupported TimeFrame: " + tf);
        };
    }

    private boolean isAligned(Instant ts, TimeFrame tf) {
        return alignTimestamp(ts, tf).equals(ts);
    }



    private CompletenessLevel computeCompleteness(
            List<MarketData> data,
            TimeFrame tf,
            Instant now
    ) {

        if (data.isEmpty()) {
            logger.debug("Completeness : data is empty. -> INCOMPLETE");
            return CompletenessLevel.INCOMPLETE;
        }

        // 1. alignement du premier
        if (!alignTimestamp(data.get(0).getTimestamp(), tf)
                .equals(data.get(0).getTimestamp())) {
            logger.debug("Completeness : First data not align -> INCOMPLETE");
            return CompletenessLevel.INCOMPLETE;
        }else
            logger.debug("Completeness : First data align.");

        // 2. continuité temporelle
        for (int i = 1; i < data.size(); i++) {
            Instant expected = tf.addTo(data.get(i - 1).getTimestamp());

            if (!data.get(i).getTimestamp().equals(expected)) {
                logger.debug("Completeness : Gape in data (data:{}, expected:{} > tf:{}) -> INCOMPLETE",
                        data.get(i).getTimestamp(), expected, tf);
                return CompletenessLevel.INCOMPLETE;
            }
        }

        // 3. dernière période
        MarketData last = data.get(data.size() - 1);
        Instant lastEnd = tf.addTo(last.getTimestamp());

        if (lastEnd.isAfter(now)) {
            logger.debug("Completeness : End before alignment -> PARTIAL_LAST");
            return CompletenessLevel.PARTIAL_LAST;
        }

        logger.debug("Completeness : Ok -> COMPLETE");
        return CompletenessLevel.COMPLETE;
    }

    /* =============== UTILITIES =============== */
    private boolean canAggregate(TimeFrame base, TimeFrame target) {
        return target.isGreaterOrEqualThan(base);
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    public MarketData peekLast() {
        return buffer.peekLast();
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
            this.data = data;
        }
    }

}
