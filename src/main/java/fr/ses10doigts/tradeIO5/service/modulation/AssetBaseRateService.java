package fr.ses10doigts.tradeIO5.service.modulation;

import fr.ses10doigts.tradeIO5.model.entity.modulation.AssetBaseRate;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl.BaseRate;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.repository.modulation.AssetBaseRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Résolution du taux de base d'un (profil, actif) : ligne dédiée de l'actif (validée par un admin, D1b) d'abord, puis la table admin
 * ({@link RainbowModulationControl#baseRate} : BTC / ETH propres, autre actif = repli BTC « non calibré »). Aucun effet sur les ordres.
 */
@Service
@RequiredArgsConstructor
public class AssetBaseRateService {

    private final AssetBaseRateRepository rows;

    /** @return {@code null} si le profil n'a pas de table ({@code CUSTOM}, nul) */
    public BaseRate resolve(RainbowModulationControl control, StartingProfile profile, String asset) {
        if (profile == null || !profile.selectable()) {
            return null;
        }
        if (!ModulationDefaults.CALIBRATED_RATE_ASSETS.contains(asset)) {
            AssetBaseRate row = rows.findByAssetSymbol(asset).orElse(null);
            if (row != null) {
                return new BaseRate(row.rate(profile), true);
            }
        }
        return control.baseRate(profile, asset);
    }
}
