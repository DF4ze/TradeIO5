package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BasePresetRole;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PresetOrigin;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveEngineState;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowPreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy.LegacyRainbowTrendConfig;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.repository.dca.bench.AssetAffectationRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowPresetRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.TrendPresetRepository;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({AffectationMigrationService.class, ExpertBasePresetService.class, AffectationMigrationServiceTest.ClockConfig.class})
@DisplayName("Migration des anciens presets vers les Affectations")
class AffectationMigrationServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);
    private static final Instant NOW = Instant.parse("2026-10-03T23:55:00Z");

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(NOW);
        }
    }

    @Autowired private AffectationMigrationService migration;
    @Autowired private ExpertBasePresetService basePresets;
    @Autowired private AssetAffectationRepository affectationRepository;
    @Autowired private TrendPresetRepository trendRepository;
    @Autowired private RainbowPresetRepository rainbowRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private User alice;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("m" + Long.toString(System.nanoTime(), 36)).email(Long.toString(System.nanoTime(), 36) + "@test.local").password("x").build());
        basePresets.ensureBasePresets();
        // colonnes legacy telles qu'en base existante (absentes du schéma courant)
        for (String t : new String[]{"rainbow_live_run", "rainbow_live_engine_state"}) {
            jdbc.execute("ALTER TABLE " + t + " ADD COLUMN IF NOT EXISTS preset_id BIGINT");
        }
    }

    private LegacyRainbowPreset legacy(String asset, String name, boolean enabled, Long strategyId) {
        var d = RainbowLiveTrendConfigs.defaults(asset);
        LegacyRainbowPreset p = new LegacyRainbowPreset();
        ReflectionTestUtils.setField(p, "user", alice);
        ReflectionTestUtils.setField(p, "assetSymbol", asset);
        ReflectionTestUtils.setField(p, "name", name);
        ReflectionTestUtils.setField(p, "enabled", enabled);
        ReflectionTestUtils.setField(p, "assetStrategyId", strategyId);
        ReflectionTestUtils.setField(p, "mode", "TREND_MIX");
        ReflectionTestUtils.setField(p, "analysisWindowMonths", 6);
        ReflectionTestUtils.setField(p, "createdAt", NOW);
        ReflectionTestUtils.setField(p, "updatedAt", NOW);
        ReflectionTestUtils.setField(p, "config", RainbowAtrConfig.of(d.bear().tuning(), d.bear().globals()));
        em.persist(p);
        return p;
    }

    private void trendRow(LegacyRainbowPreset p, boolean modifiedTrend) throws Exception {
        var d = RainbowLiveTrendConfigs.defaults(p.getAssetSymbol());
        TrendConfigDto dto = RainbowLiveTrendConfigs.toDto(d);
        var t = dto.trend();
        int smaPeriod = modifiedTrend ? t.smaPeriod() + 7 : t.smaPeriod();
        ObjectMapper om = new ObjectMapper();
        LegacyRainbowTrendConfig row = new LegacyRainbowTrendConfig();
        ReflectionTestUtils.setField(row, "preset", p);
        ReflectionTestUtils.setField(row, "shortWindow", t.shortWindow());
        ReflectionTestUtils.setField(row, "mediumWindow", t.mediumWindow());
        ReflectionTestUtils.setField(row, "longWindow", t.longWindow());
        ReflectionTestUtils.setField(row, "slopeScale", t.slopeScale());
        ReflectionTestUtils.setField(row, "enterThreshold", t.enter());
        ReflectionTestUtils.setField(row, "exitThreshold", t.exit());
        ReflectionTestUtils.setField(row, "confirmDays", t.confirm());
        ReflectionTestUtils.setField(row, "smaPeriod", smaPeriod);
        ReflectionTestUtils.setField(row, "atrPeriod", t.atrPeriod());
        ReflectionTestUtils.setField(row, "atrMultiplier", t.atrMultiplier());
        ReflectionTestUtils.setField(row, "wickDown", t.wickDown());
        ReflectionTestUtils.setField(row, "wickUp", t.wickUp());
        ReflectionTestUtils.setField(row, "rangeMapping", dto.rangeMapping());
        ReflectionTestUtils.setField(row, "bearJson", om.writeValueAsString(dto.bear()));
        ReflectionTestUtils.setField(row, "bullJson", om.writeValueAsString(dto.bull()));
        em.persist(row);
    }

    /** Ligne run + état moteur rattachés à un preset legacy (via une Affectation d'emprunt, puis détachés en preset_id). */
    private void engineRows(LegacyRainbowPreset p) {
        User donor = userRepository.save(User.builder().username("d" + Long.toString(System.nanoTime(), 36)).email(Long.toString(System.nanoTime(), 36) + "@test.local").password("x").build());
        AssetAffectation tmp = affectationRepository.save(AssetAffectation.builder().user(donor).assetSymbol(p.getAssetSymbol())
                .trendPreset(trendRepository.findAll().getFirst()).rainbowUp(rainbowRepository.findAll().getFirst())
                .rainbowDown(rainbowRepository.findAll().getFirst()).enabled(true).createdAt(NOW).updatedAt(NOW).build());
        em.persist(RainbowLiveEngineState.of(tmp, DAY, RainbowAtrState.initial()));
        em.persist(fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun.builder().user(p.getUser()).assetSymbol(p.getAssetSymbol()).affectation(tmp).day(DAY)
                .signal(RainbowLivePassBlock.builder().close(100.0).build()).build());
        em.flush();
        jdbc.update("UPDATE rainbow_live_run SET affectation_id = NULL, preset_id = ? WHERE affectation_id = ?", p.getId(), tmp.getId());
        jdbc.update("UPDATE rainbow_live_engine_state SET affectation_id = NULL, preset_id = ? WHERE affectation_id = ?", p.getId(), tmp.getId());
    }

    private int count(String table, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
    }

    @Test
    @DisplayName("Sans donnée legacy : rien n'est créé")
    void nothingToMigrate() {
        migration.migrate();
        assertEquals(0, affectationRepository.findByUser(alice).size());
    }

    @Test
    @DisplayName("Preset qui suivait la stratégie : presets système de base, run et état rattachés, relance sans doublon")
    void followsStrategy() {
        LegacyRainbowPreset p = legacy("BTC", "defaut", true, 42L);
        engineRows(p);
        em.flush();

        assertTrue(migration.migrate() >= 1);
        AssetAffectation a = affectationRepository.findByUserAndAssetSymbol(alice, "BTC").orElseThrow();
        assertEquals(PresetOrigin.SYSTEM, a.getTrendPreset().getOrigin());
        assertEquals(BasePresetRole.RAINBOW_BULL, a.getRainbowUp().getBaseRole());
        assertEquals(BasePresetRole.RAINBOW_BEAR, a.getRainbowDown().getBaseRole());
        assertTrue(a.isEnabled());
        assertEquals(1, count("rainbow_live_run", "affectation_id = " + a.getId() + " AND preset_id IS NULL"));
        assertEquals(1, count("rainbow_live_engine_state", "affectation_id = " + a.getId() + " AND preset_id IS NULL"));

        long presets = trendRepository.count() + rainbowRepository.count();
        assertEquals(0, migration.migrate(), "relance : aucune nouvelle Affectation");
        assertEquals(1, affectationRepository.findByUser(alice).size());
        assertEquals(presets, trendRepository.count() + rainbowRepository.count());
    }

    @Test
    @DisplayName("Réglages Trend modifiés : Trend propre à l'utilisateur, presets Rainbow de base conservés")
    void modifiedTrend() throws Exception {
        LegacyRainbowPreset p = legacy("ETH", "mon-reglage", true, null);
        trendRow(p, true);
        em.flush();

        migration.migrate();
        AssetAffectation a = affectationRepository.findByUserAndAssetSymbol(alice, "ETH").orElseThrow();
        assertEquals(PresetOrigin.USER, a.getTrendPreset().getOrigin());
        assertEquals(alice.getId(), a.getTrendPreset().getOwner().getId());
        assertEquals(RainbowLiveTrendConfigs.defaults("ETH").trend().smaPeriod() + 7, a.getTrendPreset().toParams().smaPeriod());
        assertEquals(PresetOrigin.SYSTEM, a.getRainbowUp().getOrigin());
        assertEquals(PresetOrigin.SYSTEM, a.getRainbowDown().getOrigin());
    }

    @Test
    @DisplayName("Réglages identiques aux défauts (Trend inchangé) : presets de base")
    void unchangedTrendUsesBase() throws Exception {
        LegacyRainbowPreset p = legacy("PAXG", "idem", true, null);
        trendRow(p, false);
        em.flush();

        migration.migrate();
        AssetAffectation a = affectationRepository.findByUserAndAssetSymbol(alice, "PAXG").orElseThrow();
        assertEquals(PresetOrigin.SYSTEM, a.getTrendPreset().getOrigin());
        assertNotEquals(null, a.getRainbowUp().getBaseRole());
    }

    @Test
    @DisplayName("Plusieurs presets legacy sur un actif : un seul retenu, runs/états des autres supprimés")
    void severalPresetsOneAsset() {
        LegacyRainbowPreset off = legacy("BTC", "ancien", false, null);
        LegacyRainbowPreset on = legacy("BTC", "actif", true, null);
        engineRows(off);
        engineRows(on);
        em.flush();

        assertTrue(migration.migrate() >= 1);
        AssetAffectation a = affectationRepository.findByUserAndAssetSymbol(alice, "BTC").orElseThrow();
        assertTrue(a.isEnabled(), "le preset activé est retenu");
        assertEquals(1, count("rainbow_live_run", "affectation_id = " + a.getId()));
        assertEquals(0, count("rainbow_live_run", "preset_id IS NOT NULL AND affectation_id IS NULL"));
        assertEquals(1, count("rainbow_live_engine_state", "affectation_id = " + a.getId()));
    }
}
