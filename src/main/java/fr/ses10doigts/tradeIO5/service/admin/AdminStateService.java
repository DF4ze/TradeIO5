package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminStateDto;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionControlService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** État global de l'exécution (lecture seule, aucun secret) : mode, verrous, kill switch, plafonds, exchanges câblés. */
@Service
@RequiredArgsConstructor
public class AdminStateService {

    private final ExecutionSettings settings;
    private final ExecutionControlService control;
    private final SecretCipher cipher;
    private final OrderExecutor executor;

    public AdminStateDto state() {
        ExecutionControl c = control.current();
        return new AdminStateDto(settings.getMode().name(), settings.isLiveUnlocked(), settings.liveExecution(),
                cipher.isConfigured(), c.isKillSwitch(), c.getUpdatedBy(), c.getUpdatedAt(), c.getMaxOrderMultiplier(),
                c.getMaxDayMultiplier(), ExecutionControlService.absoluteMaxOrderUsd(c), ExecutionControlService.absoluteMaxDayUsd(c),
                executor.orderProviders().stream().map(Enum::name).toList());
    }
}
