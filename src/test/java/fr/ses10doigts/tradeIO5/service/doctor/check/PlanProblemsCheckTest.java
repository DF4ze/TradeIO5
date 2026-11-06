package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PlanProblemsCheck")
class PlanProblemsCheckTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private RainbowLiveOrderPlanRepository plans;
    private PlanProblemsCheck check;
    private final User user = User.builder().username("clem").build();

    @BeforeEach
    void setUp() {
        plans = mock(RainbowLiveOrderPlanRepository.class);
        check = new PlanProblemsCheck(plans, new FixedDomainClock(NOW));
    }

    private RainbowLiveOrderPlan plan(long id, PlanStatus status) {
        return RainbowLiveOrderPlan.builder().id(id).user(user).day(LocalDate.of(2026, 11, 5)).status(status).build();
    }

    private List<CheckResult> run(RainbowLiveOrderPlan... list) {
        when(plans.findAdminPlans(eq(PlanStatus.SUPERSEDED), any(), any(), isNull())).thenReturn(List.of(list));
        return check.run();
    }

    @Test
    @DisplayName("FAILED => CRIT, PARTIAL / EXPIRED => WARN, EXECUTED / PLANNED / BLOCKED => rien")
    void statuses() {
        List<CheckResult> r = run(plan(1, PlanStatus.FAILED), plan(2, PlanStatus.PARTIAL), plan(3, PlanStatus.EXPIRED),
                plan(4, PlanStatus.EXECUTED), plan(5, PlanStatus.PLANNED), plan(6, PlanStatus.BLOCKED));
        assertEquals(3, r.size());
        assertEquals(DoctorStatus.CRIT, r.get(0).status());
        assertEquals("plan#1 clem 2026-11-05", r.get(0).subject());
        assertEquals(DoctorStatus.WARN, r.get(1).status());
        assertEquals(DoctorStatus.WARN, r.get(2).status());
    }

    @Test
    @DisplayName("EXECUTING : OK au début, CRIT quand il traîne au-delà du seuil")
    void stuckExecuting() {
        RainbowLiveOrderPlan fresh = plan(1, PlanStatus.EXECUTING);
        fresh.setExecutionStartedAt(NOW.minus(Duration.ofMinutes(5)));
        assertTrue(run(fresh).isEmpty());

        RainbowLiveOrderPlan stuck = plan(2, PlanStatus.EXECUTING);
        stuck.setExecutionStartedAt(NOW.minus(Duration.ofMinutes(45)));
        List<CheckResult> r = run(stuck);
        assertEquals(1, r.size());
        assertEquals(DoctorStatus.CRIT, r.getFirst().status());
    }

    @Test
    @DisplayName("step UNKNOWN => CRIT, step REJECTED => WARN, même sur un plan EXECUTED")
    void stepProblems() {
        RainbowLiveOrderPlan unknown = plan(1, PlanStatus.EXECUTED);
        unknown.getSteps().add(RainbowLiveOrderStep.builder().rank(2).assetSymbol("BTC").status(StepStatus.UNKNOWN).build());
        RainbowLiveOrderPlan rejected = plan(2, PlanStatus.EXECUTED);
        rejected.getSteps().add(RainbowLiveOrderStep.builder().rank(1).assetSymbol("ETH").status(StepStatus.REJECTED).build());
        List<CheckResult> r = run(unknown, rejected);
        assertEquals(DoctorStatus.CRIT, r.get(0).status());
        assertEquals(DoctorStatus.WARN, r.get(1).status());
    }
}
