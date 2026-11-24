package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.NewAssetLinkedEvent;
import fr.ses10doigts.tradeIO5.service.expert.simulation.DailyDataLoader;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1b, lot 1 : chaîne complète sur un actif de test (BTC rejoué comme « nouvel actif »), historique court = « provisoire » sans blocage,
 * source indisponible = proposition dégradée sans exception, étiquette source propagée, rien d'appliqué, déclenchement asynchrone.
 */
class NewAssetProposalTest {

    private static final NewAssetConfig CFG = NewAssetConfig.defaults();
    private static final RainbowModulationControl LIMITS = PresetOptimizerTestSupport.LIMITS;
    private static final double K = 1.1;
    private static final fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService ALERTS = mock(fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService.class);
    private static List<MarketData> btcCandles;
    private static TransferStudy.Source btcSource;

    @BeforeAll
    static void load() throws IOException {
        btcCandles = PresetOptimizerTestSupport.csv("BTC", Integer.MAX_VALUE);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
        btcSource = new TransferStudy.Source("BTC", sets, 6, sets[0].globals().baseAmount() / TransferConfig.defaults().cash0());
    }

    // ------------------------------------------------------------------ calage allégé = C3 / C4

    @Test
    @DisplayName("Calage allégé : mêmes niveaux que la mesure C4 (fonctions de C3) à k égal, sur la même configuration")
    void liteGivesTheC3Level() {
        List<Double> grid = List.of(0.5, 1.0, 2.0, 4.0, 8.0, 16.0);
        ExposureCalibrationConfig d = ExposureCalibrationConfig.defaults(PresetOptimizerTestSupport.END);
        ExposureCalibrationConfig cal = new ExposureCalibrationConfig(d.parity(), grid, 24, 180, 72, 12, 12, 12, List.of(1.0), 0.9, d.bearFrom(), d.bearTo(), 90);
        ExposureProductionConfig prod = new ExposureProductionConfig(cal, K, 1.0, List.of(1_000.0, 10_000.0), 90, 1.0, 2.0, 1.0, 2.0, 0.5, 4);
        ScorerInputs in = ScorerInputs.of(List.of(PresetOptimizerTestSupport.link("BTC", btcCandles)), 2_484.0, PresetOptimizerTestSupport.END,
                fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile.BALANCED, LIMITS);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var c4 = ExposureProductionStudy.runAsset(in, prod, pool);
            ExposureCalibrationStudy.Lite lite = ExposureCalibrationStudy.lite(in, cal, K, pool);
            assertEquals(c4.profiles().size(), lite.levels().size());
            for (int i = 0; i < lite.levels().size(); i++) {
                Level l = lite.levels().get(i);
                var p = c4.profiles().get(i);
                assertEquals(p.profile(), l.profile());
                assertEquals(p.level().multiplier(), l.multiplier(), 1e-12, "niveau " + l.profile());
                assertEquals(p.level().baseRate(), l.baseRate(), 1e-12, "taux " + l.profile());
                assertEquals(p.level().status(), l.status());
            }
        } finally {
            pool.shutdown();
        }
    }

    // ------------------------------------------------------------------ chaîne complète

    @Test
    @DisplayName("BTC rejoué comme nouvel actif : 4 profils, taux croissants, étiquette source propagée, aucun drapeau bloquant")
    void btcReplayedAsNewAsset() {
        NewAssetProposalResult r = NewAssetProposalCalculator.compute("NEWBTC", "TEST/LABEL", btcCandles, btcSource, CFG, K, LIMITS,
                Executors.newFixedThreadPool(2));
        assertEquals("BTC", r.presetSource());
        assertEquals("TEST/LABEL", r.historySource());
        assertNotNull(r.fingerprint());
        assertTrue(r.hasRates());
        assertEquals(4, r.levels().size());
        double prev = 0;
        for (Level l : r.levels()) {
            assertTrue(l.baseRate() > prev, "taux croissants Prudent -> Full : " + l);
            prev = l.baseRate();
        }
        assertFalse(r.flags().contains(ProposalFlag.HISTORIQUE_INSUFFISANT));
        assertFalse(r.flags().contains(ProposalFlag.PROVISOIRE));
        assertTrue(r.blocks() >= 2 && r.switches() > 0);
        System.out.println("D1b BTC rejoué : " + r.levels().stream().map(l -> String.format("%s %.4e (table %.4e)", l.profile(), l.baseRate(),
                tableBtc(l.profile()))).toList() + " ; " + r.replays() + " rejeux, " + r.measuredMs() + " ms, estimé " + r.estimatedMinutes() + " min");
    }

    static double tableBtc(String profile) {
        return switch (profile) {
            case "PRUDENT" -> 2.774e-4;
            case "BALANCED" -> 4.736e-4;
            case "DYNAMIC" -> 7.712e-4;
            default -> 1.423e-3;
        };
    }

    // ------------------------------------------------------------------ historique court / absent : jamais bloquant

    @Test
    @DisplayName("Historique court (< 24 mois) : drapeau PROVISOIRE, détail renseigné, aucune exception, aucun blocage")
    void shortHistoryIsProvisionalNotBlocking() {
        NewAssetProposalResult r = NewAssetProposalCalculator.compute("SHORT", "TEST", btcCandles.subList(btcCandles.size() - 600, btcCandles.size() - 120),
                btcSource, CFG, K, LIMITS, null);
        assertTrue(r.flags().contains(ProposalFlag.PROVISOIRE), r.flags().toString());
        assertTrue(r.historyMonths() < 24 && r.historyMonths() > 10);
        assertNotNull(r.fingerprint());
        assertTrue(r.blocks() <= 1);
        assertFalse(r.flags().contains(ProposalFlag.CALAGE_INTERROMPU));
    }

    @Test
    @DisplayName("Trop peu de bougies : « historique insuffisant », drapeau, ni taux ni exception (repli BTC non calibré)")
    void tooFewCandles() {
        NewAssetProposalResult r = NewAssetProposalCalculator.compute("TINY", "TEST", btcCandles.subList(0, 50), btcSource, CFG, K, LIMITS, null);
        assertEquals(List.of(ProposalFlag.HISTORIQUE_INSUFFISANT), r.flags());
        assertFalse(r.hasRates());
        NewAssetProposalResult none = NewAssetProposalCalculator.compute("NONE", "TEST", List.of(), btcSource, CFG, K, LIMITS, null);
        assertEquals(List.of(ProposalFlag.HISTORIQUE_INSUFFISANT), none.flags());
    }

    @Test
    @DisplayName("Budget de coût dépassé : drapeau CALAGE_COUT_ELEVE, calage non lancé, estimation rapportée")
    void costGuard() {
        NewAssetConfig tight = new NewAssetConfig(24, 48, 120, 24, 4, 0.0001, 30, 1000L);
        NewAssetProposalResult r = NewAssetProposalCalculator.compute("COST", "TEST", btcCandles, btcSource, tight, K, LIMITS, null);
        assertTrue(r.flags().contains(ProposalFlag.CALAGE_COUT_ELEVE));
        assertFalse(r.hasRates());
        assertTrue(r.estimatedMinutes() > 0.0001);
        assertEquals(0, r.replays());
    }

    // ------------------------------------------------------------------ historique : provider, complément Binance, étiquette source

    private static DailyDataset daily(List<MarketData> candles) {
        MarketDataset ds = MarketDataset.builder().pair("NEWUSDC").marketDatas(candles).size(candles.size()).build();
        return new DailyDataset(ds, MarketDataSource.KRAKEN, "NEWUSDC", false);
    }

    private static DomainClock clock() {
        DomainClock c = mock(DomainClock.class);
        when(c.now()).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));
        return c;
    }

    @Test
    @DisplayName("Provider assez long : gardé tel quel, Binance jamais appelé")
    void providerSufficient(@TempDir Path dir) {
        DailyDataLoader loader = mock(DailyDataLoader.class);
        when(loader.load("NEW", MarketDataSource.KRAKEN)).thenReturn(daily(btcCandles));
        BenchHistoryFetcher fetcher = mock(BenchHistoryFetcher.class);
        var h = new NewAssetHistoryResolver(loader, fetcher, clock()).resolve("NEW", MarketDataSource.KRAKEN, CFG, dir);
        assertEquals(btcCandles.size(), h.candles().size());
        assertTrue(h.sourceLabel().startsWith("KRAKEN/"));
        verify(fetcher, never()).fetch(any());
    }

    @Test
    @DisplayName("Provider court : complément Binance USDT stocké dans le stockage bench avec l'étiquette source, relu et vérifié (sha256)")
    void binanceComplementIsLabelledAndStored(@TempDir Path dir) throws IOException {
        DailyDataLoader loader = mock(DailyDataLoader.class);
        when(loader.load("NEW", MarketDataSource.KRAKEN)).thenReturn(daily(btcCandles.subList(btcCandles.size() - 300, btcCandles.size())));
        BenchHistoryFetcher fetcher = a -> Optional.of(new BenchHistoryFetcher.Fetched(a + "USDT", "USDT", btcCandles));
        var h = new NewAssetHistoryResolver(loader, fetcher, clock()).resolve("NEW", MarketDataSource.KRAKEN, CFG, dir);
        assertEquals(BenchCandleStore.SOURCE_LABEL, h.sourceLabel());
        assertEquals(btcCandles.size(), h.candles().size());
        BenchCandleStore.Bench stored = BenchCandleStore.load(dir, "NEW");
        assertEquals(BenchCandleStore.SOURCE_LABEL, stored.sourceLabel());
        assertEquals("USDT", stored.entry().quote());
        assertEquals(btcCandles.size(), stored.candles().size());
    }

    @Test
    @DisplayName("Binance injoignable et provider court : on garde le provider, note posée, aucune exception ; provider en échec aussi : historique vide")
    void binanceDownIsDegradedNotFailing(@TempDir Path dir) {
        DailyDataLoader loader = mock(DailyDataLoader.class);
        when(loader.load("NEW", MarketDataSource.KRAKEN)).thenReturn(daily(btcCandles.subList(btcCandles.size() - 300, btcCandles.size())));
        BenchHistoryFetcher down = a -> Optional.empty();
        var h = new NewAssetHistoryResolver(loader, down, clock()).resolve("NEW", MarketDataSource.KRAKEN, CFG, dir);
        assertEquals(300 - 0, h.candles().size());
        assertTrue(h.note().contains("Binance"));
        DailyDataLoader broken = mock(DailyDataLoader.class);
        when(broken.load(any(), any())).thenThrow(new IllegalStateException("kraken KO"));
        var none = new NewAssetHistoryResolver(broken, down, clock()).resolve("NEW", MarketDataSource.KRAKEN, CFG, dir);
        assertTrue(none.candles().isEmpty());
        NewAssetProposalResult r = NewAssetProposalCalculator.compute("NEW", none.sourceLabel(), none.candles(), btcSource, CFG, K, LIMITS, null);
        assertEquals(List.of(ProposalFlag.HISTORIQUE_INSUFFISANT), r.flags());
    }

    // ------------------------------------------------------------------ service : déclenchement, asynchrone, rien d'appliqué

    private record Rig(NewAssetProposalService service, fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository proposals,
                       fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService control,
                       fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService affectations,
                       fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository bindings, NewAssetHistoryResolver history) {
    }

    @SuppressWarnings("unchecked")
    private Rig rig(NewAssetHistoryResolver history, Path dir) {
        var proposals = mock(fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository.class);
        when(proposals.save(any())).thenAnswer(i -> i.getArgument(0));
        var bindings = mock(fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository.class);
        var binding = mock(fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding.class);
        when(bindings.findById(7L)).thenReturn(Optional.of(binding));
        var users = mock(fr.ses10doigts.tradeIO5.security.repository.UserRepository.class);
        var user = mock(fr.ses10doigts.tradeIO5.security.model.User.class);
        when(user.getUsername()).thenReturn("admin");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        var affectations = mock(fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService.class);
        var resolver = mock(fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver.class);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull("BTC").toArray(new RainbowAtrParamSet[0]);
        when(resolver.trendConfig(any())).thenReturn(new fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs.Resolved(
                fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator.Params.defaults(),
                fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping.KEEP_PREVIOUS, sets[0], sets[1]));
        when(resolver.analysisWindowMonths(any())).thenReturn(6);
        var control = mock(fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService.class);
        when(control.current()).thenReturn(LIMITS);
        var check = mock(fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheck.class);
        when(check.check(any(), any())).thenReturn(new fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckResult(
                fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, "pas de chemin sans fiat"));
        var tx = mock(org.springframework.transaction.support.TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
        var svc = new NewAssetProposalService(proposals, bindings, users, affectations, resolver, control, history, check, tx, clock(),
                ALERTS, dir.toString());
        return new Rig(svc, proposals, control, affectations, bindings, history);
    }

    @Test
    @DisplayName("Service : proposition persistée PROPOSE seulement, non tradable = avertissement (jamais de blocage), étiquette propagée, aucune écriture hors proposition")
    void proposeIsOnlyAProposal(@TempDir Path dir) {
        List<MarketData> shortRun = btcCandles.subList(btcCandles.size() - 700, btcCandles.size());
        NewAssetHistoryResolver history = mock(NewAssetHistoryResolver.class);
        when(history.resolve(any(), any(), any(), any())).thenReturn(new NewAssetHistoryResolver.History(shortRun, "BINANCE/USDT", "complété"));
        Rig rig = rig(history, dir);
        NewAssetProposal p = rig.service().propose(new NewAssetLinkedEvent(1L, "NEWX", 7L, 3L));
        assertEquals(NewAssetProposalStatus.PROPOSE, p.getStatus());
        assertEquals("BTC", p.getPresetSource());
        assertEquals("BINANCE/USDT", p.getHistorySource());
        assertTrue(p.getFlags().contains("NON_TRADABLE") && p.getWarning().contains("non tradable"));
        assertNull(p.getDecidedAt());
        assertNull(p.getDecidedBy());
        assertEquals("admin", p.getRequestedBy());
        assertNotNull(p.getCandleChecksum());
        verify(rig.proposals()).save(any());
        verify(ALERTS).alert(p);
        verify(rig.control(), never()).update(any(), any());
        verify(rig.bindings(), never()).save(any());
    }

    @Test
    @DisplayName("Déclenchement : asynchrone (rend la main avant le calcul, fil dédié basse priorité), ignoré pour BTC / ETH et pour un actif déjà proposé")
    void triggerIsAsyncAndFiltered(@TempDir Path dir) throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        NewAssetHistoryResolver history = mock(NewAssetHistoryResolver.class);
        when(history.resolve(any(), any(), any(), any())).thenAnswer(i -> {
            thread.set(Thread.currentThread());
            started.countDown();
            assertTrue(release.await(30, java.util.concurrent.TimeUnit.SECONDS));
            return new NewAssetHistoryResolver.History(List.of(), null, "vide");
        });
        Rig rig = rig(history, dir);
        long t0 = System.nanoTime();
        rig.service().onLinked(new NewAssetLinkedEvent(1L, "NEWY", 7L, 3L));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000, "onLinked doit rendre la main sans attendre le calcul");
        assertTrue(started.await(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("new-asset-proposal", thread.get().getName());
        assertEquals(Thread.MIN_PRIORITY, thread.get().getPriority());
        assertTrue(thread.get().isDaemon());
        release.countDown();
        verify(rig.proposals(), org.mockito.Mockito.timeout(30_000)).save(any());
        // BTC / ETH : jamais de proposition ; actif déjà proposé : idem
        rig.service().onLinked(new NewAssetLinkedEvent(1L, "BTC", 7L, 3L));
        rig.service().onLinked(new NewAssetLinkedEvent(1L, "ETH", 7L, 3L));
        when(rig.proposals().existsByAssetSymbol("NEWZ")).thenReturn(true);
        rig.service().onLinked(new NewAssetLinkedEvent(1L, "NEWZ", 7L, 3L));
        Thread.sleep(300);
        verify(rig.proposals(), org.mockito.Mockito.times(1)).save(any());
        assertEquals(ModulationDefaults.CALIBRATED_RATE_ASSETS, java.util.Set.of("BTC", "ETH"));
    }
}
