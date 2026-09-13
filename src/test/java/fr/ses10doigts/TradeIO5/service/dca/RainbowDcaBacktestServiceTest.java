package fr.ses10doigts.tradeIO5.service.dca;

import fr.ses10doigts.tradeIO5.exceptions.DcaException;
import fr.ses10doigts.tradeIO5.model.dto.dca.DcaResult;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataApiClient;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Cf. RainbowDcaBacktestService — règle exacte des zones/mécanismes ARMÉ/DÉCLENCHÉ confirmée par
 * Clem le 2026-09-12 (mémoire projet tradeio5_dca_rainbow_v0_zone_table_clarification_2026-09-12.md),
 * qui remplace le tableau de docs/prompts/prompt-implementation-dca-rainbow-v0.md.
 * <p>
 * Toutes les journées de test utilisent une seule bougie H1 par jour (00:00 UTC) : seul le
 * {@code close} entre en jeu dans la logique du service, une bougie H1 par jour suffit donc à
 * produire des clôtures D1 (agrégation {@code Bucket}) déterministes et faciles à vérifier à la main.
 * <p>
 * Tests {@code *ReentryMode*} (2026-09-13, cf. docs/etudes/etude-dca-tool-mcp.md §12) : les tests
 * ci-dessus n'utilisent jamais {@code buyReentryMode}/{@code sellReentryMode} explicitement, donc
 * exercent uniquement les défauts ({@code TRAILING_STOP} achat, {@code IMMEDIATE} vente) — c'est-à-
 * -dire le comportement V0 d'origine, inchangé bit à bit par cette réécriture.
 */
@DisplayName("RainbowDcaBacktestService")
@ExtendWith(MockitoExtension.class)
class RainbowDcaBacktestServiceTest {

    private static final String SYMBOL = "BTC";
    private static final String PROVIDER_SYMBOL = "BTC"; // pas de ligne asset_provider en test -> repli sur le symbole brut
    private static final BigDecimal PERC_DOWN2 = new BigDecimal("-20");
    private static final BigDecimal PERC_DOWN1 = new BigDecimal("-10");
    private static final BigDecimal PERC_UP1 = new BigDecimal("10");
    private static final BigDecimal PERC_UP2 = new BigDecimal("20");
    private static final BigDecimal PERC_UP3 = new BigDecimal("30");

    @Mock
    private MarketDataApiClient binanceClient;

    @Mock
    private AssetProviderRepository assetProviderRepository;

    @Mock
    private DcaCalculatorService dcaCalculatorService;

    @BeforeEach
    void setUp() {
        lenient().when(assetProviderRepository.findByAsset_SymbolAndSource(SYMBOL, MarketDataSource.BINANCE))
                .thenReturn(Optional.empty());
        // Pas invoqué par le test "historique insuffisant" (exception levée avant) : lenient pour
        // éviter un UnnecessaryStubbingException (MockitoExtension est en STRICT_STUBS par défaut).
        lenient().when(dcaCalculatorService.calculate(any(), any(), any(), any(), anyInt(), any(), any(), any()))
                .thenReturn(DcaResult.builder().build());
    }

    private RainbowDcaBacktestService serviceWithClock(Instant now) {
        return new RainbowDcaBacktestService(binanceClient, new FixedDomainClock(now), assetProviderRepository, dcaCalculatorService);
    }

