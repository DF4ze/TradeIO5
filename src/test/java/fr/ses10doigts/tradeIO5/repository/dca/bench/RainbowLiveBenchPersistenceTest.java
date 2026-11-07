package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertBasePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertFixtures;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveRunService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({AssetAffectationService.class, ExpertFixtures.class, ExpertBasePresetService.class, RainbowLiveRunService.class,
        AffectationConfigResolver.class, RainbowLivePresetEventService.class, RainbowLiveBenchPersistenceTest.ClockConfig.class})
@DisplayName("Espace Expert : presets de base, Affectations, runs")
class RainbowLiveBenchPersistenceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-03T23:55:00Z"));
        }
    }

    @Autowired private ExpertFixtures fixtures;
    @Autowired private ExpertBasePresetService basePresetService;
    @Autowired private AssetAffectationService affectationService;
    @Autowired private RainbowLiveRunService runService;
    @Autowired private TrendPresetRepository trendRepository;
    @Autowired private RainbowPresetRepository rainbowRepository;
    @Autowired private AssetAffectationRepository affectationRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private RainbowLiveEngineStateRepository stateRepository;
    @Autowired private RainbowLivePresetEventService eventService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private User alice;
    private User bob;

    @BeforeEach
    void users() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
    }

    private AssetAffectation btc(User user, String name) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        return fixtures.affectation(user, "BTC", name, c.toTuning(), c.toGlobals());
    }

    private static RainbowLivePassBlock block(RainbowLiveAction type, Double amount, Double qty, double close) {
        return RainbowLivePassBlock.builder().close(close).sma(close).atr(1.0).zone(2)
                .actionType(type).actionAmountUsdc(amount).actionQuantity(qty).actionPrice(close).build();
    }

    // --- Presets de base

    @Test
    @DisplayName("Presets de base : 9 presets système (1 Trend + 2 Rainbow × 3 actifs), idempotent, jamais modifiés")
    void basePresets() {
        assertEquals(9, basePresetService.ensureBasePresets());
        assertEquals(0, basePresetService.ensureBasePresets(), "2e démarrage : aucun doublon");
        assertEquals(3, trendRepository.count());
        assertEquals(6, rainbowRepository.count());
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            TrendPreset t = trendRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.TREND).orElseThrow();
            assertEquals(PresetOrigin.SYSTEM, t.getOrigin());
            assertTrue(t.getOwner() == null);
            assertEquals(PresetOrigin.SYSTEM, rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BULL).orElseThrow().getOrigin());
            assertNotNull(rainbowRepository.findByAssetSymbolAndBaseRole(asset, BasePresetRole.RAINBOW_BEAR).orElseThrow());
        }

        TrendPreset btcTrend = trendRepository.findByAssetSymbolAndBaseRole("BTC", BasePresetRole.TREND).orElseThrow();
        btcTrend.setSlopeScale(123.0);
        trendRepository.saveAndFlush(btcTrend);
        em.clear();
        assertEquals(0, basePresetService.ensureBasePresets());
        assertEquals(123.0, trendRepository.findByAssetSymbolAndBaseRole("BTC", BasePresetRole.TREND).orElseThrow().getSlopeScale());
    }

    @Test
    @DisplayName("Unicité des presets système par (actif, rôle de base)")
    void baseRoleUnique() {
        basePresetService.ensureBasePresets();
        RainbowPreset dup = RainbowPreset.builder().assetSymbol("BTC").name("Doublon").origin(PresetOrigin.SYSTEM)
                .baseRole(BasePresetRole.RAINBOW_BULL).revision(1).analysisWindowMonths(6)
                .config(RainbowLiveDefaultPresets.configFor("BTC")).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
        assertThrows(DataIntegrityViolationException.class, () -> rainbowRepository.saveAndFlush(dup));
    }

    // --- Affectations

    @Test
    @DisplayName("Affectations : une par actif sur les presets de base (inactives, complètes), idempotent, users indépendants")
    void ensureAffectations() {
        basePresetService.ensureBasePresets();
        List<AssetAffectation> created = affectationService.ensureAffectations(alice);
        assertEquals(3, created.size());
        for (AssetAffectation a : created) {
            assertTrue(a.isComplete());
            assertFalse(a.isEnabled());
            assertEquals(PresetOrigin.SYSTEM, a.getTrendPreset().getOrigin());
        }
        assertTrue(affectationService.ensureAffectations(alice).isEmpty(), "2e appel : pas de doublon");
        assertEquals(3, affectationService.ensureAffectations(bob).size(), "bob indépendant d'alice");
        assertEquals(List.of("BTC", "ETH", "PAXG"), affectationService.list(alice).stream().map(AssetAffectation::getAssetSymbol).toList());
    }

    @Test
    @DisplayName("Sans presets de base : aucune Affectation créée (pas d'Affectation incomplète)")
    void noBasePresetsNoAffectation() {
        assertTrue(affectationService.ensureAffectations(alice).isEmpty());
        assertEquals(0, affectationRepository.count());
    }

    @Test
    @DisplayName("Unicité (user, actif) en base ; l'Affectation d'un user n'est pas visible d'un autre")
    void affectationUnique() {
        AssetAffectation a = btc(alice, "Bench");
        AssetAffectation dup = AssetAffectation.builder().user(alice).assetSymbol("BTC").trendPreset(a.getTrendPreset())
                .rainbowUp(a.getRainbowUp()).rainbowDown(a.getRainbowDown()).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH).build();
        assertThrows(DataIntegrityViolationException.class, () -> affectationRepository.saveAndFlush(dup));
        em.clear();

        assertThrows(RuntimeException.class, () -> affectationService.get(bob, a.getId()));
        assertEquals(a.getId(), affectationService.get(alice, a.getId()).getId());
    }

    @Test
    @DisplayName("assign : presets installés, état moteur purgé, événement LIVE_SWITCH ; identique => rien ; autre actif / autre user refusés")
    void assign() {
        basePresetService.ensureBasePresets();
        AssetAffectation base = affectationService.forAsset(alice, "BTC");
        stateRepository.save(RainbowLiveEngineState.of(base, DAY, RainbowAtrState.initial()));
        em.flush();
        assertEquals(1, stateRepository.count());

        AssetAffectation same = affectationService.assign(alice, "BTC", base.getTrendPreset().getId(),
                base.getRainbowUp().getId(), base.getRainbowDown().getId());
        assertEquals(1, stateRepository.count(), "presets inchangés : état conservé");
        assertTrue(eventService.list(alice, "BTC").isEmpty());
        assertEquals(base.getId(), same.getId());

        RainbowPreset own = fixtures.affectation(bob, "BTC", "perso", RainbowLiveDefaultPresets.configFor("BTC").toTuning(),
                RainbowLiveDefaultPresets.configFor("BTC").toGlobals()).getRainbowUp();
        assertThrows(IllegalArgumentException.class, () -> affectationService.assign(alice, "BTC",
                base.getTrendPreset().getId(), own.getId(), base.getRainbowDown().getId()), "preset d'un autre user");
        RainbowPreset eth = rainbowRepository.findByAssetSymbolAndBaseRole("ETH", BasePresetRole.RAINBOW_BULL).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> affectationService.assign(alice, "BTC",
                base.getTrendPreset().getId(), eth.getId(), base.getRainbowDown().getId()), "preset d'un autre actif");

        RainbowPreset bear = base.getRainbowDown();
        AssetAffectation switched = affectationService.assign(alice, "BTC", base.getTrendPreset().getId(), bear.getId(), bear.getId());
        em.flush();
        assertEquals(base.getId(), switched.getId());
        assertEquals(bear.getId(), switched.getRainbowUp().getId());
        assertEquals(0, stateRepository.count(), "presets modifiés : état moteur purgé");
        assertEquals(List.of(RainbowLivePresetEventType.LIVE_SWITCH),
                eventService.list(alice, "BTC").stream().map(e -> e.getType()).toList());
    }

    // --- Runs

    @Test
    @DisplayName("Rejeu du même jour : 1 seule ligne, indicateurs remplacés, action d'origine conservée")
    void replaySameDay() {
        AssetAffectation p = btc(alice, "Bench");

        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsert(p, DAY, block(RainbowLiveAction.NONE, null, null, 100_500));
        em.flush();
        em.clear();

        assertEquals(1, runRepository.countByAffectation(p));
        RainbowLiveRun run = runRepository.findByAffectationAndDay(p, DAY).orElseThrow();
        assertEquals(100_500, run.getSignal().getClose(), 0.0);
        assertEquals(RainbowLiveAction.BUY, run.getSignal().getActionType());
        assertEquals(100.0, run.getSignal().getActionAmountUsdc(), 0.0);
        assertNotNull(run.getConfigHash());
    }

    @Test
    @DisplayName("Refresh du lendemain : indicateurs remplacés par la clôture, action figée ; sans run => false")
    void refreshKeepsAction() {
        AssetAffectation p = btc(alice, "Bench");
        assertFalse(runService.refresh(p, DAY, block(RainbowLiveAction.NONE, null, null, 100_000)), "pas de rattrapage");

        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        assertTrue(runService.refresh(p, DAY, block(RainbowLiveAction.NONE, null, null, 101_200)));
        em.flush();
        em.clear();

        RainbowLiveRun run = runRepository.findByAffectationAndDay(p, DAY).orElseThrow();
        assertEquals(1, runRepository.countByAffectation(p));
        assertEquals(101_200, run.getSignal().getClose(), 0.0);
        assertEquals(RainbowLiveAction.BUY, run.getSignal().getActionType());
        assertEquals(0.001, run.getSignal().getActionQuantity(), 1e-12);
    }

    @Test
    @DisplayName("Jours distincts => runs distincts")
    void severalDays() {
        AssetAffectation p = btc(alice, "Bench");

        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsert(p, DAY.plusDays(1), block(RainbowLiveAction.SELL, 60.0, 0.0006, 100_000));

        assertEquals(2, runRepository.countByAffectation(p));
    }

    @Test
    @DisplayName("Run : le hash et la config sont figés au jour du run, même si les presets sont modifiés ensuite")
    void runKeepsItsConfig() {
        AssetAffectation p = btc(alice, "Bench");
        RainbowLiveRun before = runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        String hashBefore = before.getConfigHash();

        RainbowPreset down = p.getRainbowDown();
        down.getConfig().setSmaPeriod(36);
        rainbowRepository.save(down);
        em.flush();
        em.clear();

        RainbowLiveRun run = runRepository.findByAffectationAndDay(p, DAY).orElseThrow();
        assertEquals(hashBefore, run.getConfigHash());
        assertEquals(50, run.getConfig().getSmaPeriod());
        assertNotEquals(36, run.getConfig().getSmaPeriod());
    }
}
