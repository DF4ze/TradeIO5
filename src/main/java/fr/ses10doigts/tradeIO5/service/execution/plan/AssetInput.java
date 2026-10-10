package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Entrées du plan pour un actif : binding (priorité, activation, wallet) et bloc live de la passe en base (statut de
 * lecture, action figée, montant USD nominal, quantité, cash par membre du groupe USD du pool au moment de la passe).
 * {@code status} nul : aucun snapshot live pour la passe.
 */
public record AssetInput(String assetSymbol, int priority, boolean executionEnabled, Long walletId, WebProviderCode provider,
                         ApiCredential credential, PortfolioStatus status, RainbowLiveAction action, BigDecimal amountUsd,
                         BigDecimal quantity, Map<String, BigDecimal> cashByMember) {
}