    /** Une bougie H1 par jour à 00:00 UTC, de closes[0] à startDate, closes[1] à startDate+1, etc. */
    private static List<MarketData> dailyCandles(LocalDate start, BigDecimal... closes) {
        List<MarketData> list = new ArrayList<>();
        LocalDate date = start;
        for (BigDecimal close : closes) {
            list.add(MarketData.builder()
                    .timeFrame(TimeFrame.H1)
                    .pair(PROVIDER_SYMBOL)
                    .timestamp(date.atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                    .open(close).high(close).low(close).close(close)
                    .volume(BigDecimal.ZERO)
                    .build());
            date = date.plusDays(1);
        }
        return list;
    }

    private static BigDecimal[] warmup(int days, double value) {
        BigDecimal[] closes = new BigDecimal[days];
        for (int i = 0; i < days; i++) {
            closes[i] = BigDecimal.valueOf(value);
        }
        return closes;
    }

    private static BigDecimal[] concat(BigDecimal[] a, double... b) {
        BigDecimal[] result = new BigDecimal[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        for (int i = 0; i < b.length; i++) {
            result[a.length + i] = BigDecimal.valueOf(b[i]);
        }
        return result;
    }

    private void stubCandles(LocalDate warmupStart, BigDecimal[] allCloses, BigDecimal currentPrice) {
        List<MarketData> h1 = dailyCandles(warmupStart, allCloses);
        when(binanceClient.getCandles(eq(PROVIDER_SYMBOL), eq(TimeFrame.H1), notNull(), notNull(), eq(1000)))
                .thenReturn(h1);
    }

    @Test
    @DisplayName("classify() : les 6 zones sont correctement bornées (limites incluses côté bas)")
    void classify_sixZonesCorrectlyBounded() {
        BigDecimal down2 = BigDecimal.valueOf(80);
        BigDecimal down1 = BigDecimal.valueOf(90);
        BigDecimal up1 = BigDecimal.valueOf(110);
        BigDecimal up2 = BigDecimal.valueOf(120);
        BigDecimal up3 = BigDecimal.valueOf(130);

        assertEquals(RainbowZone.EXTREME_BAS, RainbowZone.classify(BigDecimal.valueOf(79), down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.X2, RainbowZone.classify(down2, down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.X2, RainbowZone.classify(BigDecimal.valueOf(85), down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.X1, RainbowZone.classify(down1, down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.X1, RainbowZone.classify(BigDecimal.valueOf(100), down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.X0_5, RainbowZone.classify(up1, down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.NO_BUY, RainbowZone.classify(up2, down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.EXTREME_HAUT, RainbowZone.classify(up3, down2, down1, up1, up2, up3));
        assertEquals(RainbowZone.EXTREME_HAUT, RainbowZone.classify(BigDecimal.valueOf(200), down2, down1, up1, up2, up3));
    }

    @Test
    @DisplayName("Achat zone intermédiaire x1 quand le prix est entre percdown1 et percup1")
    void buy_intermediateZoneX1() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        BigDecimal[] closes = concat(warmup(20, 100), 100); // jour de test : close=100, SMA=100 -> X1
        stubCandles(warmupStart, closes, BigDecimal.valueOf(100));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start)
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(5).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        assertEquals(1, result.getOccurrenceCount());
        RainbowDcaOccurrence occ = result.getOccurrences().getFirst();
        assertEquals(RainbowZone.X1, occ.getZone());
        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.getBuyAction());
        assertEquals(0, occ.getBuyMultiplier().compareTo(BigDecimal.ONE));
        assertEquals(0, occ.getAmountInvested().compareTo(BigDecimal.valueOf(100)));
        assertEquals(1, result.getZoneBuyCount());
    }

    @Test
    @DisplayName("Armé achat sous percdown2 : pas d'achat le jour de l'activation, puis déclenchement par trailing stop " +
            "(rebond de 5% depuis le plus bas) alors que le prix est toujours sous percdown2 -> achat x3, retour à la normale au tick suivant")
    void buy_armedThenTrailingStopTrigger_thenResumesNormalZone() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        // jour1=100 (X1, achat x1) ; jour2=70 (activation ARMÉ, pas d'achat) ; jour3=68 (toujours
        // armé, ni percdown2 ni trailing stop franchis, pas d'achat) ; jour4=75 (déclenchement par
        // trailing stop : 75 > 68*1.05=71.4, alors que percdown2 du jour recalculé est ~76.52,
        // donc PAS déclenché par le franchissement de percdown2 lui-même) ; jour5=100 (reprise zone X1)
        BigDecimal[] closes = concat(warmup(20, 100), 100, 70, 68, 75, 100);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(100));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start.plusDays(4))
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        List<RainbowDcaOccurrence> occ = result.getOccurrences();
        assertEquals(5, occ.size());

        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.get(0).getBuyAction());
        assertEquals(RainbowDcaOccurrence.ArmState.NONE, occ.get(0).getBuyState());

        assertEquals(RainbowZone.EXTREME_BAS, occ.get(1).getZone());
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(1).getBuyAction());
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(1).getBuyState());

        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(2).getBuyAction());
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(2).getBuyState());

        assertEquals(RainbowDcaOccurrence.BuyAction.TRIGGERED, occ.get(3).getBuyAction());
        assertEquals(RainbowDcaOccurrence.ArmState.NONE, occ.get(3).getBuyState());
        assertEquals(0, occ.get(3).getBuyMultiplier().compareTo(BigDecimal.valueOf(3)));
        assertEquals(0, occ.get(3).getAmountInvested().compareTo(BigDecimal.valueOf(300)));

        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.get(4).getBuyAction());
        assertEquals(0, occ.get(4).getBuyMultiplier().compareTo(BigDecimal.ONE));

        assertEquals(2, result.getZoneBuyCount());
        assertEquals(1, result.getBuyTriggeredCount());
        assertEquals(0, result.getTotalInvested().compareTo(BigDecimal.valueOf(500))); // 100+300+100
    }

    @Test
    @DisplayName("Armé vente au-dessus de percup3, déclenchement (retour sous percup3) -> vente de sellFraction de la " +
            "position, puis cooldown bloquant l'achat ce même jour et le jour suivant, achat repris ensuite")
    void sell_armedThenTriggered_cooldownBlocksSubsequentBuys() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        // jour1=100 (X1, achat x1, position=1) ; jour2=140 (activation ARMÉ_VENTE) ;
        // jour3=135 (toujours armée) ; jour4=120 (déclenchement vente, + cooldown démarre :
        // bloque l'achat de zone intermédiaire de ce même jour) ; jour5=125 (cooldown encore actif,
        // achat bloqué) ; jour6=125 (cooldown terminé, achat repris)
        BigDecimal[] closes = concat(warmup(20, 100), 100, 140, 135, 120, 125, 125);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(125));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start.plusDays(5))
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .sellFraction(new BigDecimal("0.5"))
                .cooldownDays(2)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        List<RainbowDcaOccurrence> occ = result.getOccurrences();
        assertEquals(6, occ.size());

        // jour1 : achat x1 -> position = 100/100 = 1
        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.get(0).getBuyAction());
        assertEquals(0, occ.get(0).getPositionAfter().compareTo(BigDecimal.ONE));

        // jour2 : activation vente, pas de vente, pas d'achat (zone EXTREME_HAUT)
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(1).getSellState());
        assertEquals(RainbowDcaOccurrence.SellAction.NONE, occ.get(1).getSellAction());

        // jour3 : toujours armée
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(2).getSellState());
        assertEquals(RainbowDcaOccurrence.SellAction.NONE, occ.get(2).getSellAction());

        // jour4 : déclenchement vente (0.5 * position=1 -> vend 0.5), cooldown démarre et bloque
        // l'achat de zone intermédiaire de ce même jour (close=120 -> zone X0_5)
        RainbowDcaOccurrence jour4 = occ.get(3);
        assertEquals(RainbowDcaOccurrence.ArmState.NONE, jour4.getSellState());
        assertEquals(RainbowDcaOccurrence.SellAction.TRIGGERED, jour4.getSellAction());
        assertEquals(0, jour4.getQuantitySold().compareTo(new BigDecimal("0.5")));
        assertEquals(0, jour4.getPositionAfter().compareTo(new BigDecimal("0.5")));
        assertEquals(RainbowZone.X0_5, jour4.getZone());
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, jour4.getBuyAction(),
                "Le cooldown déclenché par la vente doit bloquer l'achat de ce même jour");
        assertEquals(1, jour4.getCooldownRemaining());

        // jour5 : cooldown encore actif (1 -> 0), achat toujours bloqué malgré la zone X0_5
        RainbowDcaOccurrence jour5 = occ.get(4);
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, jour5.getBuyAction());
        assertEquals(0, jour5.getCooldownRemaining());

        // jour6 : cooldown terminé, achat de zone repris normalement
        RainbowDcaOccurrence jour6 = occ.get(5);
        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, jour6.getBuyAction());
        assertTrue(jour6.getAmountInvested().signum() > 0);

        assertEquals(1, result.getSellTriggeredCount());
        assertEquals(0, result.getTotalSaleProceeds().compareTo(new BigDecimal("60"))); // 0.5 * 120
    }

    @Test
    @DisplayName("Cadence > 1 jour : l'achat de zone intermédiaire ne se déclenche qu'un jour sur cadenceDays")
    void cadence_skipsIntermediateBuysOnNonTickDays() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        // 4 jours de test, tous en zone X1 (close=100, SMA reste 100)
        BigDecimal[] closes = concat(warmup(20, 100), 100, 100, 100, 100);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(100));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start.plusDays(3))
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .cadenceDays(2)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        List<RainbowDcaOccurrence> occ = result.getOccurrences();
        assertEquals(4, occ.size());
        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.get(0).getBuyAction());
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(1).getBuyAction());
        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.get(2).getBuyAction());
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(3).getBuyAction());
        assertEquals(2, result.getZoneBuyCount());
    }

    @Test
    @DisplayName("Historique de warm-up insuffisant pour la SMA -> DcaException explicite")
    void insufficientWarmup_throwsDcaException() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        // seulement 5 jours de warm-up fournis au lieu des 20 requis
        BigDecimal[] closes = concat(warmup(5, 100), 100);
        List<MarketData> h1 = dailyCandles(start.minusDays(5), closes);
        when(binanceClient.getCandles(eq(PROVIDER_SYMBOL), eq(TimeFrame.H1), notNull(), notNull(), eq(1000)))
                .thenReturn(h1);

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start)
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        assertThrows(DcaException.class, () ->
                serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant()).backtest(request));
    }

    @Test
    @DisplayName("endDate égal à aujourd'hui ou dans le futur : borné à la veille (today.minusDays(1))")
    void endDate_whenTodayOrFuture_isCappedToYesterday() {
        LocalDate today = LocalDate.of(2026, 9, 13);
        Instant now = today.atTime(3, 0).atZone(TimeFrame.DEFAULT_ZONE).toInstant();
        LocalDate start = today.minusDays(5);
        LocalDate warmupStart = start.minusDays(20);

        // Closes jusqu'à yesterday (today.minusDays(1))
        BigDecimal[] closes = concat(warmup(20, 100), 100, 100, 100, 100, 100);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(100));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(today) // endDate = today
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(now).backtest(request);

        assertEquals(5, result.getOccurrenceCount()); // 5 jours clos (start à today-1)
        assertEquals(today.minusDays(1), result.getEndDate());
    }

    @Test
    @DisplayName("ReentryMode.IMMEDIATE côté achat : ignore le rebond trailing stop, déclenche seulement au franchissement " +
            "de percdown2 -> réagit plus tard que TRAILING_STOP sur la même séquence de prix")
    void buy_immediateReentry_reactsLaterThanTrailingStop() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        // Même séquence que buy_armedThenTrailingStopTrigger_thenResumesNormalZone : en TRAILING_STOP
        // ça déclenche jour4 (75 > 68*1.05) ; en IMMEDIATE, jour4 ne franchit pas percdown2 (~76.52)
        // -> reste armé, déclenche seulement jour5 (close=100 > percdown2 recalculé ~76.52).
        BigDecimal[] closes = concat(warmup(20, 100), 100, 70, 68, 75, 100);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(100));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start.plusDays(4))
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .buyReentryMode(ReentryMode.IMMEDIATE)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        List<RainbowDcaOccurrence> occ = result.getOccurrences();
        assertEquals(5, occ.size());

        // jour1 : close=100, SMA=100 -> zone X1, achat de zone (identique au comportement TRAILING_STOP)
        assertEquals(RainbowDcaOccurrence.BuyAction.ZONE, occ.get(0).getBuyAction());

        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(1).getBuyState());
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(2).getBuyState());

        // jour4 : ne déclenche PAS en IMMEDIATE (à la différence de TRAILING_STOP)
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(3).getBuyAction());
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(3).getBuyState());

        // jour5 : franchissement de percdown2 -> déclenchement
        assertEquals(RainbowDcaOccurrence.BuyAction.TRIGGERED, occ.get(4).getBuyAction());
        assertEquals(RainbowDcaOccurrence.ArmState.NONE, occ.get(4).getBuyState());
        assertEquals(0, occ.get(4).getBuyMultiplier().compareTo(BigDecimal.valueOf(3)));

        assertEquals(1, result.getZoneBuyCount());
        assertEquals(1, result.getBuyTriggeredCount());
    }

    @Test
    @DisplayName("ReentryMode.FIXED_DELAY côté achat : déclenche après fixedDelayDays jours d'armement, même si le prix " +
            "reste sous percdown2 pendant tout l'armement")
    void buy_fixedDelayReentry_triggersAfterFixedDaysRegardlessOfPrice() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        // jour1=jour2=jour3=50 : reste largement sous percdown2 tout du long (pas de rebond, pas de
        // franchissement) -> seul FIXED_DELAY (2 jours d'armement) peut déclencher ici.
        BigDecimal[] closes = concat(warmup(20, 100), 50, 50, 50);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(50));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start.plusDays(2))
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .buyReentryMode(ReentryMode.FIXED_DELAY)
                .fixedDelayDays(2)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        List<RainbowDcaOccurrence> occ = result.getOccurrences();
        assertEquals(3, occ.size());

        assertEquals(RainbowZone.EXTREME_BAS, occ.get(0).getZone());
        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(0).getBuyState());
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(0).getBuyAction());

        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(1).getBuyState());
        assertEquals(RainbowDcaOccurrence.BuyAction.NONE, occ.get(1).getBuyAction());

        assertEquals(RainbowDcaOccurrence.ArmState.NONE, occ.get(2).getBuyState());
        assertEquals(RainbowDcaOccurrence.BuyAction.TRIGGERED, occ.get(2).getBuyAction());
        assertEquals(0, occ.get(2).getBuyMultiplier().compareTo(BigDecimal.valueOf(3)));
        assertEquals(0, occ.get(2).getAmountInvested().compareTo(BigDecimal.valueOf(300)));
        assertEquals(1, result.getBuyTriggeredCount());
    }

    @Test
    @DisplayName("ReentryMode.FIXED_DELAY côté vente : déclenche après fixedDelayDays jours d'armement, même si le prix " +
            "reste au-dessus de percup3 pendant tout l'armement")
    void sell_fixedDelayReentry_triggersAfterFixedDaysRegardlessOfPrice() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate warmupStart = start.minusDays(20);
        // jour1=100 (X1, achat x1 -> position=1) ; jour2=jour3=jour4=200 : reste largement
        // au-dessus de percup3 tout du long -> seul FIXED_DELAY (2 jours d'armement) déclenche.
        BigDecimal[] closes = concat(warmup(20, 100), 100, 200, 200, 200);
        stubCandles(warmupStart, closes, BigDecimal.valueOf(200));

        RainbowDcaBacktestRequest request = RainbowDcaBacktestRequest.builder()
                .symbol(SYMBOL).startDate(start).endDate(start.plusDays(3))
                .percDown2(PERC_DOWN2).percDown1(PERC_DOWN1).percUp1(PERC_UP1).percUp2(PERC_UP2).percUp3(PERC_UP3)
                .sellReentryMode(ReentryMode.FIXED_DELAY)
                .fixedDelayDays(2)
                .baseAmount(BigDecimal.valueOf(100))
                .build();

        RainbowDcaBacktestResult result = serviceWithClock(start.plusDays(30).atStartOfDay(TimeFrame.DEFAULT_ZONE).toInstant())
                .backtest(request);

        List<RainbowDcaOccurrence> occ = result.getOccurrences();
        assertEquals(4, occ.size());

        assertEquals(0, occ.get(0).getPositionAfter().compareTo(BigDecimal.ONE));

        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(1).getSellState());
        assertEquals(RainbowDcaOccurrence.SellAction.NONE, occ.get(1).getSellAction());

        assertEquals(RainbowDcaOccurrence.ArmState.ARMED, occ.get(2).getSellState());
        assertEquals(RainbowDcaOccurrence.SellAction.NONE, occ.get(2).getSellAction());

        assertEquals(RainbowDcaOccurrence.ArmState.NONE, occ.get(3).getSellState());
        assertEquals(RainbowDcaOccurrence.SellAction.TRIGGERED, occ.get(3).getSellAction());
        assertEquals(0, occ.get(3).getQuantitySold().compareTo(new BigDecimal("0.25")));
        assertEquals(0, occ.get(3).getPositionAfter().compareTo(new BigDecimal("0.75")));
        assertEquals(1, result.getSellTriggeredCount());
    }
}
