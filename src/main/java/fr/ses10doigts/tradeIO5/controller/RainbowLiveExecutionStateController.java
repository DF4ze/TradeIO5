package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.execution.OrderPlanDtos.ExecutionStateDto;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** État global de l'exécution (mode, exécution réelle possible, kill switch) pour l'affichage. Lecture seule, aucun secret. */
@RestController
@RequestMapping("/api/rainbow-live/execution-state")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveExecutionStateController {

    private final ExecutionSettings settings;
    private final ExecutionControlService control;

    @GetMapping
    public ExecutionStateDto state() {
        return new ExecutionStateDto(settings.getMode().name(), settings.liveExecution(), control.current().isKillSwitch());
    }
}
