package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** État global de l'exécution pour le bandeau de la page Admin. Aucun secret. */
public record AdminStateDto(String mode, boolean liveUnlocked, boolean liveExecution, boolean masterKeyConfigured,
                            boolean killSwitch, String killSwitchUpdatedBy, Instant killSwitchUpdatedAt,
                            BigDecimal maxOrderMultiplier, BigDecimal maxDayMultiplier, BigDecimal absoluteMaxOrderUsd,
                            BigDecimal absoluteMaxDayUsd, List<String> orderProviders) {
}
