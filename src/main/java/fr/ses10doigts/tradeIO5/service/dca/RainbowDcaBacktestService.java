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
 * comportement V0 d'origine (TRAILING_STOP achat, IMMEDIATE vente).
 * <p>
 * Priorité vente/cooldown (2026-09-29, règles explicites de Clem) : {@code FIXED_DELAY} reste un
 * nombre de jours fixe compté depuis l'armement (formule de {@code buyTriggered}/{@code
 * sellTriggered} inchangée). Tant qu'une vente est ARMÉE, tout achat est bloqué (armement,
 * déclenchement ET achat de zone intermédiaire), pour éviter les yoyo et les ventes multiples. Le
 * cooldown ({@code cooldownDays}, déclenché par une vente exécutée) bloque à la fois l'armement
 * d'une nouvelle vente, le déclenchement d'une vente déjà armée et tout achat — aucun des trois ne
 * progresse tant que {@code cooldownRemaining > 0}. Ceci est une régression de comportement
 * volontaire par rapport à la V0 d'origine (qui ne bloquait que le montant acheté pendant le
 * cooldown, jamais l'armement/le déclenchement, et ne gatait pas du tout l'armement de vente) :
 * {@code RainbowDcaBacktestServiceTest} peut nécessiter une mise à jour de ses attentes.
 * <p>
 * Valorisation du bloc comparatif "DCA fixe" (2026-09-13, cf. étude §14) : {@code currentPrice}
 * ci-dessus est valorisé au prix de clôture d'{@code endDate} (la fenêtre backtestée), donc
 * {@code fixedDcaComparison} DOIT être valorisé au même instant — sinon on compare un PnL borné
 * dans le temps à un PnL {@code DcaCalculatorService} par défaut au prix BINANCE live
 * ({@code clock.now()}), ce qui peut produire un delta aberrant dès qu'{@code endDate} n'est pas
 * "aujourd'hui" (les 3 fenêtres du bench manuel, par exemple, toutes closes il y a des années).
 * D'où l'appel à l'overload {@code calculate(..., valuationInstant)} avec {@code fetchTo} (borne
 * haute H1 déjà calculée ci-dessus pour {@code endDate}).
 * <p>
 * {@code realizedGain}/{@code potentialGain} (2026-09-13, cf. étude §15, demande explicite de
 * Clem) : {@code costBasis} suit, par la méthode du coût moyen pondéré (WAC), le coût de revient
 * de la position actuellement détenue — augmenté de {@code amountInvested} à chaque achat exécuté,
 * réduit proportionnellement à la part de la position vendue à chaque vente déclenchée (le coût
 * moyen unitaire du reste de la position ne change pas lors d'une vente partielle). La plus-value
 * réalisée d'une vente est {@code produitDeVente - partDuCoûtDeRevientVendue} ; la plus-value
 * potentielle finale est {@code currentValue - costBasis} (coût de revient de ce qui reste en
 * position à {@code endDate}). Cf. {@link RainbowDcaBacktestResult} pour le détail des invariants.
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

        int warmupDays = request.getBoundsMode() == BoundsMode.ATR
                ? Math.max(request.getSmaPeriod(), request.getAtrPeriod() + 1)
                : request.getSmaPeriod();
        LocalDate warmupStart = startDate.minusDays(warmupDays);
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
        if (startIndex < warmupDays) {
            throw new DcaException("Historique insuffisant pour calculer une SMA(" + request.getSmaPeriod()
                    + ")" + (request.getBoundsMode() == BoundsMode.ATR ? "/ATR(" + request.getAtrPeriod() + ")" : "")
                    + " causale au " + startDate + " : seulement " + startIndex + " jour(s) de warm-up disponible(s) "
                    + "(bougies D1 dispo à partir de " + (orderedDates.isEmpty() ? "aucune" : orderedDates.getFirst()) + ").");
        }
        if (startIndex >= orderedDates.size()) {
            throw new DcaException("Aucune bougie D1 disponible à partir de " + startDate + " pour " + request.getSymbol() + ".");
        }

        BigDecimal[] atrSeries = request.getBoundsMode() == BoundsMode.ATR
                ? computeAtrSeries(orderedDates, byDate, request.getAtrPeriod())
                : null;

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

        // Coût de revient de la position détenue (méthode du coût moyen pondéré, WAC) — cf.
        // javadoc de la classe et étude §15. Indépendant de totalInvested : celui-ci ne diminue
        // jamais (somme brute de tout ce qui a été investi), costBasis lui diminue à chaque vente
        // (part du coût de revient "consommée" par la vente).
        BigDecimal costBasis = BigDecimal.ZERO;
        BigDecimal totalRealizedGain = BigDecimal.ZERO;

        BigDecimal trailingStopUpFactor = BigDecimal.ONE.add(
                request.getTrailingStopBuyPercent().divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP));
        BigDecimal trailingStopDownFactor = BigDecimal.ONE.subtract(
                request.getTrailingStopSellPercent().divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP));

        for (int i = startIndex; i < orderedDates.size(); i++) {
            LocalDate date = orderedDates.get(i);
            if (date.isAfter(endDate)) {
                break;
            }

            BigDecimal close = byDate.get(date).getClose();
            BigDecimal sma = computeSma(orderedDates, byDate, i, request.getSmaPeriod());

            BigDecimal percdown2Level;
            BigDecimal percdown1Level;
            BigDecimal percup1Level;
            BigDecimal percup2Level;
            BigDecimal percup3Level;
            if (request.getBoundsMode() == BoundsMode.ATR) {
                BigDecimal atr = atrSeries[i];
                percdown2Level = sma.subtract(atr.multiply(request.getAtrMultDown2()));
                percdown1Level = sma.subtract(atr.multiply(request.getAtrMultDown1()));
                percup1Level = sma.add(atr.multiply(request.getAtrMultUp1()));
                percup2Level = sma.add(atr.multiply(request.getAtrMultUp2()));
                percup3Level = sma.add(atr.multiply(request.getAtrMultUp3()));
            } else {
                percdown2Level = level(sma, request.getPercDown2());
                percdown1Level = level(sma, request.getPercDown1());
                percup1Level = level(sma, request.getPercUp1());
                percup2Level = level(sma, request.getPercUp2());
                percup3Level = level(sma, request.getPercUp3());
            }

            RainbowZone zone = RainbowZone.classify(close, percdown2Level, percdown1Level, percup1Level, percup2Level, percup3Level);

            // ---- VENTE (traitée avant l'achat : une vente déclenchée aujourd'hui active le
            // cooldown qui bloque l'achat ainsi que tout nouvel armement/déclenchement de vente,
            // y compris ce même jour — cf. javadoc plus haut, règle Clem 2026-09-29) ----
            RainbowDcaOccurrence.SellAction sellAction = RainbowDcaOccurrence.SellAction.NONE;
            BigDecimal sellQuantity = BigDecimal.ZERO;
            BigDecimal saleProceeds = BigDecimal.ZERO;

            if (sellState == RainbowDcaOccurrence.ArmState.ARMED) {
                sellArmedDays++;
                boolean triggered = sellTriggered(request, close, percup3Level, percup2Level, highestSinceArmed,
                        trailingStopDownFactor, sellArmedDays);
                if (triggered) {
                    BigDecimal positionBeforeSell = position;
                    sellQuantity = position.multiply(request.getSellFraction());
                    if (sellQuantity.signum() > 0) {
                        saleProceeds = sellQuantity.multiply(close);
                        position = position.subtract(sellQuantity);
                        totalQuantitySold = totalQuantitySold.add(sellQuantity);
                        totalSaleProceeds = totalSaleProceeds.add(saleProceeds);
                        sellAction = RainbowDcaOccurrence.SellAction.TRIGGERED;
                        sellTriggeredCount++;

                        // Plus-value réelle (WAC) : la part du coût de revient consommée par cette
                        // vente est proportionnelle à la part de la position vendue.
                        BigDecimal costBasisSoldPortion = costBasis.multiply(sellQuantity)
                                .divide(positionBeforeSell, SCALE, RoundingMode.HALF_UP);
                        totalRealizedGain = totalRealizedGain.add(saleProceeds.subtract(costBasisSoldPortion));
                        costBasis = costBasis.subtract(costBasisSoldPortion);
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
            } else if (zone == RainbowZone.EXTREME_HAUT && cooldownRemaining == 0) {
                // Armement de vente bloqué pendant le cooldown (cf. javadoc de la classe,
                // règle Clem 2026-09-29) : aucun nouvel armement tant que cooldownRemaining > 0.
                sellState = RainbowDcaOccurrence.ArmState.ARMED; // activation, pas de vente ce jour-là
                highestSinceArmed = close;
                sellArmedDays = 0;
            }

            // ---- ACHAT ----
            // Bloqué en totalité (armement ET déclenchement ET achat de zone) tant qu'une vente
            // est armée (évite les yoyo et les ventes multiples) ou que le cooldown est actif
            // (cf. javadoc de la classe, règle Clem 2026-09-29). Avant cette règle, seul le
            // montant investi était annulé pendant le cooldown ; désormais l'état/l'armement
            // n'évolue plus du tout dans ces deux cas.
            RainbowDcaOccurrence.BuyAction buyAction = RainbowDcaOccurrence.BuyAction.NONE;
            BigDecimal buyMultiplier = null;
            BigDecimal amountInvested = BigDecimal.ZERO;
            BigDecimal quantityBought = BigDecimal.ZERO;
            boolean cadenceTick = (cadenceCounter % request.getCadenceDays()) == 0;

            if (sellState != RainbowDcaOccurrence.ArmState.ARMED && cooldownRemaining == 0) {
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
            }

            if (buyMultiplier != null) {
                amountInvested = request.getBaseAmount().multiply(buyMultiplier);
                quantityBought = amountInvested.divide(close, SCALE, RoundingMode.HALF_UP);
                position = position.add(quantityBought);
                totalInvested = totalInvested.add(amountInvested);
                totalQuantityBought = totalQuantityBought.add(quantityBought);
                costBasis = costBasis.add(amountInvested);
                if (buyAction == RainbowDcaOccurrence.BuyAction.TRIGGERED) {
                    buyTriggeredCount++;
                } else {
                    zoneBuyCount++;
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

        // Plus-value réalisée/potentielle (2026-09-13, cf. étude §15) : décomposition de pnl entre
        // gain déjà encaissé (via les ventes) et gain encore en position (pas garanti tant que non
        // vendu). costBasis restant = coût de revient de la position à endDate (WAC).
        BigDecimal potentialGain = currentValue.subtract(costBasis);
        BigDecimal realizedGainPercent = totalInvested.signum() > 0
                ? totalRealizedGain.divide(totalInvested, SCALE, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;
        BigDecimal potentialGainPercent = totalInvested.signum() > 0
                ? potentialGain.divide(totalInvested, SCALE, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        // Valorisé au même instant (fetchTo, borne haute H1 d'endDate) que currentPrice ci-dessus —
        // cf. javadoc de la classe et étude §14 : sans ça, on compare un PnL borné dans le temps à
        // un PnL DcaCalculatorService valorisé par défaut au prix BINANCE live (clock.now()).
        DcaResult fixedDcaComparison = dcaCalculatorService.calculate(
                request.getSymbol(), startDate, endDate, TimeFrame.D1, 0,
                request.getBaseAmount(), null, MarketDataSource.BINANCE, fetchTo);

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
                .realizedGain(totalRealizedGain)
                .realizedGainPercent(realizedGainPercent)
                .potentialGain(potentialGain)
                .potentialGainPercent(potentialGainPercent)
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
        if (request.getTrailingStopBuyPercent() == null || request.getTrailingStopBuyPercent().signum() < 0) {
            throw new DcaException("trailingStopBuyPercent ne peut pas être négatif");
        }
        if (request.getTrailingStopSellPercent() == null || request.getTrailingStopSellPercent().signum() < 0) {
            throw new DcaException("trailingStopSellPercent ne peut pas être négatif");
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
        if (request.getBoundsMode() == BoundsMode.ATR) {
            if (request.getAtrPeriod() <= 0) {
                throw new DcaException("atrPeriod doit être strictement positif, reçu : " + request.getAtrPeriod());
            }
            if (request.getAtrMultDown2() == null || request.getAtrMultDown1() == null
                    || request.getAtrMultUp1() == null || request.getAtrMultUp2() == null || request.getAtrMultUp3() == null) {
                throw new DcaException("atrMultDown2/Down1/Up1/Up2/Up3 sont requis en mode ATR");
            }
            // Monotonie des bornes ATR (2026-09-30, demande explicite de Clem) : down2 >= down1 >= 0
            // et 0 <= up1 <= up2 <= up3 (egalite permise pour "supprimer" une zone en la reduisant a
            // largeur nulle, ex. up3=up2 pour ne plus jamais atteindre NO_BUY) — mais jamais
            // d'inversion, sous peine de zones qui se chevauchent (cf. RainbowZone#classify, qui
            // suppose cet ordre et ne le revalide pas lui-meme : un achat "zone" peut sinon se
            // produire au-dela du seuil EXTREME_HAUT, cf. etudes/etude-dca-tool-mcp.md §28).
            boolean atrOrdered = request.getAtrMultDown1().signum() >= 0
                    && request.getAtrMultDown2().compareTo(request.getAtrMultDown1()) >= 0
                    && request.getAtrMultUp1().signum() >= 0
                    && request.getAtrMultUp1().compareTo(request.getAtrMultUp2()) <= 0
                    && request.getAtrMultUp2().compareTo(request.getAtrMultUp3()) <= 0;
            if (!atrOrdered) {
                throw new DcaException("Les multiplicateurs ATR doivent respecter atrMultDown2 >= atrMultDown1 >= 0 "
                        + "et 0 <= atrMultUp1 <= atrMultUp2 <= atrMultUp3 (egalite permise, jamais d'inversion), reçu : "
                        + "down2=" + request.getAtrMultDown2() + " / down1=" + request.getAtrMultDown1()
                        + " / up1=" + request.getAtrMultUp1() + " / up2=" + request.getAtrMultUp2()
                        + " / up3=" + request.getAtrMultUp3());
            }
        }
        if (request.getFixedDelayDays() <= 0) {
            throw new DcaException("fixedDelayDays doit être strictement positif, reçu : " + request.getFixedDelayDays());
        }
        // Egalite permise (2026-09-30, meme regle que cote ATR ci-dessus) pour pouvoir "supprimer"
        // une zone intermediaire, mais jamais d'inversion.
        boolean ordered = request.getPercDown2().compareTo(request.getPercDown1()) <= 0
                && request.getPercDown1().compareTo(request.getPercUp1()) <= 0
                && request.getPercUp1().compareTo(request.getPercUp2()) <= 0
                && request.getPercUp2().compareTo(request.getPercUp3()) <= 0;
        if (!ordered) {
            throw new DcaException("Les bornes % doivent être croissantes (egalite permise) : percDown2 <= percDown1 <= "
                    + "percUp1 <= percUp2 <= percUp3, reçu : " + request.getPercDown2() + " / " + request.getPercDown1()
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
     * {@code trailingStopBuyPercent} depuis le plus bas atteint pendant l'armement.
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

    /**
     * ATR (Wilder) causal, calcule une seule fois pour toute la serie D1 disponible (pas seulement
     * la fenetre backtestee, warmupStart en amont fournit l'historique necessaire) : atrSeries[k]
     * correspond a orderedDates.get(k), null tant que period+1 bougies ne sont pas disponibles.
     * Meme formule de lissage que {@code AtrIndicator} (moyenne simple des period premiers TR puis
     * lissage de Wilder), mais en une seule passe sur toute la serie plutot qu'un recalcul par jour.
     */
    private static BigDecimal[] computeAtrSeries(List<LocalDate> orderedDates, Map<LocalDate, MarketData> byDate, int period) {
        int n = orderedDates.size();
        BigDecimal[] atr = new BigDecimal[n];
        if (n < period + 1) {
            return atr;
        }
        BigDecimal bdPeriod = BigDecimal.valueOf(period);
        BigDecimal trSum = BigDecimal.ZERO;
        for (int k = 1; k <= period; k++) {
            trSum = trSum.add(trueRange(byDate.get(orderedDates.get(k)), byDate.get(orderedDates.get(k - 1))));
        }
        BigDecimal current = trSum.divide(bdPeriod, SCALE, RoundingMode.HALF_UP);
        atr[period] = current;
        for (int k = period + 1; k < n; k++) {
            BigDecimal tr = trueRange(byDate.get(orderedDates.get(k)), byDate.get(orderedDates.get(k - 1)));
            current = current.multiply(bdPeriod.subtract(BigDecimal.ONE)).add(tr).divide(bdPeriod, SCALE, RoundingMode.HALF_UP);
            atr[k] = current;
        }
        return atr;
    }

    private static BigDecimal trueRange(MarketData curr, MarketData prev) {
        BigDecimal highLow = curr.getHigh().subtract(curr.getLow());
        BigDecimal highPrevClose = curr.getHigh().subtract(prev.getClose()).abs();
        BigDecimal lowPrevClose = curr.getLow().subtract(prev.getClose()).abs();
        return highLow.max(highPrevClose).max(lowPrevClose);
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
