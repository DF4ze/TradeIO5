package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderPlanService : l'erreur d'un utilisateur n'arrête pas les autres")
class OrderPlanServiceIsolationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 9);

    private RainbowLiveBinding binding(User user) {
        RainbowLivePreset preset = RainbowLivePreset.builder().id(user.getId()).user(user).assetSymbol("BTC").build();
        Wallet wallet = Wallet.builder().id(user.getId()).webProviderCode(WebProviderCode.OKX).build();
        return RainbowLiveBinding.builder().id(user.getId()).user(user).assetSymbol("BTC").preset(preset).wallet(wallet)
                .executionEnabled(true).build();
    }

    @Test
    @DisplayName("Le planner échoue pour le 1er user : erreur comptée, le 2e user est planifié")
    void errorDoesNotStopOthers() {
        User alice = User.builder().id(1L).username("alice").enabled(true).build();
        User bob = User.builder().id(2L).username("bob").enabled(true).build();
        UserRepository users = mock(UserRepository.class);
        RainbowLiveBindingRepository bindings = mock(RainbowLiveBindingRepository.class);
        RainbowLiveRunRepository runs = mock(RainbowLiveRunRepository.class);
        RainbowLiveOrderPlanRepository plans = mock(RainbowLiveOrderPlanRepository.class);
        OrderPlanner planner = mock(OrderPlanner.class);
        when(users.findByEnabledTrueAndArchivedAtIsNull()).thenReturn(List.of(alice, bob));
        for (User u : List.of(alice, bob)) {
            RainbowLiveBinding b = binding(u);
            when(bindings.findByUserOrderByPriorityAscAssetSymbolAsc(u)).thenReturn(List.of(b));
            RainbowLivePassBlock block = RainbowLivePassBlock.builder().liveStatus(PortfolioStatus.OK)
                    .liveActionType(RainbowLiveAction.NONE).liveCashDetail("{}").build();
            when(runs.findByPresetAndDay(b.getPreset(), DAY)).thenReturn(Optional.of(RainbowLiveRun.builder()
                    .preset(b.getPreset()).user(u).day(DAY).pass2355(block).build()));
            when(plans.findFirstByUserAndDayAndPassOrderByRevisionDesc(u, DAY, RainbowLivePass.T2355)).thenReturn(Optional.empty());
        }
        when(planner.plan(anyList())).thenThrow(new IllegalStateException("boom"))
                .thenReturn(new PlanDraft(List.of(), PlanStatus.PLANNED, null, (FeeTestLevel) null, null));
        OrderPlanService service = new OrderPlanService(users, bindings, runs, plans, planner, new ExecutionSettings(),
                new FixedDomainClock(Instant.parse("2026-10-09T23:58:00Z")), mock(PlatformTransactionManager.class));

        OrderPlanService.PlanSummary summary = service.planAll(DAY, RainbowLivePass.T2355);

        assertEquals(1, summary.errors());
        assertEquals(1, summary.planned());
        verify(planner, times(2)).plan(anyList());
        verify(plans, times(1)).save(any());
    }
}
