package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Preset live : dimensionnement sur le portefeuille réel (port simulé, aucun exchange). Série plate => un BUY_ZONE de
 * {@code base} (50 USDC) par jour ; série finale en hausse => SELL.
 */
@DataJpaTest
@Import({RainbowLivePresetConfigResolver.class, RainbowLivePresetEventService.class, RainbowLivePresetService.class, RainbowAssetStrategyService.class, RainbowLiveRunService.class,
        RainbowLiveExecutionService.class, RainbowLiveBindingRunService.class, RainbowTrendLiveService.class, RainbowAthService.class,
        MockPortfolioSource.class, RainbowLiveRealCashTest.ClockConfig.class})
@DisplayName("Bench Rainbow : preset live sur vrai cash (ledger, blocage, indisponibilité, rejeu, 00:05)")
class RainbowLiveRealCashTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final int N = 30;
    private static final LocalDate LAST_DAY = START.plusDays(N - 1);
    private static final Instant ASOF_2355 = Instant.parse("2026-09-30T23:55:00Z");
    private static final Instant ASOF_0005 = Instant.parse("2026-10-01T00:05:00Z");
    private static final double BASE = 50.0;

    private static final RainbowAtrTuning FLAT = new RainbowAtrTuning(3, 3, 2.0, 1.0, 1.0, 2.0, 3.0,
            ReentryMode.IMMEDIATE, ReentryMode.IMMEDIATE, 3, 5, 0, 1, 1.0, true, false, false);
    private static final RainbowAtrTuning SELLT = new RainbowAtrTuning(3, 3, 2.0, 1.0, 0.2, 0.3, 0.5,
            ReentryMode.IMMEDIATE, ReentryMode.FIXED_DELAY, 3, 5, 0, 1, 1.0, true, false, false);

    private static RainbowAtrGlobals globals() {
        return new RainbowAtrGlobals(false, 60, 30, 0.5, 2.0, 2.0, 0.5, false, 50, false, 15, 100, 2.0, 1.0, 0.5, 3.0, BASE);
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(ASOF_2355);
        }
    }

    @MockBean private MarketDatasetEngine datasetEngine;
    @MockBean private fr.ses10doigts.tradeIO5.service.calibration.BinanceDailyCandleFetcher binanceDailyCandleFetcher;
    @MockBean private RealPortfolioSource realSource;
    @Autowired private RainbowLiveExecutionService service;
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveMockWalletRepository walletRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private RainbowLiveBindingRepository bindingRepository;
    @Autowired private RainbowLiveBindingRunRepository bindingRunRepository;
    @Autowired private WalletRepository realWalletRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private jakarta.persistence.EntityManager em;

    private final Map<String, List<MarketData>> candlesByAsset = new HashMap<>();
    /** Liens (bindings) et lecture servie par le port simulé (par défaut, ou par wallet). */
    private final List<LiveSlot> slots = new ArrayList<>();
    private final Map<Long, PortfolioReading> readingByWallet = new HashMap<>();
    /** Sources dont le provider est « indisponible » : le moteur sert alors BINANCE, marqué substitué. */
    private final java.util.Set<MarketDataSource> substitutedSources = new java.util.HashSet<>();
    private PortfolioReading reading;
    private User alice;
    private Wallet wallet;
    private Long walletId;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        wallet = newWallet("OKX");
        walletId = wallet.getId();
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of());
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        when(datasetEngine.getDailyDatasetForAsset(anyString(), anyInt(), any(Instant.class), any()))
                .thenAnswer(inv -> {
                    String asset = inv.getArgument(0);
                    Instant endTime = inv.getArgument(2);
                    MarketDataSource wanted = inv.getArgument(3);
                    List<MarketData> visible = candlesByAsset.get(asset).stream()
                            .filter(c -> !c.getTimestamp().isAfter(endTime)).toList();
                    boolean substituted = wanted != null && substitutedSources.contains(wanted);
                    MarketDataSource used = wanted == null ? MarketDataSource.BINANCE
                            : substituted ? MarketDataSource.BINANCE : wanted;
                    return new DailyDataset(MarketDataset.builder().pair(asset).timeFrame(TimeFrame.D1).marketDatas(visible)
                            .size(visible.size()).isComplete(true).build(), used, "TEST", substituted);
                });
        when(realSource.liveSlots(any(User.class))).thenAnswer(inv -> List.copyOf(slots));
        when(realSource.read(any(RainbowLiveBinding.class))).thenAnswer(inv -> {
            RainbowLiveBinding b = inv.getArgument(0);
            return readingByWallet.getOrDefault(b.getWallet().getId(), reading);
        });
        when(realSource.checkFreshness(any(PortfolioReading.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Wallet newWallet(String name) {
        return realWalletRepository.save(Wallet.builder().name(name).source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(alice).build());
    }

    private PortfolioReading reading(PortfolioStatus status, double cash, Map<String, Double> positions) {
        return reading(walletId, status, cash, positions);
    }

    private static PortfolioReading reading(Long wallet, PortfolioStatus status, double cash, Map<String, Double> positions) {
        return status == PortfolioStatus.UNAVAILABLE ? PortfolioReading.unavailable(wallet, ASOF_2355)
                : new PortfolioReading(cash, positions, ASOF_2355, status, wallet);
    }

    private void serve(String asset, double... closes) {
        List<MarketData> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out.add(MarketData.builder().timeFrame(TimeFrame.D1)
                    .timestamp(START.plusDays(i).atStartOfDay(ZoneOffset.UTC).toInstant()).pair(asset)
                    .open(BigDecimal.valueOf(c)).high(BigDecimal.valueOf(c + 1)).low(BigDecimal.valueOf(c - 1))
                    .close(BigDecimal.valueOf(c)).volume(BigDecimal.ONE).build());
        }
        candlesByAsset.put(asset, out);
    }

    private static double[] flat(int n, double price) {
        double[] a = new double[n];
        Arrays.fill(a, price);
        return a;
    }

    private static double[] concat(double[] head, double... tail) {
        double[] a = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, a, head.length, tail.length);
        return a;
    }

    private RainbowLivePreset preset(String asset, RainbowAtrTuning t) {
        return presetService.create(alice, new RainbowLivePresetService.CreateRequest(asset, "t", true, 6, 1000, t, globals()));
    }

    /** Un preset par actif (aucun preset par défaut ajouté par la passe). */
    private Map<String, RainbowLivePreset> presetsAllAssets() {
        Map<String, RainbowLivePreset> out = new HashMap<>();
        RainbowLiveDefaultPresets.ASSETS.forEach(a -> out.put(a, preset(a, FLAT)));
        return out;
    }

    private RainbowLiveBinding bind(RainbowLivePreset p, int priority, double bagPercent) {
        return bind(p, priority, bagPercent, wallet);
    }

    /** Lien (binding) du preset sur {@code w} : persisté (FK du run de lien) et déclaré au port simulé. */
    private RainbowLiveBinding bind(RainbowLivePreset p, int priority, double bagPercent, Wallet w) {
        return bind(p, priority, bagPercent, w, null);
    }

    private RainbowLiveBinding bind(RainbowLivePreset p, int priority, double bagPercent, Wallet w, MarketDataSource source) {
        RainbowLiveBinding binding = bindingRepository.save(RainbowLiveBinding.builder().user(alice)
                .assetSymbol(p.getAssetSymbol()).preset(p).wallet(w).bagPercent(bagPercent).priority(priority).build());
        slots.add(new LiveSlot(binding.getId(), p.getId(), p.getAssetSymbol(), w.getId(), priority, bagPercent, source));
        return binding;
    }

    /** Bloc de simulation (wallet mock) du preset, partagé par tous ses liens. */
    private RainbowLivePassBlock block2355(RainbowLivePreset p) {
        return runRepository.findByPresetAndDay(p, LAST_DAY).orElseThrow().getPass2355();
    }

    /** Snapshot live du lien. */
    private RainbowLiveLiveBlock live2355(RainbowLiveBinding b) {
        return bindingRunRepository.findByBindingAndDay(b, LAST_DAY).orElseThrow().getPass2355();
    }

    private RainbowLiveLiveBlock live0005(RainbowLiveBinding b) {
        return bindingRunRepository.findByBindingAndDay(b, LAST_DAY).orElseThrow().getPass0005();
    }

    @Test
    @DisplayName("Signal par lien : chaque lien lit les candles de SA source ; le preset suit la source du lien prioritaire")
    void signalPerLinkOnLinkSource() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        Wallet okxWallet = newWallet("okx-2");
        RainbowLiveBinding kraken = bind(btc, 0, 50, wallet, MarketDataSource.KRAKEN);
        RainbowLiveBinding okx = bind(btc, 1, 50, okxWallet, MarketDataSource.OKX);
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of());
        readingByWallet.put(okxWallet.getId(), reading(okxWallet.getId(), PortfolioStatus.OK, 1000.0, Map.of()));

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        verify(datasetEngine, times(1)).getDailyDatasetForAsset(eq("BTC"),
                eq(RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES), eq(ASOF_2355), eq(MarketDataSource.KRAKEN));
        verify(datasetEngine, times(1)).getDailyDatasetForAsset(eq("BTC"),
                eq(RainbowLiveDefaultPresets.D1_LOOKBACK_CANDLES), eq(ASOF_2355), eq(MarketDataSource.OKX));
        verify(datasetEngine, never()).getDailyDatasetForAsset(eq("BTC"), anyInt(), any(Instant.class), isNull());
        assertEquals(MarketDataSource.KRAKEN, live2355(kraken).getCandleSource());
        assertEquals(false, live2355(kraken).getCandleSourceSubstituted());
        assertEquals(MarketDataSource.OKX, live2355(okx).getCandleSource());
        assertEquals(false, live2355(okx).getCandleSourceSubstituted());
    }

    @Test
    @DisplayName("Source du lien indisponible : fallback bruyant, snapshot du lien marqué substitué")
    void substitutedSourceIsFlaggedOnSnapshot() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100, wallet, MarketDataSource.KRAKEN);
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of());
        substitutedSources.add(MarketDataSource.KRAKEN);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLiveLiveBlock b = live2355(link);
        assertEquals(MarketDataSource.BINANCE, b.getCandleSource());
        assertEquals(true, b.getCandleSourceSubstituted());
    }

    @Test
    @DisplayName("Lien sans exchange de marché : défauts asset_provider (source null), jamais marqué substitué")
    void linkWithoutExchangeUsesDefaults() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        verify(datasetEngine, times(1)).getDailyDatasetForAsset(eq("BTC"), anyInt(), any(Instant.class), isNull());
        assertEquals(false, live2355(link).getCandleSourceSubstituted());
    }

    @Test
    @DisplayName("Achat <= cash : BUY live réservé, snapshot complet ; la chaîne mock reste inchangée")
    void buyWithinCash() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of("BTC", 0.4));

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLiveLiveBlock b = live2355(link);
        assertEquals(PortfolioStatus.OK, b.getStatus());
        assertEquals(walletId, b.getWalletId());
        assertEquals(ASOF_2355, b.getFetchedAt());
        assertEquals(1000.0, b.getCashUsd(), 1e-12);
        assertEquals(0.4, b.getPositionQty(), 1e-12);
        assertEquals(0.4, b.getTradableQty(), 1e-12);
        assertEquals(0.0, b.getCashReserved(), 1e-12);
        assertEquals(LiveBlockReason.NONE, b.getBlockReason());
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(BASE, b.getActionAmountUsdc(), 1e-12);
        assertEquals(BASE / 100, b.getActionQuantity(), 1e-12);
        // chaîne mock indépendante : le wallet mock a bien reçu l'achat fictif
        assertEquals(RainbowLiveAction.BUY, block2355(btc).getActionType());
        assertEquals(1000.0 - BASE, walletRepository.findByPreset(btc).orElseThrow().getCashUsd(), 1e-12);
    }

    @Test
    @DisplayName("Achat > cash : BLOCKED + INSUFFICIENT_CASH (tout-ou-rien), montant voulu conservé")
    void buyBeyondCashIsBlocked() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        reading = reading(PortfolioStatus.OK, 10.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLiveLiveBlock b = live2355(link);
        assertEquals(RainbowLiveAction.BLOCKED, b.getActionType());
        assertEquals(LiveBlockReason.INSUFFICIENT_CASH, b.getBlockReason());
        assertEquals(BASE, b.getActionAmountUsdc(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, block2355(btc).getActionType());
    }

    @Test
    @DisplayName("Cash commun : les liens passent par priority croissante, chaque achat réserve, le suivant voit le reste")
    void ledgerFollowsPriority() {
        Map<String, RainbowLivePreset> p = presetsAllAssets();
        RainbowLiveBinding btcLink = bind(p.get("BTC"), 2, 100);
        RainbowLiveBinding ethLink = bind(p.get("ETH"), 1, 100);
        RainbowLiveBinding paxgLink = bind(p.get("PAXG"), 3, 100);
        reading = reading(PortfolioStatus.OK, 120.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        // ETH (1) puis BTC (2) puis PAXG (3) : 50 + 50 réservés, il reste 20 < 50 pour PAXG
        RainbowLiveLiveBlock eth = live2355(ethLink);
        RainbowLiveLiveBlock btc = live2355(btcLink);
        RainbowLiveLiveBlock paxg = live2355(paxgLink);
        assertEquals(RainbowLiveAction.BUY, eth.getActionType());
        assertEquals(0.0, eth.getCashReserved(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, btc.getActionType());
        assertEquals(BASE, btc.getCashReserved(), 1e-12);
        assertEquals(RainbowLiveAction.BLOCKED, paxg.getActionType());
        assertEquals(2 * BASE, paxg.getCashReserved(), 1e-12);
    }

    @Test
    @DisplayName("Portefeuille indisponible : aucune action live (UNAVAILABLE), la chaîne mock continue")
    void unavailableMeansNoAction() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        reading = reading(PortfolioStatus.UNAVAILABLE, 0, Map.of());

        var summary = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(0, summary.errors());
        RainbowLiveLiveBlock b = live2355(link);
        assertEquals(PortfolioStatus.UNAVAILABLE, b.getStatus());
        assertEquals(RainbowLiveAction.NONE, b.getActionType());
        assertEquals(LiveBlockReason.UNAVAILABLE, b.getBlockReason());
        assertNull(b.getCashUsd());
        assertEquals(RainbowLiveAction.BUY, block2355(btc).getActionType());
    }

    @Test
    @DisplayName("Lecture STALE : même traitement qu'indisponible, statut STALE tracé")
    void staleMeansNoAction() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        reading = reading(PortfolioStatus.STALE, 1000.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLiveLiveBlock b = live2355(link);
        assertEquals(PortfolioStatus.STALE, b.getStatus());
        assertEquals(RainbowLiveAction.NONE, b.getActionType());
    }

    @Test
    @DisplayName("Presets sans lien : aucun run de lien, comportement mock inchangé")
    void nonLivePresetsUntouched() {
        Map<String, RainbowLivePreset> p = presetsAllAssets();
        bind(p.get("BTC"), 0, 100);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(1, bindingRunRepository.count(), "un seul run de lien : celui de BTC");
        assertTrue(bindingRepository.findAllByPreset(p.get("ETH")).isEmpty());
        assertEquals(RainbowLiveAction.BUY, block2355(p.get("ETH")).getActionType());
    }

    @Test
    @DisplayName("bagPercent 100 / 50 / 0 : vente plafonnée à bag x position réelle")
    void bagPercentCapsSell() {
        double[] closes = concat(flat(N - 2, 100), 150, 150);
        RainbowLivePreset btc = preset("BTC", SELLT);
        serve("BTC", closes);
        RainbowLiveMockWallet w = walletRepository.findByPreset(btc).orElseThrow();
        w.setPositionQuantity(1000); // mock non plafonnant : action mock = quantité du moteur
        walletRepository.save(w);
        reading = reading(PortfolioStatus.OK, 0, Map.of("BTC", 0.002));
        RainbowLiveBinding link = bind(btc, 0, 100);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        RainbowLiveLiveBlock full = live2355(link);
        double engineQty = block2355(btc).getActionQuantity();
        assertEquals(RainbowLiveAction.SELL, full.getActionType());
        assertEquals(Math.min(engineQty, 0.002), full.getActionQuantity(), 1e-12);
        assertEquals(0.002, full.getTradableQty(), 1e-12);

        slots.clear();
        bindingRunRepository.deleteAll();
        bindingRepository.deleteAll();
        bindingRepository.flush();
        RainbowLiveBinding halfLink = bind(btc, 0, 50);
        reading = reading(PortfolioStatus.OK, 0, Map.of("BTC", 0.002));
        runRepository.deleteAll();
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        RainbowLiveLiveBlock half = live2355(halfLink);
        assertEquals(0.001, half.getTradableQty(), 1e-12);
        assertEquals(Math.min(block2355(btc).getActionQuantity(), 0.001), half.getActionQuantity(), 1e-12);

        slots.clear();
        bindingRunRepository.deleteAll();
        bindingRepository.deleteAll();
        bindingRepository.flush();
        RainbowLiveBinding noneLink = bind(btc, 0, 0);
        runRepository.deleteAll();
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        RainbowLiveLiveBlock none = live2355(noneLink);
        assertEquals(0.0, none.getTradableQty(), 1e-12);
        assertEquals(RainbowLiveAction.NONE, none.getActionType());
    }

    @Test
    @DisplayName("Rejeu 23:55 : snapshot et action live d'origine conservés même si le portefeuille a changé")
    void replayKeepsOriginalLiveAction() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        assertEquals(RainbowLiveAction.BUY, live2355(link).getActionType());

        reading = reading(PortfolioStatus.OK, 0.0, Map.of());
        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLiveLiveBlock b = live2355(link);
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(1000.0, b.getCashUsd(), 1e-12);
        assertEquals(LiveBlockReason.NONE, b.getBlockReason());
        verify(realSource, times(1)).read(any(RainbowLiveBinding.class));
    }

    @Test
    @DisplayName("00:05 : relit le snapshot de 23:55 (pas de nouvel appel exchange) ; snapshot périmé => nouvelle lecture")
    void pass0005ReusesSnapshot() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        reading = reading(PortfolioStatus.OK, 5.0, Map.of()); // le réel a bougé depuis : ne doit pas être relu

        service.runPass(RainbowLivePass.T0005, ASOF_0005);

        RainbowLiveLiveBlock b = live0005(link);
        assertEquals(1000.0, b.getCashUsd(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        verify(realSource, times(1)).read(any(RainbowLiveBinding.class));

        when(realSource.checkFreshness(any(PortfolioReading.class))).thenAnswer(inv -> {
            PortfolioReading r = inv.getArgument(0);
            return new PortfolioReading(r.cash(), r.positions(), r.fetchedAt(), PortfolioStatus.STALE, r.walletId());
        });
        service.runPass(RainbowLivePass.T0005, ASOF_0005);

        RainbowLiveLiveBlock fresh = live0005(link);
        assertEquals(5.0, fresh.getCashUsd(), 1e-12);
        assertEquals(RainbowLiveAction.BLOCKED, fresh.getActionType());
        verify(realSource, times(2)).read(any(RainbowLiveBinding.class));
    }

    @Test
    @DisplayName("Même preset sur deux wallets : deux snapshots indépendants, signal identique, tailles différentes")
    void samePresetTwoLinksIndependentSnapshots() {
        double[] closes = concat(flat(N - 2, 100), 150, 150);
        RainbowLivePreset btc = preset("BTC", SELLT);
        serve("BTC", closes);
        RainbowLiveMockWallet w = walletRepository.findByPreset(btc).orElseThrow();
        w.setPositionQuantity(1000);
        walletRepository.save(w);
        Wallet kraken = newWallet("Kraken");
        RainbowLiveBinding okxLink = bind(btc, 0, 100, wallet);
        RainbowLiveBinding krakenLink = bind(btc, 1, 50, kraken);
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of("BTC", 0.002));
        readingByWallet.put(kraken.getId(), reading(kraken.getId(), PortfolioStatus.OK, 300.0, Map.of("BTC", 0.002)));

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        double engineQty = block2355(btc).getActionQuantity();
        RainbowLiveLiveBlock okx = live2355(okxLink);
        RainbowLiveLiveBlock kr = live2355(krakenLink);
        assertEquals(RainbowLiveAction.SELL, okx.getActionType());
        assertEquals(RainbowLiveAction.SELL, kr.getActionType());
        assertEquals(wallet.getId(), okx.getWalletId());
        assertEquals(kraken.getId(), kr.getWalletId());
        assertEquals(0.002, okx.getTradableQty(), 1e-12);
        assertEquals(0.001, kr.getTradableQty(), 1e-12);
        assertEquals(Math.min(engineQty, 0.002), okx.getActionQuantity(), 1e-12);
        assertEquals(Math.min(engineQty, 0.001), kr.getActionQuantity(), 1e-12);
        assertEquals(1, runRepository.findByPresetOrderByDayAsc(btc).stream().filter(r -> r.getDay().equals(LAST_DAY)).count(),
                "un seul run de simulation pour le preset");
    }

    @Test
    @DisplayName("Un wallet en panne n'altère pas l'autre lien ; cash commun par wallet respecté entre actifs d'un même wallet")
    void outageIsolatedAndCashPerWallet() {
        Map<String, RainbowLivePreset> p = presetsAllAssets();
        Wallet kraken = newWallet("Kraken");
        RainbowLiveBinding btcOkx = bind(p.get("BTC"), 0, 100, wallet);
        RainbowLiveBinding btcKraken = bind(p.get("BTC"), 1, 100, kraken);
        RainbowLiveBinding ethKraken = bind(p.get("ETH"), 2, 100, kraken);
        reading = reading(PortfolioStatus.UNAVAILABLE, 0, Map.of());
        readingByWallet.put(kraken.getId(), reading(kraken.getId(), PortfolioStatus.OK, 70.0, Map.of()));

        var summary = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(0, summary.errors());
        assertEquals(PortfolioStatus.UNAVAILABLE, live2355(btcOkx).getStatus());
        assertEquals(RainbowLiveAction.NONE, live2355(btcOkx).getActionType());
        // Kraken : 70 USD partagés entre BTC (50) puis ETH (reste 20 < 50)
        assertEquals(RainbowLiveAction.BUY, live2355(btcKraken).getActionType());
        assertEquals(RainbowLiveAction.BLOCKED, live2355(ethKraken).getActionType());
        assertEquals(BASE, live2355(ethKraken).getCashReserved(), 1e-12);
    }

    @Test
    @DisplayName("Supprimer un lien supprime ses runs, jamais ceux du preset")
    void deletingLinkKeepsPresetRuns() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        RainbowLiveBinding link = bind(btc, 0, 100);
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        assertEquals(1, bindingRunRepository.count());
        long presetRuns = runRepository.count();

        bindingRepository.delete(link);
        bindingRepository.flush();
        em.clear();

        assertEquals(0, bindingRunRepository.count());
        assertEquals(presetRuns, runRepository.count());
    }
}
