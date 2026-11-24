package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.optimizer.ExposureCalibrationStudy.Level;

import java.util.List;

/**
 * Résultat pur du calcul d'une proposition (rien persisté, rien appliqué). {@code levels} vide : taux non calculés (drapeaux dans {@code flags}).
 *
 * @param presetSource actif dont le preset est proposé (BTC)
 * @param historyMonths mois d'historique ; {@code blocks} : blocs de 24 mois rejouables ; {@code switches} : changements de jeu Bull / Bear
 * @param estimatedMinutes coût estimé du calage (série), {@code measuredMs} : durée mesurée
 */
public record NewAssetProposalResult(String asset, String presetSource, String historySource, CandleFingerprint fingerprint, int historyMonths, int blocks,
                                     int switches, List<ProposalFlag> flags, double marginK, List<Level> levels, double referenceBase, long replays,
                                     double estimatedMinutes, long measuredMs, List<String> notes) {

    public boolean hasRates() {
        return !levels.isEmpty();
    }
}
