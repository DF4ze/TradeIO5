package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAthReference;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
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
@Import({RainbowAssetStrategyService.class, RainbowLivePresetConfigResolver.class, RainbowLivePresetEventService.class, RainbowLivePresetService.class, RainbowLiveRunService.class, RainbowLiveBindingRunService.class, RainbowLiveExecutionService.class,
        RainbowTrendLiveService.class, RainbowAthService.class, RainbowTrendLiveServiceTest.ClockConfig.class})
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
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveEngineStateRepository stateRepository;
    @Autowired private RainbowAthReferenceRepository athRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RainbowAssetStrategyService strategyService;
    @Autowired private RainbowLivePresetEventService eventService;
    @Autowired private RainbowLivePresetConfigResolver configResolver;

    private List<MarketData> candles;
    private RainbowLivePreset btc;

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
        btc = null;
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            RainbowLivePreset p = presetService.createTrendMix(alice, a, "Trend Mix", true, 6);
            if (a.equals(ASSET)) {
                btc = p;
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

        assertTrue(stateRepository.findByPresetAndDay(btc, day(FIRST - 1)).isPresent(), "état amorcé la veille");
        assertTrue(stateRepository.findByPresetAndDay(btc, day(FIRST)).isPresent(), "état du jour");
        RainbowAthReference ath = athRepository.findByAssetSymbolAndDay(ASSET, day(FIRST)).orElseThrow();
        double expected = candles.subList(0, FIRST + 1).stream().mapToDouble(c -> c.getHigh().doubleValue()).max().orElseThrow();
        assertEquals(expected, ath.getAthValue(), 1e-9);

        RainbowLiveRun run = runRepository.findByPresetAndDay(btc, day(FIRST)).orElseThrow();
        assertNotNull(run.getSignal().getActiveSet());
        assertTrue(run.getSignal().getActiveSet().startsWith("BTC Perso"));
        assertNotNull(run.getSignal().getTrendRegime());
    }

    @Test
    @DisplayName("Passe du lendemain : le run de la veille est rafraîchi sur la clôture finale (indicateurs, ATH, état), action figée")
    void nextDayRefreshesPreviousRun() {
        pass2355(FIRST);
        RainbowLiveRun first = runRepository.findByPresetAndDay(btc, day(FIRST)).orElseThrow();
        var action = first.getSignal().getActionType();
        double provisional = first.getSignal().getClose();
        double finalClose = provisional + 3;
        MarketData old = candles.get(FIRST);
        candles.set(FIRST, MarketData.builder().timeFrame(TimeFrame.D1).timestamp(old.getTimestamp()).pair(ASSET)
                .open(old.getOpen()).high(BigDecimal.valueOf(finalClose * 1.02)).low(old.getLow())
                .close(BigDecimal.valueOf(finalClose)).volume(BigDecimal.ONE).build());

        pass2355(FIRST + 1);

        RainbowLiveRun refreshed = runRepository.findByPresetAndDay(btc, day(FIRST)).orElseThrow();
        assertEquals(finalClose, refreshed.getSignal().getClose(), 1e-9);
        assertEquals(action, refreshed.getSignal().getActionType(), "l'action décidée à 23:55 reste figée");
        assertEquals(finalClose * 1.02, athRepository.findByAssetSymbolAndDay(ASSET, day(FIRST)).orElseThrow().getAthValue(), 1e-9);
        assertTrue(runRepository.findByPresetAndDay(btc, day(FIRST + 1)).isPresent());
    }

    @Test
    @DisplayName("Position virtuelle du moteur : persistée par jour, achats = montant/close, ventes = quantité, jamais négative")
    void virtualPositionIsPersisted() {
        int last = FIRST + 60;
        for (int i = FIRST; i <= last; i++) {
            pass2355(i);
        }
        double pos = stateRepository.findByPresetAndDay(btc, day(FIRST - 1)).orElseThrow().getPositionQty();
        for (int i = FIRST; i <= last; i++) {
            var sig = runRepository.findByPresetAndDay(btc, day(i)).orElseThrow().getSignal();
            double buy = sig.getActionType() == fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction.BUY
                    ? sig.getActionAmountUsdc() / sig.getClose() : 0;
            double sell = sig.getActionType() == fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction.SELL
                    ? sig.getActionQuantity() : 0;
            pos = Math.max(0, pos - sell + buy);
            assertEquals(pos, stateRepository.findByPresetAndDay(btc, day(i)).orElseThrow().getPositionQty(), 1e-9, "jour " + i);
        }
    }

    @Test
    @DisplayName("État persisté jour après jour == état du rejeu complet (hors réserve moon, liée à la position)")
    void persistedStateEqualsReplay() {
        int last = FIRST + 40;
        for (int i = FIRST; i <= last; i++) {
            pass2355(i);
        }
        RainbowAtrState persisted = stateRepository.findByPresetAndDay(btc, day(last)).orElseThrow().toState();

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

    // --- Presets qui suivent une stratégie Actif : un changement System est pris en compte à la passe suivante

    private RainbowLivePreset followerOf(String username) {
        User u = userRepository.save(User.builder().username(username).email(username + "@example.com").password("x").enabled(true).build());
        strategyService.ensureStrategies();
        RainbowLivePreset p = presetService.ensureStrategyPresets(u).stream()
                .filter(x -> x.getAssetSymbol().equals(ASSET)).findFirst().orElseThrow();
        return presetService.setEnabled(u, p.getId(), true);
    }

    private void changeStrategyWindow(RainbowLivePreset follower, int windowMonths) {
        var st = follower.getAssetStrategy();
        strategyService.update(st.getId(), new RainbowAssetStrategyService.UpdateRequest(windowMonths,
                RainbowLiveTrendConfigs.fromJson(st.getTrendConfigJson())));
    }

    @Test
    @DisplayName("Révision de la stratégie : la passe suivante ré-amorce l'état (== rejeu avec la nouvelle config), actions passées inchangées, événement STRATEGY_CHANGED une seule fois")
    void strategyChangeReseedsState() {
        RainbowLivePreset follower = followerOf("bob");
        for (int i = FIRST; i <= FIRST + 5; i++) {
            pass2355(i);
        }
        RainbowLiveRun before = runRepository.findByPresetAndDay(follower, day(FIRST + 5)).orElseThrow();
        String oldHash = stateRepository.findByPresetAndDay(follower, day(FIRST + 5)).orElseThrow().getConfigHash();
        String oldRunHash = before.getConfigHash();
        double oldClose = before.getSignal().getClose();
        var oldAction = before.getSignal().getActionType();
        assertNotNull(oldHash);

        changeStrategyWindow(follower, 3);
        pass2355(FIRST + 6);

        RainbowLiveEngineState state = stateRepository.findByPresetAndDay(follower, day(FIRST + 6)).orElseThrow();
        assertTrue(!oldHash.equals(state.getConfigHash()), "l'état porte le hash de la nouvelle config effective");
        assertEquals(configResolver.effectiveHash(presetService.get(follower.getUser(), follower.getId())), state.getConfigHash());
        assertEquals(state.getConfigHash(),
                stateRepository.findByPresetAndDay(follower, day(FIRST + 5)).orElseThrow().getConfigHash(),
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
        RainbowLiveRun after = runRepository.findByPresetAndDay(follower, day(FIRST + 5)).orElseThrow();
        assertEquals(oldAction, after.getSignal().getActionType());
        assertEquals(oldClose, after.getSignal().getClose(), 0.0);
        assertEquals(oldRunHash, runRepository.findByPresetAndDay(follower, day(FIRST + 4)).orElseThrow().getConfigHash());

        // historique : une seule fois pour la révision 2, même si on rejoue
        pass2355(FIRST + 6);
        pass2355(FIRST + 7);
        var changed = eventService.list(follower.getUser(), ASSET).stream()
                .filter(e -> e.getType() == RainbowLivePresetEventType.STRATEGY_CHANGED).toList();
        assertEquals(1, changed.size());
        assertEquals(2, changed.getFirst().getStrategyRevision());
    }

    @Test
    @DisplayName("Un preset perso n'est pas ré-amorcé par le changement de stratégie d'un autre")
    void ownPresetNotReseeded() {
        RainbowLivePreset follower = followerOf("carol");
        for (int i = FIRST; i <= FIRST + 5; i++) {
            pass2355(i);
        }
        String ownHash = stateRepository.findByPresetAndDay(btc, day(FIRST + 5)).orElseThrow().getConfigHash();

        changeStrategyWindow(follower, 3);
        pass2355(FIRST + 6);

        assertEquals(ownHash, stateRepository.findByPresetAndDay(btc, day(FIRST + 5)).orElseThrow().getConfigHash());
        assertEquals(ownHash, stateRepository.findByPresetAndDay(btc, day(FIRST + 6)).orElseThrow().getConfigHash(),
                "le preset perso continue sans ré-amorçage");
    }
}
