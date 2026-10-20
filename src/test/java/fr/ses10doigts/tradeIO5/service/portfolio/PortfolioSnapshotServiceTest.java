package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.portfolio.PortfolioSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.WalletSource;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.portfolio.PortfolioSnapshotRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({PortfolioSnapshotService.class, PortfolioSnapshotServiceTest.ClockConfig.class})
@DisplayName("Historisation du patrimoine : instantané quotidien immuable")
class PortfolioSnapshotServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    private static final Instant AT = Instant.parse("2026-10-20T23:55:00Z");
    private static final ModulationSettings BAND = new ModulationSettings(40, 80, 1, 0);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        DomainClock domainClock() {
            return new FixedDomainClock(AT);
        }
    }

    @Autowired private PortfolioSnapshotService service;
    @Autowired private PortfolioSnapshotRepository repository;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletRepository walletRepository;

    private User alice;
    private User bob;
    private Wallet aliceWallet;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").enabled(true).build());
        aliceWallet = walletRepository.save(Wallet.builder().name("OKX").source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(alice).build());
    }

    private static PortfolioReading ok(double cash, Map<String, Double> positions) {
        return new PortfolioReading(cash, positions, AT, PortfolioStatus.OK, 1L);
    }

    @Test
    @DisplayName("Valorise stable + actifs, exposition et bande en vigueur")
    void valuesAndExposure() {
        PortfolioSnapshot s = service.record(alice, aliceWallet, DAY, ok(600.0, Map.of("BTC", 0.01, "ETH", 0.0)),
                List.of("BTC", "ETH"), Map.of("BTC", 40000.0, "ETH", 2000.0), BAND);

        assertNotNull(s);
        assertEquals(600.0, s.getCashUsd());
        assertEquals(400.0, s.getAssetsValue(), 1e-9);
        assertEquals(1000.0, s.getTotalValue(), 1e-9);
        assertEquals(40.0, s.getExposurePct(), 1e-9);
        assertEquals(40.0, s.getExposureMin());
        assertEquals(80.0, s.getExposureMax());
        assertEquals(2, s.getAssets().size(), "ETH lié mais à 0 est conservé");
    }

    @Test
    @DisplayName("Relance du même jour : aucun doublon, instantané d'origine conservé")
    void idempotent() {
        service.record(alice, aliceWallet, DAY, ok(100.0, Map.of()), List.of(), Map.of(), BAND);
        PortfolioSnapshot again = service.record(alice, aliceWallet, DAY, ok(999.0, Map.of()), List.of(), Map.of(), BAND);

        assertNull(again);
        assertEquals(1, repository.count());
        assertEquals(100.0, repository.findAll().getFirst().getCashUsd());
        assertTrue(service.exists(alice.getId(), aliceWallet.getId(), DAY));
        assertFalse(service.exists(alice.getId(), aliceWallet.getId(), DAY.plusDays(1)));
    }

    @Test
    @DisplayName("Lecture indisponible : statut conservé, valeurs nulles (jamais 0)")
    void unavailableKeepsNulls() {
        PortfolioSnapshot s = service.record(alice, aliceWallet, DAY, PortfolioReading.unavailable(aliceWallet.getId(), AT),
                List.of("BTC"), Map.of("BTC", 40000.0), BAND);

        assertEquals(PortfolioStatus.UNAVAILABLE, s.getStatus());
        assertNull(s.getCashUsd());
        assertNull(s.getTotalValue());
        assertTrue(s.getAssets().isEmpty());
    }

    @Test
    @DisplayName("Prix manquant pour un actif détenu : totaux nuls, quantité conservée")
    void missingPriceLeavesTotalsNull() {
        PortfolioSnapshot s = service.record(alice, aliceWallet, DAY, ok(100.0, Map.of("BTC", 0.5)), List.of("BTC"),
                Map.of(), BAND);

        assertNull(s.getTotalValue());
        assertNull(s.getExposurePct());
        assertEquals(100.0, s.getCashUsd());
        assertEquals(0.5, s.getAssets().getFirst().getQuantity());
        assertNull(s.getAssets().getFirst().getPrice());
    }

    @Test
    @DisplayName("Isolation : la lecture par utilisateur ne voit que ses instantanés")
    void isolatedPerUser() {
        Wallet bobWallet = walletRepository.save(Wallet.builder().name("OKX").source(WalletSource.EXCHANGE)
                .webProviderCode(WebProviderCode.OKX).user(bob).build());
        service.record(alice, aliceWallet, DAY, ok(100.0, Map.of()), List.of(), Map.of(), BAND);
        service.record(bob, bobWallet, DAY, ok(200.0, Map.of()), List.of(), Map.of(), BAND);

        List<PortfolioSnapshot> mine = repository.findByUserIdAndDayBetweenOrderByDayAsc(alice.getId(), DAY, DAY);
        assertEquals(1, mine.size());
        assertEquals(100.0, mine.getFirst().getCashUsd());
    }
}
