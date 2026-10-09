package fr.ses10doigts.tradeIO5.repository.dca.bench;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.ConfigDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveDtos.TrendConfigDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveUiMode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.UpdateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveTrendConfigs;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({RainbowLivePresetService.class, RainbowLiveTrendConfigTest.ClockConfig.class})
@DisplayName("Bench Rainbow : réglages Trend Mix par preset et mode d'affichage utilisateur")
class RainbowLiveTrendConfigTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-03T23:55:00Z"));
        }
    }

    @Autowired private RainbowLivePresetService presetService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager em;

    private User alice;

    @BeforeEach
    void user() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
    }

    private RainbowLivePreset trend(TrendConfigDto cfg) {
        return presetService.create(alice, new CreateRequest("BTC", "TM", true, 6, 1000, null, null, "TREND_MIX", cfg));
    }

    @Test
    @DisplayName("TREND_MIX sans config => défauts du code ; roundtrip des valeurs éditées (Trend, mapping, jeux)")
    void defaultsAndRoundtrip() {
        RainbowLivePreset p = trend(null);
        em.flush();
        em.clear();
        assertTrue(presetService.get(alice, p.getId()).isTrendMix());
        RainbowLiveTrendConfigs.Resolved d = presetService.trendConfig(presetService.get(alice, p.getId()));
        assertEquals(TrendMixCalculator.Params.defaults(), d.trend());
        assertEquals(RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BULL).tuning(), d.bull().tuning());

        var bull = RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BULL);
        var tp = new TrendMixCalculator.Params(10, 20, 40, 300.0, 0.25, 0.05, 5, 50, 7, 1.5, false, true);
        presetService.update(alice, p.getId(), new UpdateRequest("TM2", true, 6, null, null,
                new TrendConfigDto(tp, "TO_BULL", null,
                        new ConfigDto(bull.tuning(), bull.globals()))));
        em.flush();
        em.clear();
        RainbowLiveTrendConfigs.Resolved r = presetService.trendConfig(presetService.get(alice, p.getId()));
        assertEquals(tp, r.trend());
        assertEquals("TO_BULL", r.range().name());
        assertEquals(bull.tuning(), r.bull().tuning());
    }

    @Test
    @DisplayName("Validation : Trend / mapping / jeu invalides refusés")
    void validation() {
        var bad = new TrendMixCalculator.Params(14, 30, 60, 400.0, 0.1, 0.3, 10, 100, 5, 1.25, true, false);
        assertThrows(IllegalArgumentException.class, () -> trend(new TrendConfigDto(bad, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> trend(new TrendConfigDto(null, "NOPE", null, null)));
        var b = RainbowAtrPresets.bearBull("BTC").get(RainbowAtrPresets.BEAR);
        assertThrows(IllegalArgumentException.class, () -> trend(new TrendConfigDto(null, null,
                new ConfigDto(b.tuning(), new fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals(true, 60.0, 30.0, 0.5, 2.0, 2.0, 0.5,
                        true, 50.0, false, 15.0, 100.0, 2, 1.5, 1, 3, 0)), null)));
    }

    @Test
    @DisplayName("Mode d'affichage : EXPERT par défaut, persisté par utilisateur, valeur invalide refusée")
    void uiMode() {
        assertEquals(RainbowLiveUiMode.EXPERT, presetService.uiMode(alice));
        presetService.setUiMode(alice, "SIMPLE");
        em.flush();
        em.clear();
        assertEquals(RainbowLiveUiMode.SIMPLE, presetService.uiMode(alice));
        assertThrows(IllegalArgumentException.class, () -> presetService.setUiMode(alice, "X"));
    }
}
