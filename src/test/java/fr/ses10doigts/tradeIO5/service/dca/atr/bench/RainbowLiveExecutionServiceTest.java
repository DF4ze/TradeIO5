package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
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
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({RainbowLivePresetConfigResolver.class, RainbowLivePresetEventService.class, RainbowLivePresetService.class, RainbowAssetStrategyService.class, RainbowLiveRunService.class, RainbowLiveBindingRunService.class, RainbowLiveExecutionService.class,
        RainbowTrendLiveService.class, RainbowAthService.class,
        RainbowLiveExecutionServiceTest.ClockConfig.class})
@DisplayName("Live Rainbow : service d'exécution (passe quotidienne 23:55 UTC)")
class RainbowLiveExecutionServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    /** 30 bougies : index 0..29, le dernier jour (index 29) = 2026-09-30. */
    private static final int N = 30;
    private static final LocalDate LAST_DAY = START.plusDays(N - 1);
    private static final Instant ASOF_2355 = Instant.parse("2026-09-30T23:55:00Z");

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
    /** Aucun binding : seul le signal du preset est calculé (liveSlots vide). */
    @MockBean private RealPortfolioSource realPortfolioSource;
    @MockBean private fr.ses10doigts.tradeIO5.service.calibration.BinanceDailyCandleFetcher binanceDailyCandleFetcher;
    @Autowired private RainbowLiveExecutionService service;
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowAssetStrategyService strategyService;
    @Autowired private RainbowLivePresetRepository presetRepository;
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
        when(datasetEngine.getDailyDatasetForAsset(anyString(), anyInt(), any(Instant.class), any()))
                .thenAnswer(inv -> {
                    String asset = inv.getArgument(0);
                    Instant endTime = inv.getArgument(2);
                    List<MarketData> all = candlesByAsset.get(asset);
                    if (all == null) {
                        throw new IllegalStateException("provider KO pour " + asset);
                    }
                    List<MarketData> visible = all.stream().filter(c -> !c.getTimestamp().isAfter(endTime)).toList();
                    return new DailyDataset(MarketDataset.builder().pair(asset).timeFrame(TimeFrame.D1).marketDatas(visible)
                            .size(visible.size()).isComplete(true).build(), MarketDataSource.BINANCE, "TEST", false);
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
        return presetService.create(u, new RainbowLivePresetService.CreateRequest(asset, name, true, 6, t, g));
    }

    /** Un preset FLAT par actif autorisé => aucun preset par défaut créé par le job. */
    private List<RainbowLivePreset> presetsAllAssets(User u) {
        return RainbowLiveDefaultPresets.ASSETS.stream().map(a -> preset(u, a, "t", FLAT, globals(false, 1.0))).toList();
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
    @DisplayName("dayFor : jour UTC de asOf")
    void dayFor() {
        assertEquals(LAST_DAY, RainbowLiveExecutionService.dayFor(ASOF_2355));
        assertEquals(LAST_DAY.plusDays(1), RainbowLiveExecutionService.dayFor(ASOF_2355.plusSeconds(600)));
    }

    @Test
    @DisplayName("Constante D1 : 720 j = limite native Kraken (OHLC D1), assez pour l'ATH de BTC/ETH/PAXG")
    void d1LookbackIsKrakenNativeWindow() {
        assertEquals(720, RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES);
        assertTrue(RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES
                <= fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits.KRAKEN_OHLC_MAX);
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

        service.runPass(ASOF_2355);

        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles("BTC", closes));
        RainbowAtrResult r = RainbowAtrEngine.simulate(ds, globals(false, 1.0), FLAT, 2, N - 1);
        RainbowLivePassBlock b = run(p, LAST_DAY).getSignal();
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

        service.runPass(ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getSignal();
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(1.0, b.getActionAmountUsdc(), 1e-12);
        assertEquals(0.01, b.getActionQuantity(), 1e-12);
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

        service.runPass(ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getSignal();
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(3.0, b.getActionAmountUsdc(), 1e-12);
    }

    @Test
    @DisplayName("SELL => SELL de la quantité moteur (signal non plafonné : le plafond réel est appliqué par lien)")
    void mapsSell() {
        double[] closes = concat(flat(N - 2, 100), 150, 150);
        assertEquals(List.of("SELL"), lastDayEvents(closes, SELLT, globals(false, 1.0)));
        RainbowLivePreset p = preset(alice, "BTC", "sell", SELLT, globals(false, 1.0));
        serve("BTC", closes);

        service.runPass(ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getSignal();
        assertEquals(RainbowLiveAction.SELL, b.getActionType());
        assertTrue(b.getActionQuantity() > 0);
        assertEquals(b.getActionQuantity() * 150, b.getActionAmountUsdc(), 1e-9);
    }

    @Test
    @DisplayName("MOON_STOP => SELL (cooldown après vente : pas d'achat concurrent)")
    void mapsMoonStop() {
        double[] closes = concat(flat(N - 2, 100), 110, 90);
        assertEquals(List.of("MOON_STOP"), lastDayEvents(closes, MOON_COOLDOWN, globals(true, 1.0)));
        RainbowLivePreset p = preset(alice, "BTC", "moon", MOON_COOLDOWN, globals(true, 1.0));
        serve("BTC", closes);

        service.runPass(ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getSignal();
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

        service.runPass(ASOF_2355);

        assertEquals(RainbowLiveAction.SELL, run(p, LAST_DAY).getSignal().getActionType());
    }

    @Test
    @DisplayName("Aucun event le dernier jour => NONE")
    void noEventIsNone() {
        double[] closes = concat(flat(N - 2, 100), 70, 70);   // arme l'achat, reste sous la borne
        RainbowLivePreset p = preset(alice, "BTC", "none", TIGHT, globals(false, 1.0));
        serve("BTC", closes);
        assertTrue(lastDayEvents(closes, TIGHT, globals(false, 1.0)).isEmpty());

        service.runPass(ASOF_2355);

        RainbowLivePassBlock b = run(p, LAST_DAY).getSignal();
        assertEquals(RainbowLiveAction.NONE, b.getActionType());
        assertNull(b.getActionAmountUsdc());
    }

    // ---------------------------------------------------------------- passe quotidienne / rafraîchissement J-1

    @Test
    @DisplayName("Passe du lendemain : le run de la veille est rafraîchi sur la vraie clôture, action figée ; rejeu idempotent")
    void nextDayRefreshesPreviousRun() {
        RainbowLivePreset p = presetsAllAssets(alice).getFirst();
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        service.runPass(ASOF_2355);
        RainbowLivePassBlock first = run(p, LAST_DAY).getSignal();
        assertEquals(100.0, first.getClose(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, first.getActionType());
        assertEquals(0.01, first.getActionQuantity(), 1e-12);

        // lendemain : la bougie J a sa clôture définitive (101) et la bougie J+1 est en cours
        double[] next = concat(flat(N - 1, 100), 101, 100);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, next);
        }
        PassSummary s = service.runPass(ASOF_2355.plusSeconds(86_400));

        assertEquals(LAST_DAY.plusDays(1), s.day());
        RainbowLivePassBlock refreshed = run(p, LAST_DAY).getSignal();
        assertEquals(101.0, refreshed.getClose(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, refreshed.getActionType());
        assertEquals(1.0, refreshed.getActionAmountUsdc(), 1e-12);
        assertEquals(0.01, refreshed.getActionQuantity(), 1e-12, "la quantité décidée à 23:55 reste figée");
        assertEquals(2, runRepository.countByPreset(p));
        assertEquals(100.0, run(p, LAST_DAY.plusDays(1)).getSignal().getClose(), 1e-12);

        // rejeu : mêmes lignes
        service.runPass(ASOF_2355.plusSeconds(86_400));
        assertEquals(2, runRepository.countByPreset(p));
        assertEquals(101.0, run(p, LAST_DAY).getSignal().getClose(), 1e-12);
    }

    // ---------------------------------------------------------------- robustesse

    @Test
    @DisplayName("Bougie du jour absente (PAXG) => actif sauté, BTC/ETH traités, pas de rattrapage")
    void missingCandleSkipsOnlyThatAsset() {
        List<RainbowLivePreset> ps = presetsAllAssets(alice);
        serve("BTC", flat(N, 100));
        serve("ETH", flat(N, 100));
        serve("PAXG", flat(N - 2, 100));   // s'arrête 2 jours avant

        PassSummary s = service.runPass(ASOF_2355);

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

        PassSummary s = service.runPass(ASOF_2355);

        assertEquals(2, s.processed());
        assertEquals(1, s.errors());
        assertEquals(0, runRepository.countByPreset(ps.get(1)));
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

        PassSummary s = service.runPass(ASOF_2355);

        assertEquals(7, s.processed());
        verify(datasetEngine, times(1)).getDailyDatasetForAsset(eq("BTC"), anyInt(), any(Instant.class), isNull());
        verify(datasetEngine, times(1)).getDailyDatasetForAsset(eq("ETH"), anyInt(), any(Instant.class), isNull());
    }

    @Test
    @DisplayName("Dataset demandé avec le lookBack D1 mutualisé et asOf (horloge injectée)")
    void datasetRequestedWithSharedLookback() {
        presetsAllAssets(alice);
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        service.runPass(ASOF_2355);
        verify(datasetEngine).getDailyDatasetForAsset("BTC", RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES, ASOF_2355, null);
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

        PassSummary s = service.runPass(ASOF_2355);

        assertEquals(2, presetService.list(alice, "BTC").size(), "BTC : le preset désactivé + le preset de stratégie");
        assertEquals(1, presetService.list(alice, "ETH").size(), "preset de stratégie ETH créé par le job");
        assertEquals(1, presetService.list(alice, "PAXG").size(), "preset de stratégie PAXG créé par le job");
        assertEquals(0, presetService.list(disabledUser).size(), "aucun preset pour un user désactivé");
        assertEquals(0, runRepository.countByPreset(off));
        assertEquals(0, s.processed(), "presets de stratégie inactifs : rien n'est joué");
    }

    @Test
    @DisplayName("2 users => runs séparés (clé user non codée en dur)")
    void twoUsersSeparateRuns() {
        User bob = user("bob", true);
        RainbowLivePreset pa = presetsAllAssets(alice).getFirst();
        RainbowLivePreset pb = presetsAllAssets(bob).getFirst();
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }

        PassSummary s = service.runPass(ASOF_2355);

        assertEquals(6, s.processed());
        assertNotEquals(run(pa, LAST_DAY).getId(), run(pb, LAST_DAY).getId());
        assertEquals(alice.getId(), run(pa, LAST_DAY).getUser().getId());
        assertEquals(bob.getId(), run(pb, LAST_DAY).getUser().getId());
    }

    @Test
    @DisplayName("Config modifiée entre deux jours : le run du jour utilise la config courante, l'action de la veille reste figée")
    void configChangeAppliesFromNextRun() {
        RainbowLivePreset p = presetsAllAssets(alice).getFirst();
        serve("BTC", flat(N, 100));
        serve("ETH", flat(N, 100));
        serve("PAXG", flat(N, 100));
        service.runPass(ASOF_2355);
        String hashDay1 = run(p, LAST_DAY).getConfigHash();
        assertEquals(1.0, run(p, LAST_DAY).getConfig().toGlobals().baseAmount(), 1e-12);

        presetService.update(alice, p.getId(), new RainbowLivePresetService.UpdateRequest("t", true, 6, FLAT, globals(false, 2.0)));
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N + 1, 100));
        }
        service.runPass(ASOF_2355.plusSeconds(86_400));

        assertNotEquals(hashDay1, run(p, LAST_DAY.plusDays(1)).getConfigHash());
        assertEquals(1.0, run(p, LAST_DAY).getSignal().getActionAmountUsdc(), 1e-12, "l'action de la veille reste figée");
        assertEquals(2.0, run(p, LAST_DAY.plusDays(1)).getSignal().getActionAmountUsdc(), 1e-12);
    }

    @Test
    @DisplayName("Dataset trop court (< warmup+2) => preset sauté, pas d'exception")
    void tooShortDatasetSkipped() {
        RainbowLivePreset p = preset(alice, "BTC", "short", FLAT, globals(false, 1.0));
        presetsExcept(alice, "BTC");
        candlesByAsset.put("BTC", candles("BTC", 100, 100));
        // jour demandé = index 1 => endIdx 1 < warmup(2)+1
        PassSummary s = service.runPass(Instant.parse("2026-09-02T23:55:00Z"));

        assertEquals(0, runRepository.countByPreset(p));
        assertTrue(s.skipped() >= 1);
        assertEquals(0, s.errors());
    }
}
