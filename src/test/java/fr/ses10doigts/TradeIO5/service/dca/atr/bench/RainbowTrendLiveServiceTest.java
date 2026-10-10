package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAthReference;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
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
@Import({RainbowLivePresetService.class, RainbowLiveRunService.class, RainbowLiveExecutionService.class,
        RainbowTrendLiveService.class, MockPortfolioSource.class, RainbowAthService.class, RainbowTrendLiveServiceTest.ClockConfig.class})
@DisplayName("Bench grandeur nature Rainbow : presets Trend Mix (état persisté, ATH en base)")
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
    /** Aucun binding : tous les presets restent en simulation (liveSlots vide). */
    @MockBean private RealPortfolioSource realPortfolioSource;
    @MockBean private BinanceDailyCandleFetcher fetcher;
    @Autowired private RainbowLiveExecutionService service;
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveEngineStateRepository stateRepository;
    @Autowired private RainbowAthReferenceRepository athRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private UserRepository userRepository;

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
        when(datasetEngine.getDatasetForAsset(anyString(), eq(TimeFrame.D1), anyInt(), any(Instant.class)))
                .thenAnswer(inv -> {
                    Instant end = inv.getArgument(3);
                    List<MarketData> visible = candles.stream().filter(c -> !c.getTimestamp().isAfter(end)).toList();
                    return MarketDataset.builder().pair(inv.getArgument(0)).timeFrame(TimeFrame.D1).marketDatas(visible)
                            .size(visible.size()).isComplete(true).build();
                });
        User alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        btc = null;
        for (String a : RainbowLiveDefaultPresets.ASSETS) {
            RainbowLivePreset p = presetService.createTrendMix(alice, a, "Trend Mix", true, 6, 1000);
            if (a.equals(ASSET)) {
                btc = p;
            }
        }
    }

    private LocalDate day(int idx) {
        return START.plusDays(idx);
    }

    private void pass2355(int idx) {
        service.runPass(RainbowLivePass.T2355, day(idx).atTime(23, 55).toInstant(ZoneOffset.UTC), null);
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
        assertNotNull(run.getPass2355().getActiveSet());
        assertTrue(run.getPass2355().getActiveSet().startsWith("BTC Perso"));
        assertNotNull(run.getPass2355().getTrendRegime());
    }

    @Test
    @DisplayName("Passe 00:05 : recalcul sur la clôture finale sans écrire ni état ni ATH")
    void pass0005WritesNothing() {
        pass2355(FIRST);
        long states = stateRepository.count();
        long aths = athRepository.count();

        service.runPass(RainbowLivePass.T0005, day(FIRST + 1).atTime(0, 5).toInstant(ZoneOffset.UTC), null);

        assertEquals(states, stateRepository.count());
        assertEquals(aths, athRepository.count());
        RainbowLiveRun run = runRepository.findByPresetAndDay(btc, day(FIRST)).orElseThrow();
        assertNotNull(run.getPass0005());
        assertNotNull(run.getPass0005().getActiveSet());
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
}
