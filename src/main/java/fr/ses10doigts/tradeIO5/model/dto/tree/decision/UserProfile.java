package fr.ses10doigts.tradeIO5.model.dto.tree.decision;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.RiskProfile;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Profil utilisateur, utilisé dans les décisions.
 */
@Data
@Builder
public class UserProfile {

    /** Profil de risque global */
    private RiskProfile riskProfile;

    /** Si l'utilisateur souhaite sortir du marché */
    private boolean exitingMarket;

    /** Si l'utilisateur souhaite renforcer certaines positions */
    private boolean reinforcementActive;

    /** Paramètres spécifiques éventuels */
    private BigDecimal maxAllocationPerAsset;
    private BigDecimal minCashReserve;
}
