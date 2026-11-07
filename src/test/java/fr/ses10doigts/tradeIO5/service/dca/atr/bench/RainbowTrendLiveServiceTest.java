package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.repository.admin.AdminActionLogRepository;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAthReference;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowAthReferenceRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveEngineStateRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.calibration.BinanceDailyCandleFetcher;
import fr.ses10doigts.tradeIO5.service.calibration.dto.DailyCandle;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({ExpertBasePresetService.class, AffectationConfigResolver.class, RainbowLivePresetEventService.class, AssetAffectationService.class, RainbowLiveRunService.class, RainbowLiveBindingRunService.class, RainbowLiveExecutionService.class, fr.ses10doigts.tradeIO5.service.portfolio.PortfolioSnapshotService.class,
        RainbowTrendLiveService.class, fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService.class, RainbowAthService.class, RainbowTrendLiveServiceTest.ClockConfig.class, UserTradingSettingsService.class, RainbowModulationControlService.class})
@DisplayName("Live Rainbow : presets Trend Mix (état persisté, ATH en base)")
class RainbowTrendLiveServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final int N = 300;
    /** Premier jour joué : index 200 (au-delà du warmup du Trend Mix). */
    private static final int FIRST = 200;
    private static final String ASSET = "BTC";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-01T00:00:00Z"));
        }
    }

    @MockBean private MarketDatasetEngine datasetEngine;
    /** Aucun binding : aucun lien live (liveSlots vide), seul le signal du preset est calculé. */
    @MockBean private RealPortfolioSource realPortfolioSource;
    @MockBean private BinanceDailyCandleFetcher fetcher;
    @Autowired private RainbowLiveExecutionService service;
    @Autowired private AssetAffectationService affectationService;
    @Autowired private ExpertBasePresetService basePresetService;
    @Autowired private RainbowPresetRepository rainbowRepository;
    @Autowired private RainbowLiveEngineStateRepository stateRepository;
    @Autowired private RainbowAthReferenceRepository athRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RainbowLivePresetEventService eventService;
    @Autowired private AffectationConfigResolver configResolver;
    @Autowired private AdminActionLogRepository adminLog;

    private List<MarketData> candles;
    private AssetAffectation btc;

    @BeforeEach
    void setUp() {
        candles = new ArrayList<>();
        List<DailyCandle> daily = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            double c = 100 + 25 * Math.sin(i / 15.0) + i * 0.15;
            LocalDate d = START.plusDays(i);
            candles.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(d.atStartOfDay(ZoneOffset.UTC).toInstant())
                    .pair(ASSET).open(BigDecimal.valueOf(c)).high(BigDecimal.valueOf(c * 1.01))
                    .low(BigDecimal.valueOf(c * 0.99)).close(BigDecimal.valueOf(c)).volume(BigDecimal.ONE).build());
            daily.add(new DailyCandle(d, c, c * 1.01, c * 0.99, c, 1));
        }
        when(fetcher.fetchFullHistory(anyString())).thenReturn(daily);
        when(datasetEngine.getDailyDatasetForAsset(anyString(), anyInt(), any(Instant.class), any()))
                .thenAnswer(inv -> {
                    Instant end = inv.getArgument(2);
                    List<MarketData> visible = candles.stream().filter(c -> !c.getTimestamp().isAfter(end)).toList();
                    return new DailyDataset(MarketDataset.builder().pair(inv.getArgument(0)).timeFrame(TimeFrame.D1).marketDatas(visible)
                            .size(visible.size()).isComplete(true).build(), MarketDataSource.BINANCE, "TEST", false);
                });
        User alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        basePresetService.ensureBasePresets();
        btc = null;
        for (AssetAffectation a : affectationService.ensureAffectations(alice)) {
            AssetAffectation enabled = affectationService.setEnabled(alice, a.getId(), true);
            if (a.getAssetSymbol().equals(ASSET)) {
                btc = enabled;
            }
        }
    }

    private LocalDate day(int idx) {
        return START.plusDays(idx);
    }

    private void pass2355(int idx) {
        service.runPass(day(idx).atTime(23, 55).toInstant(ZoneOffset.UTC), null);
    }

    @Test
    @DisplayName("1re passe : amorçage de l'état par rejeu, état du jour et ATH du jour enregistrés, jeu et Trend tracés")
    void firstPassBootstrapsAndPersists() {
        pass2355(FIRST);

        assertTrue(stateRepository.findByAffectationAndDay(btc, day(FIRST - 1)).isPresent(), "état amorcé la veille");
        assertTrue(stateRepository.findByAffectationAndDay(btc, day(FIRST)).isPresent(), "état du jour");
        RainbowAthReference ath = athRepository.findByAssetSymbolAndDay(ASSET, day(FIRST)).orElseThrow();
        double expected = candles.subList(0, FIRST + 1).stream().mapToDouble(c -> c.getHigh().doubleValue()).max().orElseThrow();
        assertEquals(expected, ath.getAthValue(), 1e-9);

        RainbowLiveRun run = runRepository.findByAffectationAndDay(btc, day(FIRST)).orElseThrow();
        assertNotNull(run.getSignal().getActiveSet());
        assertTrue(run.getSignal().getActiveSet().startsWith("BTC Perso"));
        assertNotNull(run.getSignal().getTrendRegime());
    }

    @Test
    @DisplayName("Passe du lendemain : le run de la veille est rafraîchi sur la clôture finale (indicateurs, ATH, état), action figée")
    void nextDayRefreshesPreviousRun() {
        pass2355(FIRST);
        RainbowLiveRun first = runRepository.findByAffectationAndDay(btc, day(FIRST)).orElseThrow();
        var action = first.getSignal().getActionType();
        double provisional = first.getSignal().getClose();
        double finalClose = provisional + 3;
        MarketData old = candles.get(FIRST);
        candles.set(FIRST, MarketData.builder().timeFrame(TimeFrame.D1).timestamp(old.getTimestamp()).pair(ASSET)
                .open(old.getOpen()).high(BigDecimal.valueOf(finalClose * 1.02)).low(old.getLow())
                .close(BigDecimal.valueOf(finalClose)).volume(BigDecimal.ONE).build());

        pass2355(FIRST + 1);

        RainbowLiveRun refreshed = runRepository.findByAffectationAndDay(btc, day(FIRST)).orElseThrow();
        assertEquals(finalClose, refreshed.getSignal().getClose(), 1e-9);
        assertEquals(action, refreshed.getSignal().getActionType(), "l'action décidée à 23:55 reste figée");
        assertEquals(finalClose * 1.02, athRepository.findByAssetSymbolAndDay(ASSET, day(FIRST)).orElseThrow().getAthValue(), 1e-9);
        assertTrue(runRepository.findByAffectationAndDay(btc, day(FIRST + 1)).isPresent());
    }

    @Test
    @DisplayName("Position virtuelle du moteur : persistée par jour, achats = montant/close, ventes = quantité, jamais négative")
    void virtualPositionIsPersisted() {
        int last = FIRST + 60;
        for (int i = FIRST; i <= last; i++) {
            pass2355(i);
        }
        double pos = stateRepository.findByAffectationAndDay(btc, day(FIRST - 1)).orElseThrow().getPositionQty();
        for (int i = FIRST; i <= last; i++) {
            var sig = runRepository.findByAffectationAndDay(btc, day(i)).orElseThrow().getSignal();
            double buy = sig.getActionType() == fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction.BUY
                    ? sig.getActionAmountUsdc() / sig.getClose() : 0;
            double sell = sig.getActionType() == fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction.SELL
                    ? sig.getActionQuantity() : 0;
            pos = Math.max(0, pos - sell + buy);
            assertEquals(pos, stateRepository.findByAffectationAndDay(btc, day(i)).orElseThrow().getPositionQty(), 1e-9, "jour " + i);
        }
    }

    @Test
    @DisplayName("État persisté jour après jour == état du rejeu complet (hors réserve moon, liée à la position)")
    void persistedStateEqualsReplay() {
        int last = FIRST + 40;
        for (int i = FIRST; i <= last; i++) {
            pass2355(i);
        }
        RainbowAtrState persisted = stateRepository.findByAffectationAndDay(btc, day(last)).orElseThrow().toState();

        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles.subList(0, last + 1));
        TrendMixCalculator.Params tp = TrendMixCalculator.Params.defaults();
        int[] setOfBar = RainbowSetSelector.select(
                TrendMixCalculator.compute(candles.subList(0, last + 1), ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime(),
                RainbowSetSelector.RangeMapping.KEEP_PREVIOUS);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull(ASSET).toArray(new RainbowAtrParamSet[0]);
        long from = day(FIRST).minusMonths(6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int startIdx = 0;
        while (ds.time(startIdx) < from) {
            startIdx++;
        }
        RainbowAtrState replayed = RainbowAtrReplay.runFull(ds, sets, setOfBar, startIdx, last).finalState();

        assertEquals(replayed.buyArmed(), persisted.buyArmed());
        assertEquals(replayed.sellArmed(), persisted.sellArmed());
        assertEquals(replayed.buyLocked(), persisted.buyLocked());
        assertEquals(replayed.buyArmedDays(), persisted.buyArmedDays());
        assertEquals(replayed.sellArmedDays(), persisted.sellArmedDays());
        assertEquals(replayed.cooldown(), persisted.cooldown());
        assertEquals(replayed.moon().active(), persisted.moon().active());
        assertEquals(replayed.lowestSinceArmed(), persisted.lowestSinceArmed(), 1e-9);
        assertEquals(replayed.highestSinceArmed(), persisted.highestSinceArmed(), 1e-9);
    }

    @Test
    @DisplayName("Rejouer la passe 23:55 du même jour est idempotent (une seule ligne d'état et d'ATH par jour)")
    void replayingPassIsIdempotent() {
        pass2355(FIRST);
        long states = stateRepository.count();
        long aths = athRepository.count();
        pass2355(FIRST);
        assertEquals(states, stateRepository.count());
        assertEquals(aths, athRepository.count());
    }

    // --- Changement des presets d'une Affectation : le moteur est réinitialisé, la passe suivante repart de la nouvelle config

    private AssetAffectation followerOf(String username) {
        User u = userRepository.save(User.builder().username(username).email(username + "@example.com").password("x").enabled(true).build());
        AssetAffectation a = affectationService.forAsset(u, ASSET);
        return affectationService.setEnabled(u, a.getId(), true);
    }

    private RainbowPreset withWindow(AssetAffectation a, RainbowPreset base, int windowMonths, String name) {
        return rainbowRepository.save(RainbowPreset.builder().assetSymbol(ASSET).name(name)
                .origin(fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin.USER).owner(a.getUser()).revision(1)
                .analysisWindowMonths(windowMonths).config(base.getConfig()).createdAt(base.getCreatedAt())
                .updatedAt(base.getUpdatedAt()).build());
    }

    private void changeWindow(AssetAffectation a, int windowMonths) {
        RainbowPreset up = withWindow(a, a.getRainbowUp(), windowMonths, "Up " + windowMonths + "m");
        RainbowPreset down = withWindow(a, a.getRainbowDown(), windowMonths, "Down " + windowMonths + "m");
        affectationService.assign(a.getUser(), ASSET, a.getTrendPreset().getId(), up.getId(), down.getId());
    }

    @Test
    @DisplayName("Changement des presets : la passe suivante ré-amorce l'état (== rejeu avec la nouvelle config), actions passées inchangées, événement LIVE_SWITCH une seule fois")
    void presetChangeReseedsState() {
        AssetAffectation follower = followerOf("bob");
        for (int i = FIRST; i <= FIRST + 5; i++) {
            pass2355(i);
        }
        RainbowLiveRun before = runRepository.findByAffectationAndDay(follower, day(FIRST + 5)).orElseThrow();
        String oldHash = stateRepository.findByAffectationAndDay(follower, day(FIRST + 5)).orElseThrow().getConfigHash();
        String oldRunHash = before.getConfigHash();
        double oldClose = before.getSignal().getClose();
        var oldAction = before.getSignal().getActionType();
        assertNotNull(oldHash);

        changeWindow(follower, 3);
        pass2355(FIRST + 6);

        RainbowLiveEngineState state = stateRepository.findByAffectationAndDay(follower, day(FIRST + 6)).orElseThrow();
        assertTrue(!oldHash.equals(state.getConfigHash()), "l'état porte le hash de la nouvelle config effective");
        assertEquals(configResolver.effectiveHash(affectationService.get(follower.getUser(), follower.getId())), state.getConfigHash());
        assertEquals(state.getConfigHash(),
                stateRepository.findByAffectationAndDay(follower, day(FIRST + 5)).orElseThrow().getConfigHash(),
                "la veille a été ré-amorcée avec la nouvelle config");

        // état ré-amorcé == état rejoué avec la nouvelle config (fenêtre 3 mois)
        int last = FIRST + 6;
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles.subList(0, last + 1));
        TrendMixCalculator.Params tp = TrendMixCalculator.Params.defaults();
        int[] setOfBar = RainbowSetSelector.select(
                TrendMixCalculator.compute(candles.subList(0, last + 1), ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime(),
                RainbowSetSelector.RangeMapping.KEEP_PREVIOUS);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull(ASSET).toArray(new RainbowAtrParamSet[0]);
        long from = day(last).minusMonths(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int startIdx = 0;
        while (ds.time(startIdx) < from) {
            startIdx++;
        }
        RainbowAtrState replayed = RainbowAtrReplay.runFull(ds, sets, setOfBar, startIdx, last).finalState();
        RainbowAtrState persisted = state.toState();
        assertEquals(replayed.buyArmed(), persisted.buyArmed());
        assertEquals(replayed.sellArmed(), persisted.sellArmed());
        assertEquals(replayed.buyLocked(), persisted.buyLocked());
        assertEquals(replayed.buyArmedDays(), persisted.buyArmedDays());
        assertEquals(replayed.sellArmedDays(), persisted.sellArmedDays());
        assertEquals(replayed.cooldown(), persisted.cooldown());
        assertEquals(replayed.moon().active(), persisted.moon().active());

        // run de la veille : rafraîchi (config effective) mais action et clôture inchangées ; plus ancien : intact
        RainbowLiveRun after = runRepository.findByAffectationAndDay(follower, day(FIRST + 5)).orElseThrow();
        assertEquals(oldAction, after.getSignal().getActionType());
        assertEquals(oldClose, after.getSignal().getClose(), 0.0);
        assertEquals(oldRunHash, runRepository.findByAffectationAndDay(follower, day(FIRST + 4)).orElseThrow().getConfigHash());

        // historique : une seule fois pour la révision 2, même si on rejoue
        pass2355(FIRST + 6);
        pass2355(FIRST + 7);
        var changed = eventService.list(follower.getUser(), ASSET).stream()
                .filter(e -> e.getType() == RainbowLivePresetEventType.LIVE_SWITCH).toList();
        assertEquals(1, changed.size());
    }

    @Test
    @DisplayName("L'Affectation d'un utilisateur n'est pas ré-amorcée par le changement de presets d'un autre")
    void ownPresetNotReseeded() {
        AssetAffectation follower = followerOf("carol");
        for (int i = FIRST; i <= FIRST + 5; i++) {
            pass2355(i);
        }
        String ownHash = stateRepository.findByAffectationAndDay(btc, day(FIRST + 5)).orElseThrow().getConfigHash();

        changeWindow(follower, 3);
        pass2355(FIRST + 6);

        assertEquals(ownHash, stateRepository.findByAffectationAndDay(btc, day(FIRST + 5)).orElseThrow().getConfigHash());
        assertEquals(ownHash, stateRepository.findByAffectationAndDay(btc, day(FIRST + 6)).orElseThrow().getConfigHash(),
                "le preset perso continue sans ré-amorçage");
    }

    // --- Trou de passes (service arrêté, série sautée) : l'état doit être resynchronisé, jamais figé

    /** État final du rejeu de la fenêtre de {@code windowMonths} mois précédant le jour {@code anchor}, jusqu'au jour {@code last} (inclus). */
    private RainbowAtrState replayedState(int last, int anchor, int windowMonths) {
        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles.subList(0, last + 1));
        TrendMixCalculator.Params tp = TrendMixCalculator.Params.defaults();
        int[] setOfBar = RainbowSetSelector.select(
                TrendMixCalculator.compute(candles.subList(0, last + 1), ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime(),
                RainbowSetSelector.RangeMapping.KEEP_PREVIOUS);
        RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull(ASSET).toArray(new RainbowAtrParamSet[0]);
        long from = day(anchor).minusMonths(windowMonths).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int startIdx = 0;
        while (ds.time(startIdx) < from) {
            startIdx++;
        }
        return RainbowAtrReplay.runFull(ds, sets, setOfBar, startIdx, last).finalState();
    }

    private void assertSameMachine(RainbowAtrState expected, RainbowAtrState actual) {
        assertEquals(expected.buyArmed(), actual.buyArmed());
        assertEquals(expected.sellArmed(), actual.sellArmed());
        assertEquals(expected.buyLocked(), actual.buyLocked());
        assertEquals(expected.buyArmedDays(), actual.buyArmedDays());
        assertEquals(expected.sellArmedDays(), actual.sellArmedDays());
        assertEquals(expected.cooldown(), actual.cooldown());
        assertEquals(expected.moon().active(), actual.moon().active());
    }

    private long resyncEntries() {
        return adminLog.findAllByOrderByIdDesc(org.springframework.data.domain.Pageable.unpaged()).getContent().stream().filter(e -> e.getAction() == AdminAction.RUN
                && e.getTarget().startsWith(RainbowLiveDefaultPresets.RESYNC_TARGET_PREFIX + btc.getId())).count();
    }

    @Test
    @DisplayName("Trou de passes : la passe suivante ré-amorce l'état (== rejeu continu), trace au journal admin, aucun run pour les jours manqués")
    void gapTriggersResync() {
        for (int i = FIRST; i <= FIRST + 5; i++) {
            pass2355(i);
        }
        assertEquals(0, resyncEntries(), "passes continues : aucune resynchronisation");

        int resumed = FIRST + 26; // 20 jours sans passe
        pass2355(resumed);

        assertSameMachine(replayedState(resumed, resumed, 6), stateRepository.findByAffectationAndDay(btc, day(resumed)).orElseThrow().toState());
        assertTrue(stateRepository.findByAffectationAndDay(btc, day(resumed - 1)).isPresent(), "état de la veille resynchronisé");
        assertEquals(1, resyncEntries(), "le trou est tracé au journal admin");
        for (int i = FIRST + 6; i < resumed; i++) {
            assertTrue(runRepository.findByAffectationAndDay(btc, day(i)).isEmpty(), "aucun run rétroactif, jour " + i);
        }
        assertTrue(runRepository.findByAffectationAndDay(btc, day(resumed)).isPresent());

        pass2355(resumed + 1);
        assertEquals(1, resyncEntries(), "plus de trou : pas de nouvelle resynchronisation");
    }

    @Test
    @DisplayName("Resync forcée / au démarrage : état ramené à la veille sans run ni ordre ; sans force, un état à jour n'est pas recalculé")
    void resyncBringsStateToYesterday() {
        for (int i = FIRST; i <= FIRST + 5; i++) {
            pass2355(i);
        }
        int today = FIRST + 30;
        Instant now = day(today).atTime(10, 0).toInstant(ZoneOffset.UTC);

        var first = service.resync(now, btc.getId(), false, "test", "démarrage");
        assertEquals(day(today - 1), first.toDay());
        assertEquals(1, first.resynced());
        assertSameMachine(replayedState(today - 1, today, 6), stateRepository.findByAffectationAndDay(btc, day(today - 1)).orElseThrow().toState());
        assertTrue(runRepository.findByAffectationAndDay(btc, day(today - 1)).isEmpty(), "aucun run créé");
        assertEquals(1, resyncEntries());

        var second = service.resync(now, btc.getId(), false, "test", "démarrage");
        assertEquals(0, second.resynced());
        assertEquals(1, second.upToDate());

        var forced = service.resync(now, btc.getId(), true, "admin", "forcée");
        assertEquals(1, forced.resynced());
        assertEquals(2, resyncEntries());
    }
}
