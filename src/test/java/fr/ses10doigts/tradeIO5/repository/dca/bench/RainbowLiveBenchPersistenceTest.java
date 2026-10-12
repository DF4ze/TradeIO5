package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAssetStrategy;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetFollowsStrategyException;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.UpdateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowAssetStrategyService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveRunService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({RainbowLivePresetService.class, RainbowAssetStrategyService.class, RainbowLiveRunService.class,
        fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver.class,
        fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService.class, RainbowLiveBenchPersistenceTest.ClockConfig.class})
@DisplayName("Live Rainbow : persistance, presets, runs")
class RainbowLiveBenchPersistenceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-03T23:55:00Z"));
        }
    }

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveRunService runService;
    @Autowired private RainbowLivePresetRepository presetRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private RainbowAssetStrategyService strategyService;
    @Autowired private fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver configResolver;
    @Autowired private fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService eventService;
    @Autowired private RainbowAssetStrategyRepository strategyRepository;
    @Autowired private RainbowLiveTrendConfigRepository trendConfigRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private User alice;
    private User bob;

    @BeforeEach
    void users() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
    }

    private RainbowLivePreset btcPreset(User user, String name) {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        return presetService.create(user, new CreateRequest("BTC", name, true, 6, c.toTuning(), c.toGlobals()));
    }

    private static RainbowLivePassBlock block(RainbowLiveAction type, Double amount, Double qty, double close) {
        return RainbowLivePassBlock.builder().close(close).sma(close).atr(1.0).zone(2)
                .actionType(type).actionAmountUsdc(amount).actionQuantity(qty).actionPrice(close).build();
    }

    // --- Repos / presets

    @Test
    @DisplayName("CRUD preset : persisté avec config typée")
    void createPersistsPreset() {
        RainbowLivePreset p = btcPreset(alice, "Mon preset");
        em.flush();
        em.clear();

        RainbowLivePreset loaded = presetRepository.findById(p.getId()).orElseThrow();
        assertEquals("BTC", loaded.getAssetSymbol());
        assertEquals(6, loaded.getAnalysisWindowMonths());
        assertEquals(RainbowLiveDefaultPresets.configFor("BTC"), loaded.getConfig());
        assertEquals(Instant.parse("2026-10-03T23:55:00Z"), loaded.getCreatedAt());
    }

    @Test
    @DisplayName("Unicité (user, actif, name) ; deux users distincts coexistent sans interférence")
    void uniquenessAndUserIsolation() {
        btcPreset(alice, "Bench");
        btcPreset(bob, "Bench");

        assertThrows(IllegalArgumentException.class, () -> btcPreset(alice, "Bench"));
        assertEquals(1, presetService.list(alice).size());
        assertEquals(1, presetService.list(bob).size());
        assertEquals(1, presetService.list(alice, "BTC").size());
        assertTrue(presetService.list(alice, "ETH").isEmpty());

        RainbowLivePreset dup = RainbowLivePreset.builder().user(alice).assetSymbol("BTC").name("Bench")
                .analysisWindowMonths(6).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .config(RainbowLiveDefaultPresets.configFor("BTC")).build();
        assertThrows(DataIntegrityViolationException.class, () -> presetRepository.saveAndFlush(dup));
    }

    @Test
    @DisplayName("Validation : Tuning invalide, fenêtre, baseAmount, actif non autorisé")
    void validation() {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        RainbowAtrTuning invalid = c.toTuning().toBuilder().set("atrMultDown2", 0.1).build(); // down2 < down1
        assertFalse(invalid.isValid());

        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 6, invalid, c.toGlobals())));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 0, c.toTuning(), c.toGlobals())));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 6, c.toTuning(), zeroBase(c))));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("SOL", "a", true, 6, c.toTuning(), c.toGlobals())));
        assertTrue(presetService.list(alice).isEmpty());
    }

    private static RainbowAtrGlobals zeroBase(RainbowAtrConfig c) {
        RainbowAtrConfig z = RainbowAtrConfig.of(c.toTuning(), c.toGlobals());
        z.setBaseAmount(0);
        return z.toGlobals();
    }

    @Test
    @DisplayName("Un utilisateur ne peut ni éditer ni supprimer le preset d'un autre")
    void ownership() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");

        assertThrows(IllegalArgumentException.class, () -> presetService.delete(bob, p.getId()));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.update(bob, p.getId(), new UpdateRequest("x", true, 6, c.toTuning(), c.toGlobals())));
    }

    // --- Runs

    @Test
    @DisplayName("Rejeu du même jour : 1 seule ligne, indicateurs remplacés, action d'origine conservée")
    void replaySameDay() {
        RainbowLivePreset p = btcPreset(alice, "Bench");

        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsert(p, DAY, block(RainbowLiveAction.NONE, null, null, 100_500));
        em.flush();
        em.clear();

        assertEquals(1, runRepository.countByPreset(p));
        RainbowLiveRun run = runRepository.findByPresetAndDay(p, DAY).orElseThrow();
        assertEquals(100_500, run.getSignal().getClose(), 0.0);
        assertEquals(RainbowLiveAction.BUY, run.getSignal().getActionType());
        assertEquals(100.0, run.getSignal().getActionAmountUsdc(), 0.0);
        assertNotNull(run.getConfigHash());
    }

    @Test
    @DisplayName("Refresh du lendemain : indicateurs remplacés par la clôture, action figée ; sans run => false")
    void refreshKeepsAction() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        assertFalse(runService.refresh(p, DAY, block(RainbowLiveAction.NONE, null, null, 100_000)), "pas de rattrapage");

        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        assertTrue(runService.refresh(p, DAY, block(RainbowLiveAction.NONE, null, null, 101_200)));
        em.flush();
        em.clear();

        RainbowLiveRun run = runRepository.findByPresetAndDay(p, DAY).orElseThrow();
        assertEquals(1, runRepository.countByPreset(p));
        assertEquals(101_200, run.getSignal().getClose(), 0.0);
        assertEquals(RainbowLiveAction.BUY, run.getSignal().getActionType());
        assertEquals(0.001, run.getSignal().getActionQuantity(), 1e-12);
    }

    @Test
    @DisplayName("Jours distincts => runs distincts")
    void severalDays() {
        RainbowLivePreset p = btcPreset(alice, "Bench");

        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsert(p, DAY.plusDays(1), block(RainbowLiveAction.SELL, 60.0, 0.0006, 100_000));

        assertEquals(2, runRepository.countByPreset(p));
    }

    @Test
    @DisplayName("Edit preset : identité et historique conservés, aucun run écrit ; hash du run inchangé, config modifiée visible")
    void editKeepsHistory() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        RainbowLiveRun before = runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        String hashBefore = before.getConfigHash();
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");

        RainbowLivePreset edited = presetService.update(alice, p.getId(), new UpdateRequest("Renommé", false, 12,
                c.toTuning().toBuilder().set("smaPeriod", 36).build(), c.toGlobals()));
        em.flush();
        em.clear();

        assertEquals(p.getId(), edited.getId());
        assertEquals("Renommé", edited.getName());
        assertEquals(36, edited.getConfig().getSmaPeriod());
        assertEquals(1, runRepository.countByPreset(p));
        RainbowLiveRun run = runRepository.findByPresetAndDay(p, DAY).orElseThrow();
        assertEquals(hashBefore, run.getConfigHash());
        assertEquals(50, run.getConfig().getSmaPeriod());
        assertNotEquals(edited.getConfig().hash(), run.getConfigHash());
    }

    @Test
    @DisplayName("Delete preset => cascade des runs, autres presets intacts")
    void deleteCascades() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        RainbowLivePreset other = btcPreset(alice, "Autre");
        runService.upsert(p, DAY, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsert(other, DAY, block(RainbowLiveAction.NONE, null, null, 100_000));
        em.flush();
        em.clear();

        presetService.delete(alice, p.getId());
        em.clear();

        assertTrue(presetRepository.findById(p.getId()).isEmpty());
        assertEquals(1, runRepository.count());
        assertEquals(other.getId(), runRepository.findAll().getFirst().getPreset().getId());
    }

    // --- Stratégies Actif + presets qui les suivent

    @Test
    @DisplayName("Stratégies : 1 TrendMix par actif (révision 1), idempotent, complète seulement les manquantes")
    void ensureStrategies() {
        assertEquals(3, strategyService.ensureStrategies().size());
        assertTrue(strategyService.ensureStrategies().isEmpty(), "2e démarrage : aucun doublon");
        assertEquals(3, strategyRepository.count());
        for (RainbowAssetStrategy st : strategyRepository.findAll()) {
            assertEquals(RainbowLiveDefaultPresets.TREND_MIX_NAME, st.getName());
            assertEquals(RainbowLiveMode.TREND_MIX, st.getMode());
            assertEquals(1, st.getRevision());
        }

        // une stratégie manquante est recréée seule, les autres ne sont pas touchées (jamais écrasées)
        em.createQuery("delete from RainbowAssetStrategy t where t.assetSymbol = 'ETH'").executeUpdate();
        em.clear();
        assertEquals(1, strategyService.ensureStrategies().size());
        assertEquals(3, strategyRepository.count());
    }

    @Test
    @DisplayName("Stratégie : modifiable par System (révision +1), jamais supprimable, jamais réécrite par l'Initializer")
    void strategyUpdatable() {
        strategyService.ensureStrategies();
        RainbowAssetStrategy btc = strategyRepository.findAllByOrderByAssetSymbolAscNameAsc().getFirst();
        var dto = RainbowLiveTrendConfigs.fromJson(btc.getTrendConfigJson());

        RainbowAssetStrategy updated = strategyService.update(btc.getId(),
                new fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowAssetStrategyService.UpdateRequest(9, dto));
        em.flush();
        em.clear();
        assertEquals(2, updated.getRevision());
        assertEquals(9, strategyRepository.findById(btc.getId()).orElseThrow().getAnalysisWindowMonths());

        assertTrue(strategyService.ensureStrategies().isEmpty());
        assertEquals(9, strategyRepository.findById(btc.getId()).orElseThrow().getAnalysisWindowMonths(),
                "l'Initializer ne réécrit jamais une stratégie existante");

        assertThrows(IllegalArgumentException.class, () -> strategyService.update(btc.getId(),
                new fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowAssetStrategyService.UpdateRequest(0, dto)));
        RainbowAssetStrategy again = strategyRepository.findById(btc.getId()).orElseThrow();
        assertThrows(RuntimeException.class, () -> strategyRepository.delete(again));
    }

    @Test
    @DisplayName("1re utilisation : un preset par stratégie qui la SUIT (inactif, nom préfixé, aucune copie de config), idempotent, par user")
    void ensureStrategyPresets() {
        strategyService.ensureStrategies();

        List<RainbowLivePreset> created = presetService.ensureStrategyPresets(alice);
        assertEquals(3, created.size());
        assertEquals(3, presetService.list(alice).size());
        assertEquals(0, trendConfigRepository.count(), "un preset qui suit la stratégie n'a pas de ligne Trend propre");
        for (RainbowLivePreset p : created) {
            assertTrue(p.isFollowingStrategy());
            assertTrue(p.isTrendMix());
            assertFalse(p.isEnabled(), "créé inactif");
            assertEquals(RainbowLiveDefaultPresets.SYSTEM_PREFIX + RainbowLiveDefaultPresets.TREND_MIX_NAME, p.getName());

            String asset = p.getAssetSymbol();
            RainbowLiveTrendConfigs.Resolved got = configResolver.trendConfig(p);
            RainbowLiveTrendConfigs.Resolved expected = RainbowLiveTrendConfigs.defaults(asset);
            assertEquals(expected.trend(), got.trend(), asset);
            assertEquals(expected.range(), got.range(), asset);
            assertEquals(expected.bear().tuning(), got.bear().tuning(), asset);
            assertEquals(expected.bull().globals(), got.bull().globals(), asset);
        }

        assertTrue(presetService.ensureStrategyPresets(alice).isEmpty(), "2e appel : pas de doublon");
        assertEquals(3, presetService.ensureStrategyPresets(bob).size(), "bob indépendant d'alice");
    }

    @Test
    @DisplayName("Preset qui suit une stratégie : ni modifiable ni supprimable, activation seule permise (et historisée) ; préfixe réservé")
    void followingPresetReadOnly() {
        strategyService.ensureStrategies();
        RainbowLivePreset sys = presetService.ensureStrategyPresets(alice).getFirst();
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");

        assertThrows(RainbowLivePresetFollowsStrategyException.class, () ->
                presetService.update(alice, sys.getId(), new UpdateRequest("x", true, 6, c.toTuning(), c.toGlobals())));
        assertThrows(RainbowLivePresetFollowsStrategyException.class, () -> presetService.delete(alice, sys.getId()));

        assertTrue(presetService.setEnabled(alice, sys.getId(), true).isEnabled());
        assertFalse(presetService.setEnabled(alice, sys.getId(), false).isEnabled());
        presetService.setEnabled(alice, sys.getId(), false);
        var types = eventService.list(alice, null).stream().map(e -> e.getType()).toList();
        assertEquals(List.of(RainbowLivePresetEventType.DISABLE, RainbowLivePresetEventType.ENABLE), types,
                "un setEnabled sans changement n'écrit rien");
        assertEquals(3, presetService.list(alice).size());

        assertThrows(IllegalArgumentException.class, () -> btcPreset(alice, RainbowLiveDefaultPresets.SYSTEM_PREFIX + "perso"));
        RainbowLivePreset own = btcPreset(alice, "perso");
        assertThrows(IllegalArgumentException.class, () -> presetService.update(alice, own.getId(),
                new UpdateRequest(RainbowLiveDefaultPresets.SYSTEM_PREFIX + "perso", true, 6, c.toTuning(), c.toGlobals())));
        assertFalse(own.isFollowingStrategy());
    }

    @Test
    @DisplayName("Modification System : config effective changée pour tous ceux qui suivent, aucun effet sur un preset détaché")
    void strategyChangeReachesFollowersNotDetached() {
        strategyService.ensureStrategies();
        RainbowLivePreset a = presetService.ensureStrategyPresets(alice).stream()
                .filter(p -> p.getAssetSymbol().equals("BTC")).findFirst().orElseThrow();
        RainbowLivePreset b = presetService.ensureStrategyPresets(bob).stream()
                .filter(p -> p.getAssetSymbol().equals("BTC")).findFirst().orElseThrow();
        var detached = presetService.create(alice, new CreateRequest("BTC", "perso", false, 6, null, null,
                RainbowLiveMode.TREND_MIX.name(), RainbowLiveTrendConfigs.toDto(configResolver.trendConfig(a)), a.getId()));
        String hashA = configResolver.effectiveHash(a);
        String hashDetached = configResolver.effectiveHash(detached);

        RainbowAssetStrategy btc = a.getAssetStrategy();
        strategyService.update(btc.getId(), new fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowAssetStrategyService
                .UpdateRequest(12, RainbowLiveTrendConfigs.fromJson(btc.getTrendConfigJson())));
        em.flush();
        em.clear();

        RainbowLivePreset a2 = presetService.get(alice, a.getId());
        RainbowLivePreset b2 = presetService.get(bob, b.getId());
        RainbowLivePreset d2 = presetService.get(alice, detached.getId());
        assertEquals(12, configResolver.analysisWindowMonths(a2));
        assertEquals(12, configResolver.analysisWindowMonths(b2));
        assertEquals(6, configResolver.analysisWindowMonths(d2));
        assertTrue(!hashA.equals(configResolver.effectiveHash(a2)));
        assertEquals(hashDetached, configResolver.effectiveHash(d2));
        assertTrue(d2.getAssetStrategy() == null && !d2.isFollowingStrategy());
    }

    @Test
    @DisplayName("Détachement = duplication d'un preset qui suit : nouveau wallet, ancien conservé, événement DETACH ; la duplication d'un preset perso n'écrit rien")
    void detachByDuplication() {
        strategyService.ensureStrategies();
        RainbowLivePreset followed = presetService.ensureStrategyPresets(alice).stream()
                .filter(p -> p.getAssetSymbol().equals("BTC")).findFirst().orElseThrow();
        var trend = RainbowLiveTrendConfigs.toDto(configResolver.trendConfig(followed));

        RainbowLivePreset copy = presetService.create(alice, new CreateRequest("BTC", "ma copie", false, 6, null, null,
                RainbowLiveMode.TREND_MIX.name(), trend, followed.getId()));
        assertFalse(copy.isFollowingStrategy());
        assertEquals(1, trendConfigRepository.count(), "la copie porte ses propres réglages Trend");

        var events = eventService.list(alice, "BTC");
        assertEquals(1, events.size());
        assertEquals(RainbowLivePresetEventType.DETACH, events.getFirst().getType());
        assertEquals(followed.getId(), events.getFirst().getPresetBeforeId());
        assertEquals(copy.getId(), events.getFirst().getPresetAfterId());
        assertEquals(1, events.getFirst().getStrategyRevision());

        presetService.create(alice, new CreateRequest("BTC", "copie de la copie", false, 6, null, null,
                RainbowLiveMode.TREND_MIX.name(), trend, copy.getId()));
        assertEquals(1, eventService.list(alice, "BTC").size(), "dupliquer un preset perso n'est pas un détachement");
        assertTrue(eventService.list(bob, null).isEmpty(), "historique propre à chaque user");
    }

    @Test
    @DisplayName("Sans stratégie en base : aucun preset créé (le user garde ses presets perso)")
    void noStrategyNoPreset() {
        btcPreset(alice, "perso");
        assertTrue(presetService.ensureStrategyPresets(alice).isEmpty());
        assertEquals(1, presetService.list(alice).size());
    }
}
