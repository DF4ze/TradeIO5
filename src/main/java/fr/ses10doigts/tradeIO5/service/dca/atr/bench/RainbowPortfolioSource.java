package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.security.model.User;

import java.util.List;

/**
 * Seul type du bench autorisé à atteindre un portefeuille : wallet réel en lecture seule
 * ({@link RealPortfolioSource}, une lecture par lien). Aucun ordre n'est possible par ce port.
 */
public interface RainbowPortfolioSource {

    /** Liens (bindings) de l'utilisateur à un wallet réel, dans l'ordre de passage du cash. */
    List<LiveSlot> liveSlots(User user);
}
