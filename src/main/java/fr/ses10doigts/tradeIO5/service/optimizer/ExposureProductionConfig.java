package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;

import java.time.LocalDate;
import java.util.List;

/**
 * Configuration de l'étape C4 (calage exploitable : invariance au capital, bandes du live, wallet BTC + ETH). Tout seuil de décision est ici,
 * fixé avant de regarder les résultats ; plafonds lus dans {@link FrontierConfig#profileCaps()}.
 *
 * @param calibration         fenêtres, blocs, dates de bear de C3 (inchangés : 78 fenêtres de 24 mois + historique complet, 4 blocs)
 * @param k                   marge de sécurité par défaut (1,1 : décision fil-rouge) ; plafond de calage = plafond / k
 * @param kRef                k de repère (1,0) : niveaux rapportés en colonne, aucun rejeu supplémentaire
 * @param capitals            capitaux rejoués en plus du capital de C3 (≈ 1 000 et ≈ 10 000 USDC)
 * @param autonomyDays        autonomie par défaut de l'onboarding (défaut admin, 90 j)
 * @param ddTolerancePts      invariance OK si l'écart du drawdown du pire cas au capital de C3 est ≤ ce seuil (1 pt)
 * @param gainTolerancePts    ... et l'écart du gain médian des blocs ≤ ce seuil (2 pts)
 * @param bandMattersDdPts    la bande « compte » si B s'écarte de A d'au moins ce nombre de points de drawdown (pire cas) ...
 * @param bandMattersGainPts  ... ou de ce nombre de points de gain médian, pour au moins un actif du profil
 * @param walletShare         part du capital du wallet allouée à chaque lien (0,5 : partage égal, comme l'onboarding : cash / nombre de liens)
 * @param fIterations         pas de dichotomie du facteur de réduction f (recherche à une dimension)
 */
public record ExposureProductionConfig(ExposureCalibrationConfig calibration, double k, double kRef, List<Double> capitals, int autonomyDays,
                                       double ddTolerancePts, double gainTolerancePts, double bandMattersDdPts, double bandMattersGainPts,
                                       double walletShare, int fIterations) {

    public ExposureProductionConfig {
        if (k < 1.0 || kRef < 1.0 || capitals.isEmpty() || autonomyDays < 1 || walletShare <= 0 || walletShare > 1 || fIterations < 1) {
            throw new IllegalArgumentException("configuration C4 invalide");
        }
        capitals = List.copyOf(capitals);
    }

    public static ExposureProductionConfig defaults(LocalDate lastEnd) {
        return new ExposureProductionConfig(ExposureCalibrationConfig.defaults(lastEnd), 1.1, 1.0, List.of(1_000.0, 10_000.0),
                ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS).defaultValue().intValueExact(), 1.0, 2.0, 1.0, 2.0, 0.5, 8);
    }

    public LocalDate lastEnd() {
        return calibration.lastEnd();
    }
}
