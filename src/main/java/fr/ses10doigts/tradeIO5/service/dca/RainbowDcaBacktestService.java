package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.exceptions.DcaException;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.Bucket;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Backtest historique du DCA "Rainbow" (V0, résolution D1 pure) : montant/action modulés par la
 * position du prix par rapport à une SMA et ses bornes % ({@code RainbowSmaIndicator}, jamais
 * consommé par aucune Strategy — ne pas le modifier). Indépendant du DCA à montant fixe existant
 * ({@link DcaCalculatorService}), dont on ne réutilise le résultat que pour le bloc comparatif.
 * <p>
 * Cf. docs/prompts/prompt-implementation-dca-rainbow-v0.md et mémoire projet
 * tradeio5_dca_rainbow_decoupled_architecture_2026-09-12.md /
 * tradeio5_dca_rainbow_v0_zone_table_clarification_2026-09-12.md (règle exacte des zones,
 * corrige le tableau du prompt d'origine — fait foi ici).
 * <p>
 * Resampling H1→D1 : même patron que {@code tools/calibration/BucketResample.java} — fetch H1 en
 * bloc via le client Binance déjà injecté dans {@link DcaCalculatorService}, agrégation D1 réelle
 * via {@link Bucket} — volontairement PAS {@code MarketDatasetEngine} (cache "live" inadapté à un
 * calcul one-shot sur tout l'historique, cf. étude-dca-tool-mcp.md §10).
 * <p>
 * Multiplicateurs par zone et bornes % : tous paramétrables via {@link RainbowDcaBacktestRequest}
 * depuis l'introduction du bench de calibration (docs/calibration/calibration-dca-rainbow-bounds-multipliers.md) —
 * {@link RainbowZone} reste un classifieur pur, sans valeur de multiplicateur embarquée.
 * <p>
 * {@link ReentryMode} (2026-09-13, cf. docs/etudes/etude-dca-tool-mcp.md §12) : la méthode de
 * sortie des deux machines à état ARMÉ (achat sous percdown2, vente au-dessus de percup3) est
 * paramétrable indépendamment pour chaque côté ({@code buyReentryMode}/{@code sellReentryMode}) —
 * {@code buyTriggered}/{@code sellTriggered} ci-dessous. Les défauts reproduisent exactement le
 * comportement V0 d'origine (TRAILING_STOP achat, IMMEDIATE vente), donc aucune régression sur les
 * scénarios déjà couverts par {@code RainbowDcaBacktestServiceTest}.
 */
@Service
public class RainbowDcaBacktestService {

    private static final int SCALE = 10;
    /** Même pagination par blocs que {@link DcaCalculatorService} (limite Binance par appel). */
    private static final int CHUNK_HOURS = 1000;

    private final MarketDataApiClient binanceClient;
    private final DomainClock clock;
    private final AssetProviderRepository assetProviderRepository;
    private final DcaCalculatorService dcaCalculatorService;

    public RainbowDcaBacktestService(
            @Qualifier("cachingBinanceMarketDataApiClient") MarketDataApiClient binanceClient,
            DomainClock clock,
            AssetProviderRepository assetProviderRepository,
            DcaCalculatorService dcaCalculatorService
    ) {
        this.binanceClient = binanceClient;
        this.clock = clock;
        this.assetProviderRepository = assetProviderRepository;
        this.dcaCalculatorService = dcaCalculatorService;
    }

    public RainbowDcaBacktestResult backtest(RainbowDcaBacktestRequest request) {
        validate(request);

        Instant now = clock.now();
        LocalDate today = LocalDate.ofInstant(now, TimeFrame.DEFAULT_ZONE);
        LocalDate maxClosedDate = today.minusDays(1);
        LocalDate startDate = request.getStartDate();
        LocalDate endDate = request.getEndDate().isAfter(maxClosedDate) ? maxClosedDate : request.getEndDate();

        if (startDate.isAfter(endDate)) {
            throw new DcaException("Aucune bougie D1 disponible à partir de " + startDate + " pour " + request.getSymbol() + ".");
        }

        String providerSymbol = resolveProviderSymbol(request.getSymbol());

        LocalDate warmupStart = startDate.minusDays(request.getSmaPeriod());
        Instant fetchFrom = warmupStart.atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant();
        Instant fetchTo = endDate.atTime(23, 0).atZone(TimeFrame.DEFAULT_ZONE).toInstant();

        List<MarketData> h1Candles = fetchH1Range(providerSymbol, fetchFrom, fetchTo);
        if (h1Candles.isEmpty()) {
            throw new DcaException("Aucune bougie H1 disponible pour " + request.getSymbol() + " (" + providerSymbol
                    + ") entre " + fetchFrom + " et " + fetchTo + ".");
        }

        Bucket bucket = new Bucket(TimeFrame.H1, h1Candles.size() + 100);
        for (MarketData candle : h1Candles) {
            bucket.append(candle);
        }

        BucketView d1View = bucket.view(TimeFrame.D1, now);
        List<MarketData> d1Candles = new ArrayList<>(d1View.data());
        // La dernière bougie D1 peut être une journée encore en cours (pas encore close) : on
        // l'exclut du backtest, comme CachingMarketDataApiClient#isClosed le fait pour le cache H1.
        if (d1View.completeness() == CompletenessLevel.PARTIAL_LAST && !d1Candles.isEmpty()) {
            d1Candles.removeLast();
        }

        Map<LocalDate, MarketData> byDate = new TreeMap<>();
        for (MarketData candle : d1Candles) {
            byDate.put(LocalDate.ofInstant(candle.getTimestamp(), TimeFrame.DEFAULT_ZONE), candle);
        }

        List<LocalDate> orderedDates = new ArrayList<>(byDate.keySet());
        int startIndex = 0;
        while (startIndex < orderedDates.size() && orderedDates.get(startIndex).isBefore(startDate)) {
            startIndex++;
        }
        if (startIndex < request.getSmaPeriod()) {
            throw new DcaException("Historique insuffisant pour calculer une SMA(" + request.getSmaPeriod()
                    + ") causale au " + startDate + " : seulement " + startIndex + " jour(s) de warm-up disponible(s) "
                    + "(bougies D1 dispo à partir de " + (orderedDates.isEmpty() ? "aucune" : orderedDates.getFirst()) + ").");
        }
        if (startIndex >= orderedDates.size()) {
            throw new DcaException("Aucune bougie D1 disponible à partir de " + startDate + " pour " + request.getSymbol() + ".");
        }

        List<RainbowDcaOccurrence> occurrences = new ArrayList<>();

        RainbowDcaOccurrence.ArmState buyState = RainbowDcaOccurrence.ArmState.NONE;
        BigDecimal lowestSinceArmed = null;
        int buyArmedDays = 0;
        RainbowDcaOccurrence.ArmState sellState = RainbowDcaOccurrence.ArmState.NONE;
        BigDecimal highestSinceArmed = null;
        int sellArmedDays = 0;
        int cooldownRemaining = 0;
        int cadenceCounter = 0;

        BigDecimal position = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalQuantityBought = BigDecimal.ZERO;
        BigDecimal totalQuantitySold = BigDecimal.ZERO;
        BigDecimal totalSaleProceeds = BigDecimal.ZERO;
        int zoneBuyCount = 0;
        int buyTriggeredCount = 0;
        int sellTriggeredCount = 0;

        BigDecimal trailingStopUpFactor = BigDecimal.ONE.add(
                request.getTrailingStopPercent().divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP));
        BigDecimal trailingStopDownFactor = BigDecimal.ONE.subtract(
                request.getTrailingStopPercent().divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP));

        for (int i = startIndex; i < orderedDates.size(); i++) {
            LocalDate date = orderedDates.get(i);
            if (date.isAfter(endDate)) {
                break;
            }

            BigDecimal close = byDate.get(date).getClose();
            BigDecimal sma = computeSma(orderedDates, byDate, i, request.getSmaPeriod());

            BigDecimal percdown2Level = level(sma, request.getPercDown2());
            BigDecimal percdown1Level = level(sma, request.getPercDown1());
            BigDecimal percup1Level = level(sma, request.getPercUp1());
            BigDecimal percup2Level = level(sma, request.getPercUp2());
            BigDecimal percup3Level = level(sma, request.getPercUp3());

            RainbowZone zone = RainbowZone.classify(close, percdown2Level, percdown1Level, percup1Level, percup2Level, percup3Level);

            // ---- VENTE (traitée avant l'achat : une vente déclenchée aujourd'hui active le
            // cooldown qui peut bloquer l'achat de ce même jour, cf. javadoc plus bas) ----
            RainbowDcaOccurrence.SellAction sellAction = RainbowDcaOccurrence.SellAction.NONE;
            BigDecimal sellQuantity = BigDecimal.ZERO;
            BigDecimal saleProceeds = BigDecimal.ZERO;

            if (sellState == RainbowDcaOccurrence.ArmState.ARMED) {
                sellArmedDays++;
                boolean triggered = sellTriggered(request, close, percup3Level, percup2Level, highestSinceArmed,
                        trailingStopDownFactor, sellArmedDays);
                if (triggered) {
                    sellQuantity = position.multiply(request.getSellFraction());
                    if (sellQuantity.signum() > 0) {
                        saleProceeds = sellQuantity.multiply(close);
                        position = position.subtract(sellQuantity);
                        totalQuantitySold = totalQuantitySold.add(sellQuantity);
                        totalSaleProceeds = totalSaleProceeds.add(saleProceeds);
                        sellAction = RainbowDcaOccurrence.SellAction.TRIGGERED;
                        sellTriggeredCount++;
                    }
                    sellState = RainbowDcaOccurrence.ArmState.NONE;
                    highestSinceArmed = null;
                    sellArmedDays = 0;
                    cooldownRemaining = request.getCooldownDays();
                } else {
                    // reste armée, pas de vente ce jour (les achats de zone intermédiaire restent
                    // actifs entre-temps, cf. prompt) : on étend le pic observé pour les prochains
                    // jours (TRAILING_STOP), sans effet sur IMMEDIATE/FIXED_DELAY.
                    highestSinceArmed = highestSinceArmed.max(close);
                }
            } else if (zone == RainbowZone.EXTREME_HAUT) {
                sellState = RainbowDcaOccurrence.ArmState.ARMED; // activation, pas de vente ce jour-là
                highestSinceArmed = close;
                sellArmedDays = 0;
            }

            // ---- ACHAT ----
            RainbowDcaOccurrence.BuyAction buyAction = RainbowDcaOccurrence.BuyAction.NONE;
            BigDecimal buyMultiplier = null;
            BigDecimal amountInvested = BigDecimal.ZERO;
            BigDecimal quantityBought = BigDecimal.ZERO;
            boolean cadenceTick = (cadenceCounter % request.getCadenceDays()) == 0;

            if (buyState == RainbowDcaOccurrence.ArmState.ARMED) {
                buyArmedDays++;
                boolean triggered = buyTriggered(request, close, percdown2Level, lowestSinceArmed,
                        trailingStopUpFactor, buyArmedDays);
                if (triggered) {
                    buyMultiplier = request.getMultTriggered();
                    buyAction = RainbowDcaOccurrence.BuyAction.TRIGGERED;
                    buyState = RainbowDcaOccurrence.ArmState.NONE;
                    lowestSinceArmed = null;
                    buyArmedDays = 0;
                } else {
                    lowestSinceArmed = lowestSinceArmed.min(close);
                }
            } else if (zone == RainbowZone.EXTREME_BAS) {
                buyState = RainbowDcaOccurrence.ArmState.ARMED;
                lowestSinceArmed = close; // activation, pas d'achat ce jour-là
                buyArmedDays = 0;
            } else if (cadenceTick) {
                BigDecimal intermediateMultiplier = resolveIntermediateMultiplier(zone, request);
                if (intermediateMultiplier != null && intermediateMultiplier.signum() > 0) {
                    buyMultiplier = intermediateMultiplier;
                    buyAction = RainbowDcaOccurrence.BuyAction.ZONE;
                }
            }

            if (buyMultiplier != null) {
                if (cooldownRemaining == 0) {
                    amountInvested = request.getBaseAmount().multiply(buyMultiplier);
                    quantityBought = amountInvested.divide(close, SCALE, RoundingMode.HALF_UP);
                    position = position.add(quantityBought);
                    totalInvested = totalInvested.add(amountInvested);
                    totalQuantityBought = totalQuantityBought.add(quantityBought);
                    if (buyAction == RainbowDcaOccurrence.BuyAction.TRIGGERED) {
                        buyTriggeredCount++;
                    } else {
                        zoneBuyCount++;
                    }
                } else {
                    // Cooldown actif (y compris déclenché par la vente de ce même jour) : aucun
                    // achat n'a lieu, quelle que soit la zone (cf. prompt) — l'état/le
                    // multiplicateur "aurait dû" ci-dessus reste tracé mais le montant reste nul.
                    buyAction = RainbowDcaOccurrence.BuyAction.NONE;
                    buyMultiplier = null;
                }
            }

            cadenceCounter++;
            if (cooldownRemaining > 0) {
                cooldownRemaining--;
            }

            occurrences.add(RainbowDcaOccurrence.builder()
                    .date(date)
                    .close(close)
                    .sma(sma)
                    .zone(zone)
                    .buyState(buyState)
                    .sellState(sellState)
                    .buyAction(buyAction)
                    .sellAction(sellAction)
                    .buyMultiplier(buyMultiplier)
                    .amountInvested(amountInvested)
                    .quantityBought(quantityBought)
                    .quantitySold(sellQuantity)
                    .saleProceeds(saleProceeds)
                    .positionAfter(position)
                    .cooldownRemaining(cooldownRemaining)
                    .build());
        }

        BigDecimal currentPrice = byDate.get(endDate).getClose();
        BigDecimal currentValue = position.multiply(currentPrice);
        BigDecimal avgBuyPrice = totalQuantityBought.signum() > 0
                ? totalInvested.divide(totalQuantityBought, SCALE, RoundingMode.HALF_UP)
                : null;
        BigDecimal pnl = totalInvested.signum() > 0
                ? currentValue.add(totalSaleProceeds).subtract(totalInvested)
                : null;
        BigDecimal pnlPercent = (pnl != null && totalInvested.signum() > 0)
                ? pnl.divide(totalInvested, SCALE, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        DcaResult fixedDcaComparison = dcaCalculatorService.calculate(
                request.getSymbol(), startDate, endDate, TimeFrame.D1, 0,
                request.getBaseAmount(), null, MarketDataSource.BINANCE);

        return RainbowDcaBacktestResult.builder()
                .symbol(request.getSymbol())
                .startDate(startDate)
                .endDate(endDate)
                .parameters(request)
                .occurrenceCount(occurrences.size())
                .zoneBuyCount(zoneBuyCount)
                .buyTriggeredCount(buyTriggeredCount)
                .sellTriggeredCount(sellTriggeredCount)
                .totalInvested(totalInvested)
                .totalQuantityBought(totalQuantityBought)
                .totalQuantitySold(totalQuantitySold)
                .totalSaleProceeds(totalSaleProceeds)
                .remainingQuantity(position)
                .avgBuyPrice(avgBuyPrice)
                .currentPrice(currentPrice)
                .currentValue(currentValue)
                .pnl(pnl)
                .pnlPercent(pnlPercent)
                .occurrences(occurrences)
                .fixedDcaComparison(fixedDcaComparison)
                .build();
    }

    private void validate(RainbowDcaBacktestRequest request) {
        if (request.getSymbol() == null || request.getSymbol().isBlank()) {
            throw new DcaException("symbol est requis");
        }
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new DcaException("startDate et endDate sont requis");
        }
        if (request.getStartDate().isAfter(request.getEndDate())) {
            throw new DcaException("startDate (" + request.getStartDate() + ") doit être avant ou égal à endDate ("
                    + request.getEndDate() + ")");
        }
        if (request.getSmaPeriod() <= 0) {
            throw new DcaException("smaPeriod doit être strictement positif, reçu : " + request.getSmaPeriod());
        }
        if (request.getBaseAmount() == null || request.getBaseAmount().signum() <= 0) {
            throw new DcaException("baseAmount doit être strictement positif");
        }
        if (request.getCadenceDays() <= 0) {
            throw new DcaException("cadenceDays doit être strictement positif, reçu : " + request.getCadenceDays());
        }
        if (request.getCooldownDays() < 0) {
            throw new DcaException("cooldownDays ne peut pas être négatif, reçu : " + request.getCooldownDays());
        }
        if (request.getSellFraction() == null || request.getSellFraction().signum() <= 0
                || request.getSellFraction().compareTo(BigDecimal.ONE) > 0) {
            throw new DcaException("sellFraction doit être compris entre 0 (exclu) et 1 (inclus), reçu : "
                    + request.getSellFraction());
        }
        if (request.getTrailingStopPercent() == null || request.getTrailingStopPercent().signum() < 0) {
            throw new DcaException("trailingStopPercent ne peut pas être négatif");
        }
        if (request.getMultX2() == null || request.getMultX2().signum() < 0
                || request.getMultX1() == null || request.getMultX1().signum() < 0
                || request.getMultX0_5() == null || request.getMultX0_5().signum() < 0
                || request.getMultTriggered() == null || request.getMultTriggered().signum() < 0) {
            throw new DcaException("multX2 / multX1 / multX0_5 / multTriggered ne peuvent pas être négatifs ou nuls (null)");
        }
        if (request.getBuyReentryMode() == null || request.getSellReentryMode() == null) {
            throw new DcaException("buyReentryMode et sellReentryMode sont requis");
        }
        if (request.getFixedDelayDays() <= 0) {
            throw new DcaException("fixedDelayDays doit être strictement positif, reçu : " + request.getFixedDelayDays());
        }
        boolean ordered = request.getPercDown2().compareTo(request.getPercDown1()) < 0
                && request.getPercDown1().compareTo(request.getPercUp1()) < 0
                && request.getPercUp1().compareTo(request.getPercUp2()) < 0
                && request.getPercUp2().compareTo(request.getPercUp3()) < 0;
        if (!ordered) {
            throw new DcaException("Les bornes % doivent être strictement croissantes : percDown2 < percDown1 < "
                    + "percUp1 < percUp2 < percUp3, reçu : " + request.getPercDown2() + " / " + request.getPercDown1()
                    + " / " + request.getPercUp1() + " / " + request.getPercUp2() + " / " + request.getPercUp3());
        }
    }

    /**
     * Résout le multiplicateur d'achat "zone intermédiaire" (pas d'armement, application directe
     * à chaque tick de cadence DCA) depuis la requête. {@code null} pour {@link RainbowZone#EXTREME_BAS}/
     * {@link RainbowZone#EXTREME_HAUT} : ces deux zones ne produisent jamais d'achat direct, elles
     * relèvent exclusivement du mécanisme ARMÉ/DÉCLENCHÉ ({@link RainbowDcaBacktestRequest#getMultTriggered()}).
     */
    private static BigDecimal resolveIntermediateMultiplier(RainbowZone zone, RainbowDcaBacktestRequest request) {
        return switch (zone) {
            case X2 -> request.getMultX2();
            case X1 -> request.getMultX1();
            case X0_5 -> request.getMultX0_5();
            case NO_BUY -> BigDecimal.ZERO;
            case EXTREME_BAS, EXTREME_HAUT -> null;
        };
    }

    /**
     * Condition de déclenchement de l'achat ARMÉ (zone EXTREME_BAS), selon
     * {@link RainbowDcaBacktestRequest#getBuyReentryMode()}. {@code TRAILING_STOP} reproduit
     * exactement la règle V0 d'origine (immuable) : franchissement de percdown2 OU rebond de
     * {@code trailingStopPercent} depuis le plus bas atteint pendant l'armement.
     */
    private static boolean buyTriggered(
            RainbowDcaBacktestRequest request, BigDecimal close, BigDecimal percdown2Level,
            BigDecimal lowestSinceArmed, BigDecimal trailingStopUpFactor, int armedDays
    ) {
        return switch (request.getBuyReentryMode()) {
            case TRAILING_STOP -> close.compareTo(percdown2Level) > 0
                    || close.compareTo(lowestSinceArmed.multiply(trailingStopUpFactor)) > 0;
            case IMMEDIATE -> close.compareTo(percdown2Level) > 0;
            case FIXED_DELAY -> armedDays >= request.getFixedDelayDays();
        };
    }

    /**
     * Condition de déclenchement de la vente ARMÉE (zone EXTREME_HAUT), selon
     * {@link RainbowDcaBacktestRequest#getSellReentryMode()}. {@code IMMEDIATE} reproduit
     * exactement la règle V0 d'origine (immuable) : conserve la double condition percup3/percup2
     * du prompt d'origine (percup2 mathématiquement redondant avec percup3 ici, gardé tel quel par
     * fidélité au prompt — cf. javadoc historique). {@code TRAILING_STOP} est nouveau côté vente
     * (le V0 d'origine n'avait pas de mécanisme de repli symétrique au trailing stop achat).
     */
    private static boolean sellTriggered(
            RainbowDcaBacktestRequest request, BigDecimal close, BigDecimal percup3Level, BigDecimal percup2Level,
            BigDecimal highestSinceArmed, BigDecimal trailingStopDownFactor, int armedDays
    ) {
        return switch (request.getSellReentryMode()) {
            case TRAILING_STOP -> close.compareTo(percup3Level) < 0
                    || close.compareTo(highestSinceArmed.multiply(trailingStopDownFactor)) < 0;
            case IMMEDIATE -> close.compareTo(percup3Level) < 0 || close.compareTo(percup2Level) < 0;
            case FIXED_DELAY -> armedDays >= request.getFixedDelayDays();
        };
    }

    /** {@code sma * (1 + percent/100)} — même formule que {@code RainbowSmaIndicator#percentDecay}. */
    private static BigDecimal level(BigDecimal sma, BigDecimal percent) {
        BigDecimal ratio = percent.divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        return sma.add(sma.multiply(ratio));
    }

    private static BigDecimal computeSma(List<LocalDate> orderedDates, Map<LocalDate, MarketData> byDate, int index, int period) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int j = index - period + 1; j <= index; j++) {
            sum = sum.add(byDate.get(orderedDates.get(j)).getClose());
        }
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    /** Cf. {@code DcaCalculatorService#resolveProviderSymbol} : traduction du symbole nu vers la paire Binance native. */
    private String resolveProviderSymbol(String symbol) {
        return assetProviderRepository.findByAsset_SymbolAndSource(symbol, MarketDataSource.BINANCE)
                .map(AssetProvider::getProviderSymbol)
                .orElse(symbol);
    }

    /** Même pagination par blocs que {@code DcaCalculatorService#fetchCandleRange}. */
    private List<MarketData> fetchH1Range(String symbol, Instant firstHour, Instant lastHour) {
        TreeMap<Instant, MarketData> byHour = new TreeMap<>();
        Instant cursor = floorToHour(firstHour);
        Instant end = floorToHour(lastHour);
        Duration chunkSpan = Duration.ofHours(CHUNK_HOURS - 1L);

        while (!cursor.isAfter(end)) {
            Instant chunkEnd = cursor.plus(chunkSpan);
            if (chunkEnd.isAfter(end)) {
                chunkEnd = end;
            }
            List<MarketData> candles = binanceClient.getCandles(symbol, TimeFrame.H1, cursor, chunkEnd, CHUNK_HOURS);
            for (MarketData candle : candles) {
                byHour.put(floorToHour(candle.getTimestamp()), candle);
            }
            cursor = chunkEnd.plusSeconds(3600);
        }
        return new ArrayList<>(byHour.values());
    }

    private static Instant floorToHour(Instant instant) {
        long epochSeconds = instant.getEpochSecond();
        long flooredEpochSeconds = Math.floorDiv(epochSeconds, 3600L) * 3600L;
        return Instant.ofEpochSecond(flooredEpochSeconds);
    }
}
