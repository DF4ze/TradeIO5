package fr.ses10doigts.tradeIO5.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;

/**
 * Lecture/écriture du curseur de risque continu (0-10) par utilisateur. Palier 2 (2026-08) :
 * structure de persistance uniquement, pas le calcul de sizing qui le consommera (étude §4/§7,
 * volontairement non tranché).
 */
@Service
@RequiredArgsConstructor
public class UserTradingSettingsService {

    /**
     * Valeur par défaut retournée quand aucune ligne n'existe encore pour l'utilisateur : "milieu
     * de l'échelle", un choix arbitraire pour éviter un null, pas une recommandation produit.
     */
    public static final int DEFAULT_RISK_CURSOR = 5;

    private final UserTradingSettingsRepository userTradingSettingsRepository;

    public int getRiskCursor(User user) {
        return userTradingSettingsRepository.findByUser(user)
                .map(UserTradingSettings::getRiskCursor)
                .orElse(DEFAULT_RISK_CURSOR);
    }

    public void setRiskCursor(User user, int riskCursor) {
        if (riskCursor < 0 || riskCursor > 10) {
            throw new IllegalArgumentException("riskCursor must be between 0 and 10, got " + riskCursor);
        }

        Optional<UserTradingSettings> existing = userTradingSettingsRepository.findByUser(user);

        UserTradingSettings settings = existing
                .map(s -> {
                    s.setRiskCursor(riskCursor);
                    return s;
                })
                .orElseGet(() -> UserTradingSettings.builder()
                        .user(user)
                        .riskCursor(riskCursor)
                        .build());

        userTradingSettingsRepository.save(settings);
    }
}
