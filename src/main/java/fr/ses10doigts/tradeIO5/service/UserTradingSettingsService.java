package fr.ses10doigts.tradeIO5.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.UserTradingSettings;
import fr.ses10doigts.tradeIO5.repository.UserTradingSettingsRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;

/**
 * Lecture/écriture des réglages de trading par utilisateur : curseur de risque continu (0-10) et réglages de modulation
 * (bande d'exposition, puissance, biais). Persistance et validation uniquement : rien n'est encore appliqué aux montants.
 */
@Service
@RequiredArgsConstructor
public class UserTradingSettingsService {

    /**
     * Valeur par défaut retournée quand aucune ligne n'existe encore pour l'utilisateur : "milieu
     * de l'échelle", un choix arbitraire pour éviter un null, pas une recommandation produit.
     */
    public static final int DEFAULT_RISK_CURSOR = 5;

    /** Réglages de modulation d'un utilisateur : exposition min/max en %, puissance (×), biais signé. */
    public record ModulationSettings(double exposureMin, double exposureMax, double power, double bias) {

        public static ModulationSettings neutral() {
            return new ModulationSettings(ModulationDefaults.NEUTRAL_EXPOSURE_MIN, ModulationDefaults.NEUTRAL_EXPOSURE_MAX,
                    ModulationDefaults.NEUTRAL_POWER, ModulationDefaults.NEUTRAL_BIAS);
        }
    }

    private final UserTradingSettingsRepository userTradingSettingsRepository;
    private final RainbowModulationControlService modulationControl;

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

    /** Réglages stockés ; ligne absente ou colonne nulle => valeur neutre. */
    public ModulationSettings getModulationSettings(User user) {
        return userTradingSettingsRepository.findByUser(user).map(UserTradingSettingsService::storedOrNeutral)
                .orElseGet(ModulationSettings::neutral);
    }

    /**
     * Enregistre les réglages après validation contre les limites admin courantes ; {@link IllegalArgumentException}
     * (rien écrit) sinon. Ne modifie jamais {@code riskCursor}.
     */
    public ModulationSettings setModulationSettings(User user, double exposureMin, double exposureMax, double power,
                                                    double bias) {
        validate(modulationControl.current(), exposureMin, exposureMax, power, bias);
        UserTradingSettings settings = userTradingSettingsRepository.findByUser(user)
                .orElseGet(() -> UserTradingSettings.builder().user(user).riskCursor(DEFAULT_RISK_CURSOR).build());
        settings.setExposureMin(exposureMin);
        settings.setExposureMax(exposureMax);
        settings.setPower(power);
        settings.setBias(bias);
        userTradingSettingsRepository.save(settings);
        return new ModulationSettings(exposureMin, exposureMax, power, bias);
    }

    /**
     * Réglages stockés <b>clampés</b> aux bornes admin courantes (l'admin peut les resserrer après coup) : puissance dans
     * [powerMin, powerMax], biais dans ±biasMax, bande dans [0, 100] et élargie autour de son centre (décalée pour rester dans
     * [0, 100]) si elle est plus étroite que {@code minBandWidthPts}.
     */
    public ModulationSettings effectiveSettings(User user) {
        RainbowModulationControl limits = modulationControl.current();
        ModulationSettings stored = getModulationSettings(user);
        double minWidth = limits.getMinBandWidthPts().doubleValue();
        double floor = ModulationDefaults.EXPOSURE_FLOOR_PCT;
        double ceiling = ModulationDefaults.EXPOSURE_CEILING_PCT;
        double min = Math.clamp(stored.exposureMin(), floor, ceiling);
        double max = Math.clamp(stored.exposureMax(), floor, ceiling);
        if (max - min < minWidth) {
            double half = minWidth / 2;
            double center = (min + max) / 2;
            min = Math.clamp(center - half, floor, ceiling - minWidth);
            max = min + minWidth;
        }
        double biasMax = limits.getBiasMax().doubleValue();
        return new ModulationSettings(min, max,
                Math.clamp(stored.power(), limits.getPowerMin().doubleValue(), limits.getPowerMax().doubleValue()),
                Math.clamp(stored.bias(), -biasMax, biasMax));
    }

    private static ModulationSettings storedOrNeutral(UserTradingSettings s) {
        return new ModulationSettings(
                s.getExposureMin() == null ? ModulationDefaults.NEUTRAL_EXPOSURE_MIN : s.getExposureMin(),
                s.getExposureMax() == null ? ModulationDefaults.NEUTRAL_EXPOSURE_MAX : s.getExposureMax(),
                s.getPower() == null ? ModulationDefaults.NEUTRAL_POWER : s.getPower(),
                s.getBias() == null ? ModulationDefaults.NEUTRAL_BIAS : s.getBias());
    }

    private static void validate(RainbowModulationControl limits, double exposureMin, double exposureMax, double power,
                                 double bias) {
        if (!Double.isFinite(exposureMin) || !Double.isFinite(exposureMax) || !Double.isFinite(power) || !Double.isFinite(bias)) {
            throw new IllegalArgumentException("Les réglages de modulation doivent être des nombres finis");
        }
        if (exposureMin < ModulationDefaults.EXPOSURE_FLOOR_PCT || exposureMin >= exposureMax
                || exposureMax > ModulationDefaults.EXPOSURE_CEILING_PCT) {
            throw new IllegalArgumentException("Bande d'exposition : 0 ≤ min < max ≤ 100 requis, reçu " + exposureMin
                    + " / " + exposureMax);
        }
        double minWidth = limits.getMinBandWidthPts().doubleValue();
        if (exposureMax - exposureMin < minWidth) {
            throw new IllegalArgumentException("Bande d'exposition : largeur minimale " + minWidth + " pts, reçu "
                    + (exposureMax - exposureMin));
        }
        double powerMin = limits.getPowerMin().doubleValue();
        double powerMax = limits.getPowerMax().doubleValue();
        if (power < powerMin || power > powerMax) {
            throw new IllegalArgumentException("Puissance dans [" + powerMin + ", " + powerMax + "] requise, reçu " + power);
        }
        double biasMax = limits.getBiasMax().doubleValue();
        if (Math.abs(bias) > biasMax) {
            throw new IllegalArgumentException("|biais| ≤ " + biasMax + " requis, reçu " + bias);
        }
    }
}
