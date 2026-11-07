package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.TrendPreset;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExpertFixtures;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Import({ExpertFixtures.class, AffectationConfigResolver.class})
@DisplayName("Réglages Trend : défauts, validation, lecture par l'Affectation")
class RainbowLiveTrendConfigTest {

    @Autowired private ExpertFixtures fixtures;
    @Autowired private AffectationConfigResolver resolver;
    @Autowired private UserRepository userRepository;
    @Autowired private TrendPresetRepository trendRepository;
    @Autowired private EntityManager em;

    private User alice;

    @BeforeEach
    void user() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
    }

    @Test
    @DisplayName("normalize sans config => défauts du code de l'actif")
    void defaults() {
        RainbowLiveTrendConfigs.Resolved d = RainbowLiveTrendConfigs.normalize("BTC", null);
        assertEquals(TrendMixCalculator.Params.defaults(), d.trend());
        assertEquals(RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BULL).tuning(), d.bull().tuning());
    }

    @Test
    @DisplayName("Validation : Trend / mapping / jeu invalides refusés")
    void validation() {
        var bad = new TrendMixCalculator.Params(14, 30, 60, 400.0, 0.1, 0.3, 10, 100, 5, 1.25, true, false);
        assertThrows(IllegalArgumentException.class, () -> RainbowLiveTrendConfigs.normalize("BTC", new TrendConfigDto(bad, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> RainbowLiveTrendConfigs.normalize("BTC", new TrendConfigDto(null, "NOPE", null, null)));
        var b = RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BEAR);
        assertThrows(IllegalArgumentException.class, () -> RainbowLiveTrendConfigs.normalize("BTC", new TrendConfigDto(null, null,
                new ConfigDto(b.tuning(), new RainbowAtrGlobals(true, 60.0, 30.0, 0.5, 2.0, 2.0, 0.5,
                        true, 50.0, false, 15.0, 100.0, 2, 1.5, 1, 3, 0)), null)));
    }

    @Test
    @DisplayName("L'Affectation résout Trend (aller-retour en base), mapping du RANGE et jeux DOWN / UP ; le hash suit le Trend")
    void affectationResolvesPresets() {
        var cfg = RainbowLiveDefaultPresets.configFor("BTC");
        AssetAffectation a = fixtures.affectation(alice, "BTC", "TM", cfg.toTuning(), cfg.toGlobals());
        String hash = resolver.effectiveHash(a);

        var tp = new TrendMixCalculator.Params(10, 20, 40, 300.0, 0.25, 0.05, 5, 50, 7, 1.5, false, true);
        TrendPreset trend = a.getTrendPreset();
        trend.applyParams(tp);
        trend.setRangeMapping("TO_BULL");
        trendRepository.save(trend);
        em.flush();
        em.clear();

        AssetAffectation reloaded = em.find(AssetAffectation.class, a.getId());
        RainbowLiveTrendConfigs.Resolved r = resolver.trendConfig(reloaded);
        assertEquals(tp, r.trend());
        assertEquals("TO_BULL", r.range().name());
        assertEquals(cfg.toTuning(), r.bull().tuning());
        assertEquals(cfg.toTuning(), r.bear().tuning());
        assertNotEquals(hash, resolver.effectiveHash(reloaded));
    }
}
