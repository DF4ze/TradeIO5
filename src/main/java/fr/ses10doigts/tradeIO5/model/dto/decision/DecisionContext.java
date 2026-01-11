package fr.ses10doigts.tradeIO5.model.dto.decision;

import fr.ses10doigts.tradeIO5.model.dto.decision.strategy.MarketContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * - Pourquoi
 * La décision dépend aussi de l'utilisateur, pas que du marché.
 *
 * - Responsabilité :
 * état du wallet
 * historique des actions
 * exposition actuelle
 * règles de risque
 *
 * - Conceptuellement :
 * “Avec ce portefeuille, ce user, à ce moment…”
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DecisionContext {
    /** Snapshot du portefeuille utilisateur */
    private WalletSnapshot walletSnapshot;

    /** Profil de l'utilisateur (risk, objectifs…) */
    private UserProfile userProfile;

    /** Contexte du marché (prices, volumes…) */
    private MarketContext marketContext;

    /** État global de l'application pour cette décision */
    private Map<String, Object> globalState;

    /** Timestamp de création du contexte */
    private Instant timestamp;

    public DecisionContext(WalletSnapshot walletSnapshot,
                           UserProfile userProfile,
                           MarketContext marketContext,
                           Map<String, Object> globalState) {
        this.walletSnapshot = walletSnapshot;
        this.userProfile = userProfile;
        this.marketContext = marketContext;
        this.globalState = globalState;
        this.timestamp = Instant.now();
    }
}
