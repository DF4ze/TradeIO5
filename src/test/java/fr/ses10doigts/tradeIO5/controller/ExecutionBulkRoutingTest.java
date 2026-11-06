package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminBulkPlanService;
import fr.ses10doigts.tradeIO5.service.admin.AdminPlanTriggerService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.security.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Routage : /plans/bulk/{action} n'est pas capté par /plans/{id}/reset")
class ExecutionBulkRoutingTest {

    @Test
    @DisplayName("POST /plans/bulk/reset => lot ; POST /plans/12/reset => reset unitaire")
    void bulkResetIsNotCapturedByUnitReset() throws Exception {
        IAuthenticationFacade facade = mock(IAuthenticationFacade.class);
        when(facade.getConnectedUser()).thenReturn(User.builder().id(1L).username("u").build());
        AdminBulkPlanService bulk = mock(AdminBulkPlanService.class);
        AdminPlanTriggerService trigger = mock(AdminPlanTriggerService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExecutionBulkAdminController(bulk, facade),
                new ExecutionPlanAdminController(trigger, mock(DomainClock.class), facade)).build();

        mvc.perform(post("/api/admin/execution/plans/bulk/reset").contentType(MediaType.APPLICATION_JSON)
                .content("{\"planIds\":[1],\"dryRun\":true}")).andExpect(status().isOk());
        verify(bulk).execute(eq(AdminBulkPlanService.BulkAction.RESET), any(), eq(true), any(), anyString());

        mvc.perform(post("/api/admin/execution/plans/12/reset")).andExpect(status().isOk());
        verify(trigger).reset(12L, "u");
    }
}
