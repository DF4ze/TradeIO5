package fr.ses10doigts.tradeIO5.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.RainbowBandsDto;
import fr.ses10doigts.tradeIO5.service.portfolio.RainbowBandsQueryService;
import fr.ses10doigts.tradeIO5.service.portfolio.RainbowBandsSimulator;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DataJpaTest
@Import({RainbowLivePresetService.class, RainbowLivePresetConfigResolver.class, RainbowLivePresetEventService.class,
        RainbowBandsQueryService.class, RainbowLiveBandsControllerTest.ClockConfig.class})
@DisplayName("RainbowLiveBandsController : bandes figées lues dans les runs")
class RainbowLiveBandsControllerTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(Instant.parse("2026-10-20T23:55:00Z"));
        }
    }

    @Autowired private RainbowBandsQueryService service;
    @MockBean private RainbowBandsSimulator simulator;
    @Autowired private RainbowLivePresetService presetService;
    @Autowired private RainbowLiveBindingRepository bindingRepository;
    @Autowired private RainbowLiveRunRepository runRepository;
    @Autowired private WalletRepository walletRepository;
    @Autowired private UserRepository userRepository;

    private final IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
    private MockMvc mvc;
    private User alice;
    private User bob;
    private RainbowLiveBinding binding;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").build());
        Wallet wallet = walletRepository.save(Wallet.builder().name("OKX").source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(alice).build());
        RainbowAtrConfig c = RainbowLiveDefaultPresets.configFor("BTC");
        RainbowLivePreset preset = presetService.create(alice, new CreateRequest("BTC", "live", true, 6, c.toTuning(), c.toGlobals()));
        binding = bindingRepository.save(RainbowLiveBinding.builder().user(alice).assetSymbol("BTC").preset(preset)
                .wallet(wallet).bagPercent(100).priority(0).build());
        run(preset, LocalDate.of(2026, 7, 1), 100.0);
        run(preset, LocalDate.of(2026, 10, 19), 200.0);
        run(preset, LocalDate.of(2026, 10, 20), 210.0);
        when(facade.getConnectedUser()).thenReturn(alice);
        mvc = MockMvcBuilders.standaloneSetup(new RainbowLiveBandsController(service, facade))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private void run(RainbowLivePreset preset, LocalDate day, double close) {
        runRepository.save(RainbowLiveRun.builder().preset(preset).user(alice).assetSymbol("BTC").day(day).signal(RainbowLivePassBlock.builder()
                .close(close).sma(close - 1).atr(2.0).boundDown2(close - 5).boundDown1(close - 3).boundUp1(close + 3)
                .boundUp2(close + 5).boundUp3(close + 8).zone(2).build()).build());
    }

    @Test
    @DisplayName("Bandes figées de la période, ordre chronologique, non simulées")
    void bandsOfPeriod() throws Exception {
        String url = "/api/rainbow-live/bindings/" + binding.getId() + "/bands";
        mvc.perform(get(url).param("period", "30d")).andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(false))
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].day[2]").value(19))
                .andExpect(jsonPath("$.points[1].boundUp3").value(218.0))
                .andExpect(jsonPath("$.points[1].zone").value(2));
        mvc.perform(get(url).param("period", "all")).andExpect(jsonPath("$.points.length()").value(3));
    }

    @Test
    @DisplayName("simulated=true : points recalculés par le simulateur, marqués simulés")
    void simulatedBands() throws Exception {
        when(simulator.simulate(org.mockito.ArgumentMatchers.eq(binding), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of(new RainbowBandsDto.Point(
                java.time.LocalDate.of(2026, 10, 18), 100.0, 98.0, 2.0, 90.0, 94.0, 102.0, 106.0, 110.0, 1, "BEAR", "BEAR")));
        mvc.perform(get("/api/rainbow-live/bindings/" + binding.getId() + "/bands").param("simulated", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulated").value(true))
                .andExpect(jsonPath("$.points.length()").value(1))
                .andExpect(jsonPath("$.points[0].boundUp3").value(110.0));
    }

    @Test
    @DisplayName("Période invalide : 400 ; lien d'autrui ou inconnu : 404")
    void errorsAndIsolation() throws Exception {
        String url = "/api/rainbow-live/bindings/" + binding.getId() + "/bands";
        mvc.perform(get(url).param("period", "2w")).andExpect(status().isBadRequest());
        when(facade.getConnectedUser()).thenReturn(bob);
        mvc.perform(get(url)).andExpect(status().isNotFound());
        mvc.perform(get("/api/rainbow-live/bindings/999999/bands")).andExpect(status().isNotFound());
    }
}
