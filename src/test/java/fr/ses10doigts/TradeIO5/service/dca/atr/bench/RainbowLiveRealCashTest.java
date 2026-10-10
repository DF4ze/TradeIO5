package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveMockWalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Preset live : dimensionnement sur le portefeuille réel (port simulé, aucun exchange). Série plate => un BUY_ZONE de
 * {@code base} (50 USDC) par jour ; série finale en hausse => SELL.
 */
@DataJpaTest
@Import({RainbowLivePresetService.class, RainbowLivePresetTemplateService.class, RainbowLiveRunService.class,
        RainbowLiveExecutionService.class, RainbowTrendLiveService.class, RainbowAthService.class,
        MockPortfolioSource.class, RainbowLiveRealCashTest.ClockConfig.class})
@DisplayName("Bench Rainbow : preset live sur vrai cash (ledger, blocage, indisponibilité, rejeu, 00:05)")
class RainbowLiveRealCashTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final int N = 30;
    private static final LocalDate LAST_DAY = START.plusDays(N - 1);
    private static final Instant ASOF_2355 = Instant.parse("2026-09-30T23:55:00Z");
    private static final Instant ASOF_0005 = Instant.parse("2026-10-01T00:05:00Z");
    private static final Long WALLET = 77L;
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
    @Autowired private UserRepository userRepository;

    private final Map<String, List<MarketData>> candlesByAsset = new HashMap<>();
    /** Presets liés (id -> slot) et lecture servie par le port simulé. */
    private final List<LiveSlot> slots = new ArrayList<>();
    private PortfolioReading reading;
    private User alice;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of());
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            serve(a, flat(N, 100));
        }
        when(datasetEngine.getDatasetForAsset(anyString(), eq(TimeFrame.D1), anyInt(), any(Instant.class)))
                .thenAnswer(inv -> {
                    Instant endTime = inv.getArgument(3);
                    List<MarketData> visible = candlesByAsset.get((String) inv.getArgument(0)).stream()
                            .filter(c -> !c.getTimestamp().isAfter(endTime)).toList();
                    return MarketDataset.builder().pair(inv.getArgument(0)).timeFrame(TimeFrame.D1).marketDatas(visible)
                            .size(visible.size()).isComplete(true).build();
                });
        when(realSource.liveSlots(any(User.class))).thenAnswer(inv -> List.copyOf(slots));
        when(realSource.read(any(RainbowLivePreset.class))).thenAnswer(inv -> reading);
        when(realSource.checkFreshness(any(PortfolioReading.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static PortfolioReading reading(PortfolioStatus status, double cash, Map<String, Double> positions) {
        return status == PortfolioStatus.UNAVAILABLE ? PortfolioReading.unavailable(WALLET, ASOF_2355)
                : new PortfolioReading(cash, positions, ASOF_2355, status, WALLET);
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

    private void bind(RainbowLivePreset p, int priority, double bagPercent) {
        slots.add(new LiveSlot(p.getId(), p.getAssetSymbol(), WALLET, priority, bagPercent));
    }

    private RainbowLivePassBlock block2355(RainbowLivePreset p) {
        return runRepository.findByPresetAndDay(p, LAST_DAY).orElseThrow().getPass2355();
    }

    private RainbowLivePassBlock block0005(RainbowLivePreset p) {
        return runRepository.findByPresetAndDay(p, LAST_DAY).orElseThrow().getPass0005();
    }

    @Test
    @DisplayName("Achat <= cash : BUY live réservé, snapshot complet ; la chaîne mock reste inchangée")
    void buyWithinCash() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        bind(btc, 0, 100);
        reading = reading(PortfolioStatus.OK, 1000.0, Map.of("BTC", 0.4));

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = block2355(btc);
        assertEquals(PortfolioStatus.OK, b.getLiveStatus());
        assertEquals(WALLET, b.getLiveWalletId());
        assertEquals(ASOF_2355, b.getLiveFetchedAt());
        assertEquals(1000.0, b.getLiveCashUsdc(), 1e-12);
        assertEquals(0.4, b.getLivePositionQty(), 1e-12);
        assertEquals(0.4, b.getLiveTradableQty(), 1e-12);
        assertEquals(0.0, b.getLiveCashReserved(), 1e-12);
        assertEquals(LiveBlockReason.NONE, b.getLiveBlockReason());
        assertEquals(RainbowLiveAction.BUY, b.getLiveActionType());
        assertEquals(BASE, b.getLiveActionAmountUsdc(), 1e-12);
        assertEquals(BASE / 100, b.getLiveActionQuantity(), 1e-12);
        // chaîne mock indépendante : le wallet mock a bien reçu l'achat fictif
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
        assertEquals(1000.0 - BASE, walletRepository.findByPreset(btc).orElseThrow().getCashUsdc(), 1e-12);
    }

    @Test
    @DisplayName("Achat > cash : BLOCKED + INSUFFICIENT_CASH (tout-ou-rien), montant voulu conservé")
    void buyBeyondCashIsBlocked() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        bind(btc, 0, 100);
        reading = reading(PortfolioStatus.OK, 10.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = block2355(btc);
        assertEquals(RainbowLiveAction.BLOCKED, b.getLiveActionType());
        assertEquals(LiveBlockReason.INSUFFICIENT_CASH, b.getLiveBlockReason());
        assertEquals(BASE, b.getLiveActionAmountUsdc(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
    }

    @Test
    @DisplayName("Cash commun : les actifs passent par priority croissante, chaque achat réserve, le suivant voit le reste")
    void ledgerFollowsPriority() {
        Map<String, RainbowLivePreset> p = presetsAllAssets();
        bind(p.get("BTC"), 2, 100);
        bind(p.get("ETH"), 1, 100);
        bind(p.get("PAXG"), 3, 100);
        reading = reading(PortfolioStatus.OK, 120.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        // ETH (1) puis BTC (2) puis PAXG (3) : 50 + 50 réservés, il reste 20 < 50 pour PAXG
        RainbowLivePassBlock eth = block2355(p.get("ETH"));
        RainbowLivePassBlock btc = block2355(p.get("BTC"));
        RainbowLivePassBlock paxg = block2355(p.get("PAXG"));
        assertEquals(RainbowLiveAction.BUY, eth.getLiveActionType());
        assertEquals(0.0, eth.getLiveCashReserved(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, btc.getLiveActionType());
        assertEquals(BASE, btc.getLiveCashReserved(), 1e-12);
        assertEquals(RainbowLiveAction.BLOCKED, paxg.getLiveActionType());
        assertEquals(2 * BASE, paxg.getLiveCashReserved(), 1e-12);
    }

    @Test
    @DisplayName("Portefeuille indisponible : aucune action live (UNAVAILABLE), la chaîne mock continue")
    void unavailableMeansNoAction() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        bind(btc, 0, 100);
        reading = reading(PortfolioStatus.UNAVAILABLE, 0, Map.of());

        var summary = service.runPass(RainbowLivePass.T2355, ASOF_2355);

        assertEquals(0, summary.errors());
        RainbowLivePassBlock b = block2355(btc);
        assertEquals(PortfolioStatus.UNAVAILABLE, b.getLiveStatus());
        assertEquals(RainbowLiveAction.NONE, b.getLiveActionType());
        assertEquals(LiveBlockReason.UNAVAILABLE, b.getLiveBlockReason());
        assertNull(b.getLiveCashUsdc());
        assertEquals(RainbowLiveAction.BUY, b.getActionType());
    }

    @Test
    @DisplayName("Lecture STALE : même traitement qu'indisponible, statut STALE tracé")
    void staleMeansNoAction() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        bind(btc, 0, 100);
        reading = reading(PortfolioStatus.STALE, 1000.0, Map.of());

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = block2355(btc);
        assertEquals(PortfolioStatus.STALE, b.getLiveStatus());
        assertEquals(RainbowLiveAction.NONE, b.getLiveActionType());
    }

    @Test
    @DisplayName("Presets non live : aucun champ live, comportement mock inchangé")
    void nonLivePresetsUntouched() {
        Map<String, RainbowLivePreset> p = presetsAllAssets();
        bind(p.get("BTC"), 0, 100);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock eth = block2355(p.get("ETH"));
        assertNull(eth.getLiveStatus());
        assertNull(eth.getLiveActionType());
        assertEquals(RainbowLiveAction.BUY, eth.getActionType());
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
        bind(btc, 0, 100);

        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        RainbowLivePassBlock full = block2355(btc);
        double engineQty = full.getActionQuantity();
        assertEquals(RainbowLiveAction.SELL, full.getLiveActionType());
        assertEquals(Math.min(engineQty, 0.002), full.getLiveActionQuantity(), 1e-12);
        assertEquals(0.002, full.getLiveTradableQty(), 1e-12);

        slots.clear();
        bind(btc, 0, 50);
        reading = reading(PortfolioStatus.OK, 0, Map.of("BTC", 0.002));
        runRepository.deleteAll();
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        RainbowLivePassBlock half = block2355(btc);
        assertEquals(0.001, half.getLiveTradableQty(), 1e-12);
        assertEquals(Math.min(half.getActionQuantity(), 0.001), half.getLiveActionQuantity(), 1e-12);

        slots.clear();
        bind(btc, 0, 0);
        runRepository.deleteAll();
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        RainbowLivePassBlock none = block2355(btc);
        assertEquals(0.0, none.getLiveTradableQty(), 1e-12);
        assertEquals(RainbowLiveAction.NONE, none.getLiveActionType());
    }

    @Test
    @DisplayName("Rejeu 23:55 : snapshot et action live d'origine conservés même si le portefeuille a changé")
    void replayKeepsOriginalLiveAction() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        bind(btc, 0, 100);
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        assertEquals(RainbowLiveAction.BUY, block2355(btc).getLiveActionType());

        reading = reading(PortfolioStatus.OK, 0.0, Map.of());
        service.runPass(RainbowLivePass.T2355, ASOF_2355);

        RainbowLivePassBlock b = block2355(btc);
        assertEquals(RainbowLiveAction.BUY, b.getLiveActionType());
        assertEquals(1000.0, b.getLiveCashUsdc(), 1e-12);
        assertEquals(LiveBlockReason.NONE, b.getLiveBlockReason());
        verify(realSource, times(1)).read(any(RainbowLivePreset.class));
    }

    @Test
    @DisplayName("00:05 : relit le snapshot de 23:55 (pas de nouvel appel exchange) ; snapshot périmé => nouvelle lecture")
    void pass0005ReusesSnapshot() {
        RainbowLivePreset btc = presetsAllAssets().get("BTC");
        bind(btc, 0, 100);
        service.runPass(RainbowLivePass.T2355, ASOF_2355);
        reading = reading(PortfolioStatus.OK, 5.0, Map.of()); // le réel a bougé depuis : ne doit pas être relu

        service.runPass(RainbowLivePass.T0005, ASOF_0005);

        RainbowLivePassBlock b = block0005(btc);
        assertEquals(1000.0, b.getLiveCashUsdc(), 1e-12);
        assertEquals(RainbowLiveAction.BUY, b.getLiveActionType());
        verify(realSource, times(1)).read(any(RainbowLivePreset.class));

        when(realSource.checkFreshness(any(PortfolioReading.class))).thenAnswer(inv -> {
            PortfolioReading r = inv.getArgument(0);
            return new PortfolioReading(r.cash(), r.positions(), r.fetchedAt(), PortfolioStatus.STALE, r.walletId());
        });
        service.runPass(RainbowLivePass.T0005, ASOF_0005);

        RainbowLivePassBlock fresh = block0005(btc);
        assertEquals(5.0, fresh.getLiveCashUsdc(), 1e-12);
        assertEquals(RainbowLiveAction.BLOCKED, fresh.getLiveActionType());
        verify(realSource, times(2)).read(any(RainbowLivePreset.class));
    }
}
