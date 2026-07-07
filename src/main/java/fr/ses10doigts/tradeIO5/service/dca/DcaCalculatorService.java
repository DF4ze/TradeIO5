package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.exceptions.DcaException;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaOccurrence;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Calcule une simulation DCA (Dollar-Cost Averaging) : prix moyen pondéré, total investi, PnL,
 * pour un calendrier d'achats généré à fréquence fixe. Cf. docs/etude-dca-tool-mcp.md.
 * <p>
 * Bypass volontaire de {@code MarketDatasetEngine}/{@code Bucket} (cache glissant ancré sur
 * "maintenant", inadapté à des points historiques épars sur plusieurs mois/années — cf. étude
 * section 1) : ce service appelle directement un {@link MarketDataApiClient} (par défaut la
 * version {@code cachingBinanceMarketDataApiClient}, qui persiste déjà les bougies H1 en base —
 * cf. {@code CachingMarketDataApiClient} / etude-cache-db-candles-h1.md), avec sa propre
 * pagination par blocs de {@value #CHUNK_HOURS} bougies H1 (limite Binance par appel).
 */
@Service
public class DcaCalculatorService {

    private static final int SCALE = 10;
    private static final int CHUNK_HOURS = 1000;
    /**
     * Kraken/OKX ne renvoient en pratique que les ~30 derniers jours de bougies H1 quel que soit
     * le {@code since} demandé (cf. étude section 4) : au-delà, le calcul serait silencieusement
     * tronqué. On préfère un échec explicite (cf. étude section 2 : "comportement si la période
     * précède l'historique disponible").
     */
    private static final long NON_BINANCE_MAX_HORIZON_DAYS = 25;

    private final Map<MarketDataSource, MarketDataApiClient> clientsBySource;
    private final DomainClock clock;

    public DcaCalculatorService(
            @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceClient,
            @Qualifier("cachingKrakenMarketDataApiClient") MarketDataApiClient krakenClient,
            @Qualifier("cachingOkxMarketDataApiClient") MarketDataApiClient okxClient,
            DomainClock clock
    ) {
        this.clientsBySource = new EnumMap<>(MarketDataSource.class);
        this.clientsBySource.put(MarketDataSource.BINANCE, binanceClient);
        this.clientsBySource.put(MarketDataSource.KRAKEN, krakenClient);
        this.clientsBySource.put(MarketDataSource.OKX, okxClient);
        this.clock = clock;
    }

    public DcaResult calculate(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            TimeFrame frequency,
            int purchaseHourUtc,
            BigDecimal amount,
            BigDecimal feePercent,
            MarketDataSource source
    ) {
        validate(symbol, startDate, endDate, frequency, purchaseHourUtc, amount, feePercent);
        MarketDataSource effectiveSource = source != null ? source : MarketDataSource.BINANCE;
        MarketDataApiClient client = resolveClient(effectiveSource);
        BigDecimal effectiveFeePercent = feePercent != null ? feePercent : BigDecimal.ZERO;

        Instant firstOccurrence = startDate.atTime(purchaseHourUtc, 0).atZone(TimeFrame.DEFAULT_ZONE).toInstant();
        Instant endInstant = endDate.atTime(purchaseHourUtc, 0).atZone(TimeFrame.DEFAULT_ZONE).toInstant();
        Instant now = clock.now();
        Instant effectiveEnd = endInstant.isAfter(now) ? now : endInstant;

        List<Instant> schedule = buildSchedule(firstOccurrence, effectiveEnd, frequency);
        if (schedule.isEmpty()) {
            throw new DcaException("Aucune échéance DCA dans la période demandée : la date de "
                    + "début (" + startDate + " " + purchaseHourUtc + "h UTC) est postérieure à "
                    + "la date de fin effective (" + effectiveEnd + ").");
        }

        Instant firstHour = floorToHour(schedule.get(0));
        Instant lastHour = floorToHour(schedule.get(schedule.size() - 1));

        long horizonDays = Duration.between(firstHour, lastHour).toDays();
        if (effectiveSource != MarketDataSource.BINANCE && horizonDays > NON_BINANCE_MAX_HORIZON_DAYS) {
            throw new DcaException("La source " + effectiveSource + " ne fournit fiablement que "
                    + "les ~" + NON_BINANCE_MAX_HORIZON_DAYS + " derniers jours de bougies H1 : "
                    + "cette simulation couvre " + horizonDays + " jour(s). Utilisez BINANCE "
                    + "(recommandé par défaut) pour un DCA de cet horizon.");
        }

        TreeMap<Instant, MarketData> candlesByHour = fetchCandleRange(client, symbol, firstHour, lastHour);
        if (candlesByHour.isEmpty()) {
            throw new DcaException("Aucune bougie H1 disponible pour " + symbol + " (" + effectiveSource
                    + ") entre " + firstHour + " et " + lastHour + ".");
        }

        BigDecimal currentPrice = fetchCurrentPrice(client, symbol, now);
        if (currentPrice == null) {
            throw new DcaException("Impossible de résoudre le prix courant de " + symbol + " (" + effectiveSource + ") pour calculer le PnL.");
        }

        List<DcaOccurrence> occurrences = new ArrayList<>(schedule.size());
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        int missingCount = 0;

        for (Instant occurrence : schedule) {
            MarketData candle = candlesByHour.get(floorToHour(occurrence));
            if (candle == null) {
                occurrences.add(DcaOccurrence.builder()
                        .timestamp(occurrence)
                        .plannedAmount(amount)
                        .missing(true)
                        .build());
                missingCount++;
                continue;
            }

            BigDecimal price = candle.getOpen();
            BigDecimal fee = amount.multiply(effectiveFeePercent)
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
            BigDecimal netAmount = amount.subtract(fee);
            BigDecimal quantity = netAmount.divide(price, SCALE, RoundingMode.HALF_UP);

            occurrences.add(DcaOccurrence.builder()
                    .timestamp(occurrence)
                    .plannedAmount(amount)
                    .missing(false)
                    .price(price)
                    .fee(fee)
                    .quantity(quantity)
                    .build());

            totalInvested = totalInvested.add(amount);
            totalFees = totalFees.add(fee);
            totalQuantity = totalQuantity.add(quantity);
        }

        BigDecimal avgPrice = totalQuantity.signum() > 0
                ? totalInvested.divide(totalQuantity, SCALE, RoundingMode.HALF_UP)
                : null;
        BigDecimal currentValue = totalQuantity.multiply(currentPrice);
        BigDecimal pnl = totalInvested.signum() > 0 ? currentValue.subtract(totalInvested) : null;
        BigDecimal pnlPercent = (pnl != null && totalInvested.signum() > 0)
                ? pnl.divide(totalInvested, SCALE, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        return DcaResult.builder()
                .symbol(symbol)
                .source(effectiveSource)
                .frequency(frequency)
                .purchaseHourUtc(purchaseHourUtc)
                .firstOccurrence(schedule.get(0))
                .lastOccurrence(schedule.get(schedule.size() - 1))
                .occurrenceCount(schedule.size())
                .missingCount(missingCount)
                .totalInvested(totalInvested)
                .totalFees(totalFees)
                .totalQuantity(totalQuantity)
                .avgPrice(avgPrice)
                .currentPrice(currentPrice)
                .currentValue(currentValue)
                .pnl(pnl)
                .pnlPercent(pnlPercent)
                .occurrences(occurrences)
                .build();
    }

    private void validate(
            String symbol, LocalDate startDate, LocalDate endDate, TimeFrame frequency,
            int purchaseHourUtc, BigDecimal amount, BigDecimal feePercent
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new DcaException("symbol est requis");
        }
        if (startDate == null || endDate == null) {
            throw new DcaException("startDate et endDate sont requis");
        }
        if (startDate.isAfter(endDate)) {
            throw new DcaException("startDate (" + startDate + ") doit être avant ou égal à endDate (" + endDate + ")");
        }
        if (frequency == null || !(frequency.isCalendar() || frequency == TimeFrame.D1)) {
            throw new DcaException("frequency invalide (" + frequency + ") : seules les fréquences calendaires "
                    + "(D1, W1, W2, M1, M2, M3, M6, Y1, Y3) ont un sens comme cadence DCA.");
        }
        if (purchaseHourUtc < 0 || purchaseHourUtc > 23) {
            throw new DcaException("purchaseHourUtc doit être compris entre 0 et 23 (heure UTC), reçu : " + purchaseHourUtc);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new DcaException("amount doit être strictement positif");
        }
        if (feePercent != null && (feePercent.signum() < 0 || feePercent.compareTo(BigDecimal.valueOf(100)) >= 0)) {
            throw new DcaException("feePercent doit être compris entre 0 (inclus) et 100 (exclu), reçu : " + feePercent);
        }
    }

    private MarketDataApiClient resolveClient(MarketDataSource source) {
        MarketDataApiClient client = clientsBySource.get(source);
        if (client == null) {
            throw new DcaException("Source de marché non supportée pour le DCA : " + source
                    + " (supportées : " + clientsBySource.keySet() + ")");
        }
        return client;
    }

    /** Cf. étude section 3 : {@code TimeFrame.addTo} préserve l'heure d'achat à chaque pas (arithmétique UTC). */
    private static List<Instant> buildSchedule(Instant first, Instant end, TimeFrame frequency) {
        List<Instant> schedule = new ArrayList<>();
        Instant current = first;
        while (!current.isAfter(end)) {
            schedule.add(current);
            current = frequency.addTo(current, 1);
        }
        return schedule;
    }

    /**
     * Récupère toute la série H1 sur [firstHour, lastHour] par blocs de {@value #CHUNK_HOURS}
     * bougies (limite par appel Binance), plutôt qu'un appel réseau par échéance (cf. étude
     * section 4). Le client injecté est déjà {@code CachingMarketDataApiClient} : les blocs déjà
     * en base ne redéclenchent aucun appel réseau.
     */
    private static TreeMap<Instant, MarketData> fetchCandleRange(
            MarketDataApiClient client, String symbol, Instant firstHour, Instant lastHour
    ) {
        TreeMap<Instant, MarketData> byHour = new TreeMap<>();
        Instant cursor = firstHour;
        Duration chunkSpan = Duration.ofHours(CHUNK_HOURS - 1L);

        while (!cursor.isAfter(lastHour)) {
            Instant chunkEnd = cursor.plus(chunkSpan);
            if (chunkEnd.isAfter(lastHour)) {
                chunkEnd = lastHour;
            }
            List<MarketData> candles = client.getCandles(symbol, TimeFrame.H1, cursor, chunkEnd, CHUNK_HOURS);
            for (MarketData candle : candles) {
                byHour.put(floorToHour(candle.getTimestamp()), candle);
            }
            cursor = chunkEnd.plusSeconds(3600);
        }
        return byHour;
    }

    /** Même mécanique que {@code TreeAnalysisFacade.extractLastPrice} : dernière bougie H1 disponible. */
    private static BigDecimal fetchCurrentPrice(MarketDataApiClient client, String symbol, Instant now) {
        List<MarketData> candles = client.getCandles(symbol, TimeFrame.H1, null, now, 1);
        if (candles.isEmpty()) {
            return null;
        }
        return candles.get(candles.size() - 1).getClose();
    }

    /** Aligne un instant sur la grille H1 (multiples de 3600s depuis epoch), cf. CachingMarketDataApiClient#floorToGrid. */
    private static Instant floorToHour(Instant instant) {
        long epochSeconds = instant.getEpochSecond();
        long flooredEpochSeconds = Math.floorDiv(epochSeconds, 3600L) * 3600L;
        return Instant.ofEpochSecond(flooredEpochSeconds);
    }
}
