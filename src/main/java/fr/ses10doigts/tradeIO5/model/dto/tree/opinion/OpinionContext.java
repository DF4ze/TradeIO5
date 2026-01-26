package fr.ses10doigts.tradeIO5.model.dto.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;

import java.util.Map;

/**
 * - Pourquoi
 * La décision dépend aussi de l'utilisateur, pas que du marché.
 * <p>
 * - Responsabilité :
 * état du wallet
 * historique des actions
 * exposition actuelle
 * règles de risque
 * <p>
 * - Conceptuellement :
 * “Avec ce portefeuille, ce user, à ce moment…”
 *
 * @param walletSnapshot Snapshot du portefeuille utilisateur
 * @param userProfile    Profil de l'utilisateur (risk, objectifs…)
 * @param marketContext  Contexte du marché (prices, volumes…)
 * @param globalState    État global de l'application pour cette décision
 * @param clock          Timestamp de création du contexte
 */

public record OpinionContext(
        WalletSnapshot walletSnapshot,
        UserProfile userProfile,
        MarketContext marketContext,
        Map<String, Object> globalState,
        DomainClock clock
) {
}
