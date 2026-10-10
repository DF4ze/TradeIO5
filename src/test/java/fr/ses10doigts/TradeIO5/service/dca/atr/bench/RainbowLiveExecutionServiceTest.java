package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLivePresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService.PassSummary;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({RainbowLivePresetConfigResolver.class, RainbowLivePresetEventService.class, RainbowLivePresetService.class, RainbowAssetStrategyService.class, RainbowLiveRunService.class, RainbowLiveExecutionService.class,
        RainbowTrendLiveService.class, MockPortfolioSource.class, RainbowAthService.class,
        RainbowLiveExecutionServiceTest.ClockConfig.class})
@DisplayName("Bench grandeur nature Rainbow : service d'exécution (passes 23:55 / 00:05)")
class RainbowLiveExecutionServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    /** 30 bougies : index 0..29, le dernier jour (index 29) = 2026-09-30. */
    private static final int N = 30;
    private static final LocalDate LAST_DAY = START.plusDays(N - 1);
    private static final Instant ASOF_2355 = Instant.parse("2026-09-30T23:55:00Z");
    private static final Instant ASOF_0005 = Instant.parse("2026-10-01T00:05:00Z");

    /** Zone X1 sur série plate (sma 100, atr 2) : un BUY_ZONE de {@code base} par bougie. IMMEDIATE, pas de cooldown. */
    private static final RainbowAtrTuning FLAT = new RainbowAtrTuning(3, 3, 2.0, 1.0, 1.0, 2.0, 3.0,
            ReentryMode.IMMEDIATE, ReentryMode.IMMEDIATE, 3, 5, 0, 1, 1.0, true, false, false);
    /** Bornes serrées : un creux net => EXTREME_BAS, un pic net => EXTREME_HAUT. */
    private static final RainbowAtrTuning TIGHT = new RainbowAtrTuning(3, 3, 0.5, 0.25, 0.5, 1.0, 2.0,
            ReentryMode.IMMEDIATE, ReentryMode.IMMEDIATE, 3, 5, 0, 1, 1.0, true, false, false);
    private static final RainbowAtrTuning SELLT = new RainbowAtrTuning(3, 3, 2.0, 1.0, 0.2, 0.3, 0.5,
            ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY, 3, 5, 0, 1, 1.0, true, false, false);
    /** Cooldown après vente : pas d'achat sur la bougie du MOON_STOP. */
    private static final RainbowAtrTuning MOON_COOLDOWN = new RainbowAtrTuning(3, 3, 2.0, 1.0, 3.0, 4.0, 5.0,
            ReentryMode.IMMEDIATE, ReentryMode.IMMEDIATE, 3, 5, 5, 1, 1.0, true, true, false);
    /** Sans cooldown : achat possible sur la bougie du MOON_STOP. */
    private static final RainbowAtrTuning MOON_NO_COOLDOWN = new RainbowAtrTuning(3, 3, 2.0, 1.0, 3.0, 4.0, 5.0,
            ReentryMode.IMMEDIATE, ReentryMode.IMMEDIATE, 3, 5, 0, 1, 1.0, true, false, false);

    private static RainbowAtrGlobals globals(boolean moon, double base) {
        return new RainbowAtrGlobals(false, 60, 30, 0.5, 2.0, 2.0, 0.5, moon, 50, false, 15, 100, 2.0, 1.0, 0.5, 3.0, base);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(ASOF_2355);
        }
    }

    @MockBean private MarketDatasetEngine datasetEngine;
    /** Aucun binding : tous les presets restent en simulation (liveSlots vide). */
    @MockBean private RealPortfolioSource realPortfolioSource;
    @MockBean private fr.ses10doigts.tradeIO5.service.calibration.BinanceDailyCandleFetcher binanceDailyCandleFetcher;
    @Autowired private RainbowLiveExecutionService service;
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowAssetStrategyService strategyService;
    @Autowired private RainbowLivePresetRepository presetRepository;
    @Autowired private RainbowLiveMockWalletRepository walletRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private UserRepository userRepository;

    /** Bougies servies par actif ; absence de clé => exception du provider. Filtrées sur {@code endTime}. */
    private final Map<String, List<MarketData>> candlesByAsset = new HashMap<>();
    private User alice;

    @BeforeEach
    void setUp() {
        alice = user("alice", true);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        when(datasetEngine.getDatasetForAsset(anyString(), eq(TimeFrame.D1), anyInt(), any(Instant.class)))
                .thenAnswer(inv -> {
                    String asset = inv.getArgument(0);
                    Instant endTime = inv.getArgument(3);
                    List<MarketData> all = candlesByAsset.get(asset);
                    if (all == null) {
                        throw new IllegalStateException("provider KO pour " + asset);
                    }
                    List<MarketData> visible = all.stream().filter(c -> !c.getTimestamp().isAfter(endTime)).toList();
                    return MarketDataset.builder().pair(asset).timeFrame(TimeFrame.D1).marketDatas(visible)
                            .size(visible.size()).isComplete(true).build();
                });
    }

    private User user(String name, boolean enabled) {
        return userRepository.save(User.builder().username(name).email(name + "@example.com").password("x").enabled(enabled).build());
    }

    private static List<MarketData> candles(String asset, double... closes) {
        List<MarketData> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(MarketData.builder().timeFrame(TimeFrame.D1)
                    .timestamp(START.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant()).pair(asset)
                    .open(BigDecimal.valueOf(c)).high(BigDecimal.valueOf(c + 1)).low(BigDecimal.valueOf(c - 1))
                    .close(BigDecimal.valueOf(c)).volume(BigDecimal.ONE).build());
        }
        return out;
    }

    private static double[] flat(int n, double price) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, price);
        return a;
    }

    private static double[] concat(double[] head, double... tail) {
        double[] a = java.util.Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, a, head.length, tail.length);
        return a;
    }

    private RainbowLivePreset preset(User u, String asset, String name, RainbowAtrTuning t, RainbowAtrGlobals g) {
        return presetService.create(u, new RainbowLivePresetService.CreateRequest(asset, name, true, 6, 1000, t, g));
    }

    /** Un preset FLAT par actif autorisé => aucun preset par défaut créé par le job. */
    private List<RainbowLivePreset> presetsAllAssets(User u) {
        return RainbowLiveDefaultPresets.ASSETS.stream().map(a -> preset(u, a, "t", FLAT, globals(false, 1.0))).toList();
    }

    private RainbowLiveMockWallet wallet(RainbowLivePreset p) {
        return walletRepository.findByPreset(p).orElseThrow();
    }

    private RainbowLiveRun run(RainbowLivePreset p, LocalDate day) {
        return runRepository.findByPresetAndDay(p, day).orElseThrow();
    }

    private void serve(String asset, double... closes) {
        candlesByAsset.put(asset, candles(asset, closes));
    }

    /** Dernier jour : événements du moteur sur la série (vérifie que le scénario produit bien ce qu'on teste). */
    private static List<String> lastDayEvents(double[] closes, RainbowAtrTuning t, RainbowAtrGlobals g) {
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles("X", closes));
        int end = closes.length - 1;
        return RainbowAtrEngine.simulate(ds, g, new RainbowAtrTuning[]{t}, null,
                        RainbowAtrDataset.warmup(t.smaPeriod(), t.atrPeriod()), end, true)
                .events().stream().filter(e -> e.index() == end).map(RainbowAtrResult.Event::type).toList();
    }

    // ---------------------------------------------------------------- jour UTC

    @Test
    @DisplayName("dayFor : 23:55 => jour de asOf ; 00:05 => veille (UTC)")
    void dayFor() {
        assertEquals(LAST_DAY, RainbowLiveExecutionService.dayFor(RainbowLivePass.T2355, ASOF_2355));
        assertEquals(LAST_DAY, RainbowLiveExecutionService.dayFor(RainbowLivePass.T0005, ASOF_0005));
    }

    @Test
    @DisplayName("Constante D1 : lookBack × 24 H1 ≤ capacité du bucket (sinon troncature silencieuse)")
    void d1LookbackFitsBucket() {
        assertTrue(RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES * 24 <= fr.ses10doigts.tradeIO5.service.market.dataset.Bucket.BASE_MAX_ITEMS);
        assertTrue(RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES >= 3300, "doit couvrir BTC/ETH depuis 2017-08");
    }

    // ---------------------------------------------------------------- parité

    @Test
    @DisplayName("Parité : le bloc == valeurs de simulate() appelé directement (bornes, zone, facteurs, états)")
    void parityWithEngine() {
        double[] closes = concat(flat(25, 100), 103, 99, 104, 98, 101);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, closes);
        }
        RainbowLivePreset p = presetsAllAssets(alice).getFirst();

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles("BTC", closes));
        RainbowAtrResult r = RainbowAtrEngine.simulate(ds, globals(false, 1.0), FLAT, 2, N - 1);
        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        double sma = r.lastSma();
        double atr = r.lastAtr();
        assertEquals(closes[N - 1], b.getClose(), 1e-12);
        assertEquals(sma, b.getSma(), 1e-12);
        assertEquals(atr, b.getAtr(), 1e-12);
        assertEquals(sma - atr * 2.0, b.getBoundDown2(), 1e-12);
        assertEquals(sma - atr * 1.0, b.getBoundDown1(), 1e-12);
        assertEquals(sma + atr * 1.0, b.getBoundUp1(), 1e-12);
        assertEquals(sma + atr * 2.0, b.getBoundUp2(), 1e-12);
        assertEquals(sma + atr * 3.0, b.getBoundUp3(), 1e-12);
        assertEquals(r.lastZone(), b.getZone());
        assertEquals(r.lastAthDistance(), b.getAthDistance(), 1e-12);
        assertEquals(r.lastBuyFactor(), b.getBuyFactor(), 1e-12);
        assertEquals(r.lastSellFactor(), b.getSellFactor(), 1e-12);
        assertEquals(r.moonModeLast(), b.getMoonMode());
        assertEquals(r.buyArmed(), b.getBuyArmed());
        assertEquals(r.sellArmed(), b.getSellArmed());
        assertEquals(r.buyLocked(), b.getBuyLocked());
        assertEquals(r.cooldownRemaining(), b.getCooldownRemaining());
        assertEquals(r.moonReserveQty(), b.getMoonReserveQty(), 1e-12);
        assertEquals(closes[N - 1], b.getActionPrice(), 1e-12);
    }

    // ---------------------------------------------------------------- mapping des actions

    @Test
    @DisplayName("BUY_ZONE => BUY du montant moteur ; quantité = montant / close")
    void mapsBuyZone() {
        double[] closes = flat(N, 100);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, closes);
        }
        RainbowLivePreset p = presetsAllAssets(alice).getFirst();
        assertEquals(List.of("BUY_ZONE"), lastDayEvents(closes, FLAT, globals(false, 1.0)));

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(1.0, b.getActionAmountUsdc(), 1e-12);
        assertEquals(0.01, b.getActionQuantity(), 1e-12);
        assertEquals(999.0, b.getCashAfter(), 1e-12);
        assertEquals(0.01, b.getPositionAfter(), 1e-12);
        assertEquals(999.0, wallet(p).getCashUsd(), 1e-12);
    }

    @Test
    @DisplayName("BUY_TRIGGERED => BUY (montant × multTriggered)")
    void mapsBuyTriggered() {
        double[] closes = concat(flat(N - 2, 100), 70, 100);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, closes);
        }
        RainbowLivePreset p = preset(alice, "BTC", "trig", TIGHT, globals(false, 1.0));
        serve("BTC", closes);
        assertEquals(List.of("BUY_TRIGGERED"), lastDayEvents(closes, TIGHT, globals(false, 1.0)));

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(3.0, b.getActionAmountUsdc(), 1e-12);
    }

    @Test
    @DisplayName("SELL => SELL de la quantité moteur ; plafonnée à la position du wallet mock")
    void mapsSellAndCapsToPosition() {
        double[] closes = concat(flat(N - 2, 100), 150, 150);
        assertEquals(List.of("SELL"), lastDayEvents(closes, SELLT, globals(false, 1.0)));
        RainbowLivePreset p = preset(alice, "BTC", "sell", SELLT, globals(false, 1.0));
        serve("BTC", closes);
        RainbowLiveMockWallet w = wallet(p);
        w.setPositionQuantity(0.005);
        walletRepository.save(w);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        assertEquals(RainbowLiveAction.SELL, b.getActionType());
        assertEquals(0.005, b.getActionQuantity(), 1e-12);
        assertEquals(0.005 * 150, b.getActionAmountUsdc(), 1e-9);
        assertEquals(0.0, wallet(p).getPositionQuantity(), 1e-12);
        assertEquals(1000 + 0.005 * 150, wallet(p).getCashUsd(), 1e-9);
    }

    @Test
    @DisplayName("SELL avec position mock nulle => NONE (wallet jamais négatif, pas d'exception)")
    void sellWithNoPositionIsNone() {
        double[] closes = concat(flat(N - 2, 100), 150, 150);
        RainbowLivePreset p = preset(alice, "BTC", "sell", SELLT, globals(false, 1.0));
        serve("BTC", closes);

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(0, s.errors());
        assertEquals(RainbowLiveAction.NONE, run(p, LAST_DAY).getPass2355().getActionType());
        assertEquals(0.0, wallet(p).getPositionQuantity(), 1e-12);
    }

    @Test
    @DisplayName("MOON_STOP => SELL (cooldown après vente : pas d'achat concurrent)")
    void mapsMoonStop() {
        double[] closes = concat(flat(N - 2, 100), 110, 90);
        assertEquals(List.of("MOON_STOP"), lastDayEvents(closes, MOON_COOLDOWN, globals(true, 1.0)));
        RainbowLivePreset p = preset(alice, "BTC", "moon", MOON_COOLDOWN, globals(true, 1.0));
        serve("BTC", closes);
        RainbowLiveMockWallet w = wallet(p);
        w.setPositionQuantity(1.0);
        walletRepository.save(w);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        assertEquals(RainbowLiveAction.SELL, b.getActionType());
        assertTrue(b.getActionQuantity() > 0 && b.getActionQuantity() <= 1.0);
    }

    @Test
    @DisplayName("Achat ET vente sur la même bougie : la vente l'emporte")
    void sellWinsOverBuyOnSameBar() {
        double[] closes = concat(flat(N - 2, 100), 110, 90);
        List<String> events = lastDayEvents(closes, MOON_NO_COOLDOWN, globals(true, 1.0));
        assertTrue(events.contains("MOON_STOP") && events.contains("BUY_ZONE"), "scénario : " + events);
        RainbowLivePreset p = preset(alice, "BTC", "both", MOON_NO_COOLDOWN, globals(true, 1.0));
        serve("BTC", closes);
        RainbowLiveMockWallet w = wallet(p);
        w.setPositionQuantity(1.0);
        walletRepository.save(w);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(RainbowLiveAction.SELL, run(p, LAST_DAY).getPass2355().getActionType());
    }

    @Test
    @DisplayName("Aucun event le dernier jour => NONE")
    void noEventIsNone() {
        double[] closes = concat(flat(N - 2, 100), 70, 70);   // arme l'achat, reste sous la borne
        RainbowLivePreset p = preset(alice, "BTC", "none", TIGHT, globals(false, 1.0));
        serve("BTC", closes);
        assertTrue(lastDayEvents(closes, TIGHT, globals(false, 1.0)).isEmpty());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        assertEquals(RainbowLiveAction.NONE, b.getActionType());
        assertNull(b.getActionAmountUsdc());
    }

    // ---------------------------------------------------------------- plafonds cash

    @Test
    @DisplayName("Achat partiel si cash < montant ; NONE si cash = 0 ; aucune IllegalStateException")
    void capsBuyToCash() {
        double[] closes = flat(N, 100);
        RainbowLivePreset p = preset(alice, "BTC", "cash", FLAT, globals(false, 5.0));
        serve("BTC", closes);
        RainbowLiveMockWallet w = wallet(p);
        w.setCashUsd(2.0);
        walletRepository.save(w);

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(0, s.errors());
        RainbowLivePassBlock b = run(p, LAST_DAY).getPass2355();
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(2.0, b.getActionAmountUsdc(), 1e-12);
        assertEquals(0.02, b.getActionQuantity(), 1e-12);
        assertEquals(0.0, wallet(p).getCashUsd(), 1e-12);

        // lendemain : cash 0 => NONE
        serve("BTC", concat(closes, 100));
        service.runPass(RainbowLivePass.T2355, ASOF_2355.plusSeconds(86_400));
        RainbowLivePassBlock next = run(p, LAST_DAY.plusDays(1)).getPass2355();
        assertEquals(RainbowLiveAction.NONE, next.getActionType());
        assertEquals(0.0, wallet(p).getCashUsd(), 1e-12);
    }

    // ---------------------------------------------------------------- double passe / idempotence

    @Test
    @DisplayName("Double passe : blocs côte à côte, wallet modifié une seule fois, bougie du nouveau jour ignorée, rejeu idempotent")
    void doublePass() {
        double[] c2355 = flat(N, 100);
        RainbowLivePreset p = presetsAllAssets(alice).getFirst();
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, c2355);
        }
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        double cashAfter2355 = wallet(p).getCashUsd();
        assertEquals(999.0, cashAfter2355, 1e-12);

        // 00:05 : vraie clôture du jour (101) + 1re bougie du nouveau jour (60) qui doit être ignorée
        double[] c0005 = concat(flat(N - 1, 100), 101, 60);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, c0005);
        }
        PassSummary s = service.runPass(RainbowLivePass.T0005, ASOF_0005);

        assertEquals(LAST_DAY, s.day());
        RainbowLiveRun r = run(p, LAST_DAY);
        assertNotNull(r.getPass2355());
        assertNotNull(r.getPass0005());
        assertEquals(100.0, r.getPass2355().getClose(), 1e-12);
        assertEquals(101.0, r.getPass0005().getClose(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, r.getPass0005().getActionType());
        assertNull(r.getPass0005().getCashAfter());
        assertTrue(r.deltaActionDiffers(), "quantité 1/100 vs 1/101");
        assertEquals(cashAfter2355, wallet(p).getCashUsd(), 1e-12);
        assertEquals(1, runRepository.countByPreset(p), "pas de run pour le nouveau jour");

        // rejeu des deux passes : même état
        service.runPass(RainbowLivePass.T0005, ASOF_0005);
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        assertEquals(cashAfter2355, wallet(p).getCashUsd(), 1e-12);
        assertEquals(1, runRepository.countByPreset(p));
        assertEquals(RainbowLiveAction.BUY, run(p, LAST_DAY).getPass2355().getActionType());
    }

    @Test
    @DisplayName("00:05 sans 23:55 toléré : bloc 00:05 seul, wallet intact")
    void pass0005AloneIsTolerated() {
        double[] closes = concat(flat(N, 100), 60);
        RainbowLivePreset p = preset(alice, "BTC", "t", FLAT, globals(false, 1.0));
        serve("BTC", closes);

        service.runPass(RainbowLivePass.T0005, ASOF_0005);

        RainbowLiveRun r = run(p, LAST_DAY);
        assertNull(r.getPass2355());
        assertNotNull(r.getPass0005());
        assertEquals(1000.0, wallet(p).getCashUsd(), 1e-12);
        assertFalse(r.deltaActionDiffers());
    }

    // ---------------------------------------------------------------- robustesse

    @Test
    @DisplayName("Bougie du jour absente (PAXG) => actif sauté, BTC/ETH traités, pas de rattrapage")
    void missingCandleSkipsOnlyThatAsset() {
        List<RainbowLivePreset> ps = presetsAllAssets(alice);
        serve("BTC", flat(N, 100));
        serve("ETH", flat(N, 100));
        serve("PAXG", flat(N - 2, 100));   // s'arrête 2 jours avant

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(2, s.processed());
        assertEquals(1, s.skipped());
        assertEquals(0, s.errors());
        assertEquals(1, runRepository.countByPreset(ps.get(0)));
        assertEquals(0, runRepository.countByPreset(ps.get(2)), "ni passe du jour, ni rattrapage des jours manquants");
    }

    @Test
    @DisplayName("Provider en erreur pour un actif => erreurs comptées, les autres actifs traités")
    void providerFailureIsIsolated() {
        List<RainbowLivePreset> ps = presetsAllAssets(alice);
        serve("BTC", flat(N, 100));
        candlesByAsset.remove("ETH");     // ETH absent => exception du provider

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(2, s.processed());
        assertEquals(1, s.errors());
        assertEquals(0, runRepository.countByPreset(ps.get(1)));
    }

    @Test
    @DisplayName("Erreur d'un preset (wallet mock absent) n'arrête pas les autres")
    void presetErrorIsIsolated() {
        RainbowLivePreset a = preset(alice, "BTC", "a", FLAT, globals(false, 1.0));
        RainbowLivePreset b = preset(alice, "BTC", "b", FLAT, globals(false, 1.0));
        presetsExcept(alice, "BTC");
        serve("BTC", flat(N, 100));
        walletRepository.delete(wallet(a));
        walletRepository.flush();

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(1, s.errors());
        assertEquals(0, runRepository.countByPreset(a));
        assertEquals(1, runRepository.countByPreset(b));
    }

    private void presetsExcept(User u, String assetToSkip) {
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            if (!asset.equals(assetToSkip)) {
                preset(u, asset, "t", FLAT, globals(false, 1.0));
                serve(asset, flat(N, 100));
            }
        }
    }

    @Test
    @DisplayName("Dataset D1 chargé UNE fois par actif quel que soit le nombre de presets/users")
    void datasetLoadedOncePerAsset() {
        User bob = user("bob", true);
        preset(alice, "BTC", "a", FLAT, globals(false, 1.0));
        preset(alice, "BTC", "b", FLAT, globals(false, 2.0));
        preset(bob, "BTC", "a", FLAT, globals(false, 1.0));
        presetsExcept(alice, "BTC");
        presetsExcept(bob, "BTC");
        serve("BTC", flat(N, 100));

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(7, s.processed());
        verify(datasetEngine, times(1)).getDatasetForAsset(eq("BTC"), eq(TimeFrame.D1), anyInt(), any(Instant.class));
        verify(datasetEngine, times(1)).getDatasetForAsset(eq("ETH"), eq(TimeFrame.D1), anyInt(), any(Instant.class));
    }

    @Test
    @DisplayName("Dataset demandé avec le lookBack D1 mutualisé et asOf (horloge injectée)")
    void datasetRequestedWithSharedLookback() {
        presetsAllAssets(alice);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        verify(datasetEngine).getDatasetForAsset("BTC", TimeFrame.D1, RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES, ASOF_2355);
    }

    // ---------------------------------------------------------------- presets / users

    @Test
    @DisplayName("Presets qui suivent les stratégies pour les users actifs (inactifs donc non joués) ; preset disabled ignoré ; user désactivé ignoré")
    void presetsAndUsers() {
        strategyService.ensureStrategies();
        User disabledUser = user("carol", false);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        RainbowLivePreset off = preset(alice, "BTC", "off", FLAT, globals(false, 1.0));
        off.setEnabled(false);
        presetRepository.save(off);

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(2, presetService.list(alice, "BTC").size(), "BTC : le preset désactivé + le preset de stratégie");
        assertEquals(1, presetService.list(alice, "ETH").size(), "preset de stratégie ETH créé par le job");
        assertEquals(1, presetService.list(alice, "PAXG").size(), "preset de stratégie PAXG créé par le job");
        assertEquals(0, presetService.list(disabledUser).size(), "aucun preset pour un user désactivé");
        assertEquals(0, runRepository.countByPreset(off));
        assertEquals(0, s.processed(), "presets de stratégie inactifs : rien n'est joué");
    }

    @Test
    @DisplayName("2 users => runs et wallets séparés (clé user non codée en dur)")
    void twoUsersSeparateRuns() {
        User bob = user("bob", true);
        RainbowLivePreset pa = presetsAllAssets(alice).getFirst();
        RainbowLivePreset pb = presetsAllAssets(bob).getFirst();
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }

        PassSummary s = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(6, s.processed());
        assertNotEquals(run(pa, LAST_DAY).getId(), run(pb, LAST_DAY).getId());
        assertEquals(alice.getId(), run(pa, LAST_DAY).getUser().getId());
        assertEquals(bob.getId(), run(pb, LAST_DAY).getUser().getId());
        assertEquals(999.0, wallet(pa).getCashUsd(), 1e-12);
        assertEquals(999.0, wallet(pb).getCashUsd(), 1e-12);
    }

    @Test
    @DisplayName("Config modifiée entre deux jours : le run du jour utilise la config courante, les runs passés restent intacts")
    void configChangeAppliesFromNextRun() {
        RainbowLivePreset p = presetsAllAssets(alice).getFirst();
        serve("BTC", flat(N, 100));
        serve("ETH", flat(N, 100));
        serve("PAXG", flat(N, 100));
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        String hashDay1 = run(p, LAST_DAY).getConfigHash();
        assertEquals(1.0, run(p, LAST_DAY).getConfig().toGlobals().baseAmount(), 1e-12);

        presetService.update(alice, p.getId(), new RainbowLivePresetService.UpdateRequest("t", true, 6, FLAT, globals(false, 2.0)));
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N + 1, 100));
        }
        service.runPass(RainbowLivePass.T2355, ASOF_2355.plusSeconds(86_400));

        assertEquals(hashDay1, run(p, LAST_DAY).getConfigHash());
        assertEquals(1.0, run(p, LAST_DAY).getConfig().toGlobals().baseAmount(), 1e-12);
        assertNotEquals(hashDay1, run(p, LAST_DAY.plusDays(1)).getConfigHash());
        assertEquals(2.0, run(p, LAST_DAY.plusDays(1)).getPass2355().getActionAmountUsdc(), 1e-12);
    }

    @Test
    @DisplayName("Dataset trop court (< warmup+2) => preset sauté, pas d'exception")
    void tooShortDatasetSkipped() {
        RainbowLivePreset p = preset(alice, "BTC", "short", FLAT, globals(false, 1.0));
        presetsExcept(alice, "BTC");
        candlesByAsset.put("BTC", candles("BTC", 100, 100));
        // jour demandé = index 1 => endIdx 1 < warmup(2)+1
        PassSummary s = service.runPass(RainbowLivePass.T2355, Instant.parse("2026-09-02T23:55:00Z"));

        assertEquals(0, runRepository.countByPreset(p));
        assertTrue(s.skipped() >= 1);
        assertEquals(0, s.errors());
    }
}
