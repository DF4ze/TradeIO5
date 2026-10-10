package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.security.model.User;

import java.util.List;

/**
 * Seul type du bench autorisé à atteindre un portefeuille : wallet mock ({@link MockPortfolioSource}) ou wallet réel en
 * lecture seule ({@link RealPortfolioSource}). Aucun ordre n'est possible par ce port.
 */
public interface RainbowPortfolioSource {

    /** Lecture du portefeuille qui dimensionne le preset. Ne lève pas pour une panne exchange : statut {@code UNAVAILABLE}. */
    PortfolioReading read(RainbowLivePreset preset);

    /** Presets de l'utilisateur liés à un wallet réel, dans l'ordre de passage du cash ; vide pour la source mock. */
    default List<LiveSlot> liveSlots(User user) {
        return List.of();
    }

    /** Lecture éventuellement requalifiée {@code STALE} si trop ancienne ; inchangée pour la source mock. */
    default PortfolioReading checkFreshness(PortfolioReading reading) {
        return reading;
    }
}
