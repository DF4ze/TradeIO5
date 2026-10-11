package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminPlanDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.EventPageDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderEvent;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderEventRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionMode;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Import({AdminPlanQueryService.class, AdminPlanQueryServiceTest.Config.class})
@DisplayName("Lecture admin des plans : tous utilisateurs, filtres, révisions remplacées exclues, bornes de pagination")
class AdminPlanQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    @TestConfiguration
    static class Config {
        @Bean
        DomainClock clock() {
            return new FixedDomainClock(NOW);
        }
    }

    @Autowired private AdminPlanQueryService service;
    @Autowired private RainbowLiveOrderPlanRepository planRepository;
    @Autowired private RainbowLiveOrderEventRepository eventRepository;
    @Autowired private UserRepository userRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = userRepository.save(User.builder().username("alice").email("alice@example.com").password("x").enabled(true).build());
        bob = userRepository.save(User.builder().username("bob").email("bob@example.com").password("x").enabled(true).build());
    }

    private RainbowLiveOrderPlan plan(User user, LocalDate day, int revision, PlanStatus status, Instant expiresAt) {
        return planRepository.save(RainbowLiveOrderPlan.builder().user(user).day(day).pass(RainbowLivePass.T2355).revision(revision)
                .status(status).mode(ExecutionMode.DRY_RUN).inputsHash("h" + user.getId() + day + revision)
                .createdAt(NOW.minusSeconds(60 + revision)).expiresAt(expiresAt).build());
    }

    @Test
    @DisplayName("Tous les utilisateurs, révisions remplacées exclues, plus récent d'abord")
    void allUsersWithoutSuperseded() {
        plan(alice, LocalDate.of(2026, 10, 10), 1, PlanStatus.SUPERSEDED, NOW.plusSeconds(3600));
        plan(alice, LocalDate.of(2026, 10, 10), 2, PlanStatus.EXECUTED, NOW.plusSeconds(3600));
        plan(bob, LocalDate.of(2026, 10, 11), 1, PlanStatus.PLANNED, NOW.plusSeconds(3600));

        List<AdminPlanDto> plans = service.plans(null, null, null, null);

        assertEquals(2, plans.size());
        assertEquals("bob", plans.getFirst().username());
        assertEquals("alice", plans.getLast().username());
        assertEquals(2, plans.getLast().revision());
    }

    @Test
    @DisplayName("Fenêtre par défaut = 7 jours ; from/to explicites ; from > to refusé")
    void windows() {
        plan(alice, LocalDate.of(2026, 10, 5), 1, PlanStatus.PLANNED, NOW.plusSeconds(3600));
        plan(alice, LocalDate.of(2026, 9, 20), 1, PlanStatus.PLANNED, NOW.plusSeconds(3600));

        assertEquals(1, service.plans(null, null, null, null).size());
        assertEquals(2, service.plans(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 11), null, null).size());
        assertThrows(IllegalArgumentException.class, () -> service.plans(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 1), null, null));
        assertEquals(ExecutionDefaults.ADMIN_PLAN_WINDOW_DAYS, 7);
    }

    @Test
    @DisplayName("Filtres utilisateur et statut effectif (PLANNED échu = EXPIRED)")
    void filters() {
        plan(alice, LocalDate.of(2026, 10, 11), 1, PlanStatus.PLANNED, NOW.minusSeconds(1));
        plan(bob, LocalDate.of(2026, 10, 11), 1, PlanStatus.PLANNED, NOW.plusSeconds(600));

        assertEquals(1, service.plans(null, null, alice.getId(), null).size());
        assertEquals("alice", service.plans(null, null, null, PlanStatus.EXPIRED).getFirst().username());
        assertEquals("bob", service.plans(null, null, null, PlanStatus.PLANNED).getFirst().username());
        assertTrue(service.plans(null, null, null, PlanStatus.EXECUTED).isEmpty());
    }

    @Test
    @DisplayName("Audit cross-user : pages bornées (taille ≤ 100, ≥ 1, page ≥ 0), plan inconnu => vide")
    void eventsBounds() {
        RainbowLiveOrderPlan p = plan(bob, LocalDate.of(2026, 10, 11), 1, PlanStatus.EXECUTED, NOW.plusSeconds(600));
        for (int i = 0; i < 3; i++) {
            eventRepository.save(RainbowLiveOrderEvent.builder().plan(p).type(OrderEventType.PLAN_STARTED).payload("{\"n\":" + i + "}")
                    .createdAt(NOW).build());
        }

        EventPageDto page = service.events(p.getId(), 0, 2).orElseThrow();
        assertEquals(2, page.items().size());
        assertEquals(3, page.totalElements());
        assertEquals(ExecutionDefaults.MAX_PAGE_SIZE, service.events(p.getId(), 0, 100_000).orElseThrow().size());
        assertEquals(1, service.events(p.getId(), -5, 0).orElseThrow().size());
        assertEquals(0, service.events(p.getId(), -5, 0).orElseThrow().page());
        assertTrue(service.events(999_999L, 0, 20).isEmpty());
    }
}
