package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMockWallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetTemplate;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetLockedException;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.UpdateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetTemplateService;
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
@Import({RainbowLivePresetService.class, RainbowLivePresetTemplateService.class, RainbowLiveRunService.class, RainbowLiveBenchPersistenceTest.ClockConfig.class})
@DisplayName("Bench grandeur nature Rainbow : persistance, presets, runs")
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
    @Autowired private RainbowLiveMockWalletRepository walletRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private RainbowLivePresetTemplateService templateService;
    @Autowired private RainbowLivePresetTemplateRepository templateRepository;
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
        return presetService.create(user, new CreateRequest("BTC", name, true, 6, 1000, c.toTuning(), c.toGlobals()));
    }

    private static RainbowLivePassBlock block(RainbowLiveAction type, Double amount, Double qty, double close) {
        return RainbowLivePassBlock.builder().close(close).sma(close).atr(1.0).zone(2)
                .actionType(type).actionAmountUsdc(amount).actionQuantity(qty).actionPrice(close).build();
    }

    // --- Repos / presets

    @Test
    @DisplayName("CRUD preset : persisté avec config typée, wallet mock initialisé au capital")
    void createPersistsPresetAndWallet() {
        RainbowLivePreset p = btcPreset(alice, "Mon preset");
        em.flush();
        em.clear();

        RainbowLivePreset loaded = presetRepository.findById(p.getId()).orElseThrow();
        assertEquals("BTC", loaded.getAssetSymbol());
        assertEquals(6, loaded.getAnalysisWindowMonths());
        assertEquals(RainbowLiveDefaultPresets.configFor("BTC"), loaded.getConfig());
        RainbowLiveMockWallet w = walletRepository.findByPreset(loaded).orElseThrow();
        assertEquals(1000, w.cash(), 0.0);
        assertEquals(0.0, w.quantity("BTC"), 0.0);
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
                .analysisWindowMonths(6).initialCapitalUsdc(1).createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .config(RainbowLiveDefaultPresets.configFor("BTC")).build();
        assertThrows(DataIntegrityViolationException.class, () -> presetRepository.saveAndFlush(dup));
    }

    @Test
    @DisplayName("Validation : Tuning invalide, fenêtre, capital, baseAmount, actif non autorisé")
    void validation() {
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        RainbowAtrTuning invalid = c.toTuning().toBuilder().set("atrMultDown2", 0.1).build(); // down2 < down1
        assertFalse(invalid.isValid());

        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 6, 1000, invalid, c.toGlobals())));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 0, 1000, c.toTuning(), c.toGlobals())));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 6, 0, c.toTuning(), c.toGlobals())));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("BTC", "a", true, 6, 1000, c.toTuning(), zeroBase(c))));
        assertThrows(IllegalArgumentException.class, () ->
                presetService.create(alice, new CreateRequest("SOL", "a", true, 6, 1000, c.toTuning(), c.toGlobals())));
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
    @DisplayName("Idempotence : rejeu 23:55 ⇒ 1 seule ligne, wallet non doublé, action d'origine conservée")
    void replay2355() {
        RainbowLivePreset p = btcPreset(alice, "Bench");

        runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_500));
        em.flush();
        em.clear();

        assertEquals(1, runRepository.countByPreset(p));
        RainbowLiveMockWallet w = walletRepository.findByPreset(p).orElseThrow();
        assertEquals(900, w.getCashUsdc(), 1e-9);
        assertEquals(0.001, w.getPositionQuantity(), 1e-12);
        RainbowLiveRun run = runRepository.findByPresetAndDay(p, DAY).orElseThrow();
        assertEquals(100_500, run.getPass2355().getClose(), 0.0); // indicateurs rejoués
        assertEquals(RainbowLiveAction.BUY, run.getPass2355().getActionType());
        assertEquals(900, run.getPass2355().getCashAfter(), 1e-9);
        assertEquals(0.001, run.getPass2355().getPositionAfter(), 1e-12);
        assertNotNull(run.getConfigHash());
        assertNull(run.getPass0005());
    }

    @Test
    @DisplayName("00:05 n'altère ni le bloc 23:55 ni le wallet ; deltaActionDiffers calculé ; rejeu 00:05 idempotent")
    void pass0005Independent() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));

        runService.upsertPass(p, DAY, RainbowLivePass.T0005, block(RainbowLiveAction.NONE, null, null, 101_000));
        runService.upsertPass(p, DAY, RainbowLivePass.T0005, block(RainbowLiveAction.NONE, null, null, 101_200));
        em.flush();
        em.clear();

        RainbowLiveRun run = runRepository.findByPresetAndDay(p, DAY).orElseThrow();
        assertEquals(1, runRepository.countByPreset(p));
        assertEquals(100_000, run.getPass2355().getClose(), 0.0);
        assertEquals(RainbowLiveAction.BUY, run.getPass2355().getActionType());
        assertEquals(101_200, run.getPass0005().getClose(), 0.0);
        assertNull(run.getPass0005().getCashAfter());
        assertTrue(run.deltaActionDiffers());
        assertEquals(900, walletRepository.findByPreset(p).orElseThrow().getCashUsdc(), 1e-9);
    }

    @Test
    @DisplayName("deltaActionDiffers faux si actions identiques")
    void deltaSame() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        RainbowLiveRun run = runService.upsertPass(p, DAY, RainbowLivePass.T0005,
                block(RainbowLiveAction.BUY, 100.0, 0.001, 100_010));

        assertFalse(run.deltaActionDiffers());
    }

    @Test
    @DisplayName("Bloc 00:05 sans 23:55 toléré (pas de mise à jour du wallet)")
    void pass0005Alone() {
        RainbowLivePreset p = btcPreset(alice, "Bench");

        runService.upsertPass(p, DAY, RainbowLivePass.T0005, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        em.flush();
        em.clear();

        RainbowLiveRun run = runRepository.findByPresetAndDay(p, DAY).orElseThrow();
        assertNull(run.getPass2355());
        assertNotNull(run.getPass0005());
        assertFalse(run.deltaActionDiffers());
        assertEquals(1000, walletRepository.findByPreset(p).orElseThrow().getCashUsdc(), 0.0);
    }

    @Test
    @DisplayName("Achat 23:55 sans cash refusé : rien n'est persisté ni débité")
    void buyWithoutCashRefused() {
        RainbowLivePreset p = btcPreset(alice, "Bench");

        assertThrows(IllegalStateException.class, () ->
                runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 5000.0, 0.05, 100_000)));
        assertEquals(1000, walletRepository.findByPreset(p).orElseThrow().getCashUsdc(), 0.0);
    }

    @Test
    @DisplayName("Jours distincts ⇒ runs distincts ; le wallet cumule les actions 23:55 de chaque jour")
    void severalDays() {
        RainbowLivePreset p = btcPreset(alice, "Bench");

        runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsertPass(p, DAY.plusDays(1), RainbowLivePass.T2355, block(RainbowLiveAction.SELL, 60.0, 0.0006, 100_000));

        assertEquals(2, runRepository.findByPresetOrderByDayAsc(p).size());
        RainbowLiveMockWallet w = walletRepository.findByPreset(p).orElseThrow();
        assertEquals(960, w.getCashUsdc(), 1e-9);
        assertEquals(0.0004, w.getPositionQuantity(), 1e-12);
    }

    @Test
    @DisplayName("Edit preset : identité, wallet et historique conservés, aucun run écrit ; hash du run inchangé, config modifiée visible")
    void editKeepsHistory() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        RainbowLiveRun before = runService.upsertPass(p, DAY, RainbowLivePass.T2355,
                block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
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
        assertEquals(900, walletRepository.findByPreset(p).orElseThrow().getCashUsdc(), 1e-9);
        assertEquals(1000, edited.getInitialCapitalUsdc(), 0.0);
    }

    @Test
    @DisplayName("Delete preset ⇒ cascade complète (wallet mock + runs), autres presets intacts")
    void deleteCascades() {
        RainbowLivePreset p = btcPreset(alice, "Bench");
        RainbowLivePreset other = btcPreset(alice, "Autre");
        runService.upsertPass(p, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.BUY, 100.0, 0.001, 100_000));
        runService.upsertPass(p, DAY, RainbowLivePass.T0005, block(RainbowLiveAction.NONE, null, null, 100_100));
        runService.upsertPass(other, DAY, RainbowLivePass.T2355, block(RainbowLiveAction.NONE, null, null, 100_000));
        em.flush();
        em.clear();

        presetService.delete(alice, p.getId());
        em.clear();

        assertTrue(presetRepository.findById(p.getId()).isEmpty());
        assertEquals(1, walletRepository.count());
        assertEquals(1, runRepository.count());
        assertEquals(other.getId(), runRepository.findAll().getFirst().getPreset().getId());
    }

    // --- Templates système + copie par user

    @Test
    @DisplayName("Templates : 2 par actif (Bench global FIXED + Trend Mix), idempotent, complète seulement les manquants")
    void ensureTemplates() {
        assertEquals(6, templateService.ensureTemplates().size());
        assertTrue(templateService.ensureTemplates().isEmpty(), "2e démarrage : aucun doublon");
        assertEquals(6, templateRepository.count());

        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            RainbowLivePresetTemplate bench = templateRepository.findAllByOrderByAssetSymbolAscNameAsc().stream()
                    .filter(t -> t.getAssetSymbol().equals(asset) && t.getMode() == RainbowLiveMode.FIXED)
                    .findFirst().orElseThrow();
            assertEquals(RainbowLiveDefaultPresets.DEFAULT_NAME, bench.getName());
            assertEquals(RainbowLiveDefaultPresets.configFor(asset), bench.getConfig(), asset);
            assertEquals(null, bench.getTrendConfigJson());
        }

        // un template manquant (suppression en masse, hors callbacks) est recréé seul, les autres ne sont pas touchés
        em.createQuery("delete from RainbowLivePresetTemplate t where t.assetSymbol = 'ETH'").executeUpdate();
        em.clear();
        assertEquals(2, templateService.ensureTemplates().size());
        assertEquals(6, templateRepository.count());
    }

    @Test
    @DisplayName("Templates : immuables (update et delete refusés par l'entité)")
    void templatesImmutable() {
        templateService.ensureTemplates();
        em.flush();
        em.clear();

        RainbowLivePresetTemplate t = templateRepository.findAll().getFirst();
        ReflectionTestUtils.setField(t, "name", "autre");
        assertThrows(RuntimeException.class, () -> em.flush());
        em.clear();

        RainbowLivePresetTemplate t2 = templateRepository.findAll().getFirst();
        assertThrows(RuntimeException.class, () -> templateRepository.delete(t2));
    }

    @Test
    @DisplayName("1re utilisation : copie des 6 templates (inactifs, système, nom préfixé, wallet au capital), idempotent, par user")
    void ensureSystemPresets() {
        templateService.ensureTemplates();

        List<RainbowLivePreset> created = presetService.ensureSystemPresets(alice);
        assertEquals(6, created.size());
        assertEquals(6, presetService.list(alice).size());
        assertEquals(6, walletRepository.count());
        for (RainbowLivePreset p : created) {
            assertTrue(p.isSystem());
            assertFalse(p.isEnabled(), "copie créée inactive");
            assertTrue(p.getName().startsWith(RainbowLiveDefaultPresets.SYSTEM_PREFIX), p.getName());
            assertEquals(1000, walletRepository.findByPreset(p).orElseThrow().cash(), 0.0);
        }
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            RainbowLivePreset bench = presetService.list(alice, asset).stream().filter(p -> !p.isTrendMix()).findFirst().orElseThrow();
            assertEquals(RainbowLiveDefaultPresets.SYSTEM_PREFIX + RainbowLiveDefaultPresets.DEFAULT_NAME, bench.getName());
            assertEquals(RainbowLiveDefaultPresets.configFor(asset), bench.getConfig(), asset);

            RainbowLivePreset trend = presetService.list(alice, asset).stream().filter(RainbowLivePreset::isTrendMix).findFirst().orElseThrow();
            assertEquals(RainbowLiveDefaultPresets.SYSTEM_PREFIX + RainbowLiveDefaultPresets.TREND_MIX_NAME, trend.getName());
            RainbowLiveTrendConfigs.Resolved got = RainbowLiveTrendConfigs.resolve(
                    trendConfigRepository.findByPreset(trend).orElseThrow(), asset);
            RainbowLiveTrendConfigs.Resolved expected = RainbowLiveTrendConfigs.defaults(asset);
            assertEquals(expected.trend(), got.trend(), asset);
            assertEquals(expected.range(), got.range(), asset);
            assertEquals(expected.bear().tuning(), got.bear().tuning(), asset);
            assertEquals(expected.bull().globals(), got.bull().globals(), asset);
        }

        assertTrue(presetService.ensureSystemPresets(alice).isEmpty(), "2e appel : pas de doublon");
        assertEquals(6, presetService.ensureSystemPresets(bob).size(), "bob indépendant d'alice");
        assertEquals(12, walletRepository.count());
    }

    @Test
    @DisplayName("Copie système : ni modifiable ni supprimable, activation seule permise ; préfixe réservé")
    void systemPresetLocked() {
        templateService.ensureTemplates();
        RainbowLivePreset sys = presetService.ensureSystemPresets(alice).getFirst();
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");

        assertThrows(RainbowLivePresetLockedException.class, () ->
                presetService.update(alice, sys.getId(), new UpdateRequest("x", true, 6, c.toTuning(), c.toGlobals())));
        assertThrows(RainbowLivePresetLockedException.class, () -> presetService.delete(alice, sys.getId()));

        assertTrue(presetService.setEnabled(alice, sys.getId(), true).isEnabled());
        assertFalse(presetService.setEnabled(alice, sys.getId(), false).isEnabled());
        assertEquals(6, presetService.list(alice).size());

        assertThrows(IllegalArgumentException.class, () -> btcPreset(alice, RainbowLiveDefaultPresets.SYSTEM_PREFIX + "perso"));
        RainbowLivePreset own = btcPreset(alice, "perso");
        assertThrows(IllegalArgumentException.class, () -> presetService.update(alice, own.getId(),
                new UpdateRequest(RainbowLiveDefaultPresets.SYSTEM_PREFIX + "perso", true, 6, c.toTuning(), c.toGlobals())));
        assertFalse(own.isSystem());
    }

    @Test
    @DisplayName("Sans template en base : aucune copie (le user garde ses presets perso)")
    void noTemplateNoCopy() {
        btcPreset(alice, "perso");
        assertTrue(presetService.ensureSystemPresets(alice).isEmpty());
        assertEquals(1, presetService.list(alice).size());
    }
}
