package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.List;

/**
 * Configuration de l'étape E2 (robustesse de la coupure au switch). Tout seuil de décision est ici, fixé avant de regarder les résultats ;
 * le mécanisme de coupure est celui d'E1 ({@link SwitchCutConfig}), inchangé.
 *
 * @param cut             configuration d'E1 (grille de multiplicateurs, vente 100 %, 4 blocs de 24 mois, règle de blocs) ; la ré-entrée est posée par run
 * @param sizes           tailles de ré-entrée comparées, en % du capital initial, ordre croissant (0 et 20 = E1 reconstitué, 5 et 10 = nouvelles)
 * @param newSizes        tailles réellement nouvelles (runs du bloc A)
 * @param noiseSizes      tailles rejouées pour la bande de bruit (B) et pour le portefeuille (C)
 * @param shifts          décalages de la date de départ, en jours (20, de 0 à 60 : méthode de B')
 * @param stableShare     part des décalages où la coupure doit battre le pur (0,8) pour qu'un plafond soit « stable »
 * @param minCaps         plafonds (sur 4) à satisfaire pour ROBUSTE / STABLE / TIENT (3)
 * @param walletShare     part du capital commun attribuée à chaque lien (0,5) ; la ré-entrée d'un lien = taille × part × capital commun
 * @param capTolerancePts tolérance (1 pt) pour dire que le plafond du wallet est respecté au multiplicateur interpolé
 * @param reportMultipliers multiplicateurs auxquels on rapporte la réduction de drawdown « à multiplicateur égal » (C)
 * @param replayMs        durée supposée d'un rejeu (estimation avant lancement)
 * @param budgetMinutes   au-delà, le runner s'arrête et demande
 */
public record SwitchRobustnessConfig(SwitchCutConfig cut, List<Double> sizes, List<Double> newSizes, List<Double> noiseSizes, List<Integer> shifts,
                                     double stableShare, int minCaps, double walletShare, double capTolerancePts, List<Double> reportMultipliers,
                                     double replayMs, double budgetMinutes) {

    public SwitchRobustnessConfig {
        if (sizes.isEmpty() || shifts.isEmpty() || stableShare <= 0 || stableShare > 1 || minCaps < 1 || walletShare <= 0 || walletShare > 1) {
            throw new IllegalArgumentException("configuration E2 invalide");
        }
        sizes = List.copyOf(sizes);
        newSizes = List.copyOf(newSizes);
        noiseSizes = List.copyOf(noiseSizes);
        shifts = List.copyOf(shifts);
        reportMultipliers = List.copyOf(reportMultipliers);
    }

    public static SwitchRobustnessConfig defaults(LocalDate lastEnd) {
        return new SwitchRobustnessConfig(SwitchCutConfig.defaults(lastEnd), List.of(0.0, 5.0, 10.0, 20.0), List.of(5.0, 10.0), List.of(20.0, 10.0),
                ExposureParity.shifts(20, 60), 0.8, 3, 0.5, 1.0, List.of(1.0, 2.0, 4.0), 25.0, 10.0);
    }

    /** Rejeux au pire, en équivalents d'un rejeu à un lien (un rejeu de wallet à 2 liens compte double). */
    public long worstCaseReplays(int assets) {
        int blocks = cut.parity().blockCount();
        long levels = cut.multipliers().size();
        long reconstituted = (long) (sizes.size() - newSizes.size()) * assets * cut.replays();
        long a = (long) newSizes.size() * assets * cut.replays();
        long b = (long) assets * shifts.size() * levels * blocks * (1L + noiseSizes.size());
        long c = 2L * (levels * blocks * (1L + noiseSizes.size()) + 4L * blocks * (1L + noiseSizes.size()));
        return reconstituted + a + b + c;
    }

    public double worstCaseMinutes(int assets) {
        return worstCaseReplays(assets) * replayMs / 60_000.0;
    }
}
