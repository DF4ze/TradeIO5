package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;

/**
 * Configuration de l'étape D1a (transfert des presets existants sur des actifs jamais vus). Tout seuil de décision est ici, fixé avant de
 * regarder les résultats.
 *
 * @param cash0              capital de départ de chaque paire (identique pour les 36 paires)
 * @param minBlocks          blocs de 24 mois rejouables pour conclure sur un actif (2) ; en dessous : « provisoire », hors verdict
 * @param blockWinShare      part minimale de blocs où Δ &gt; 0 pour {@code TRANSFERE} (0,6)
 * @param genericShare       part minimale d'actifs hors échantillon conclus où le preset {@code TRANSFERE} pour {@code GENERIQUE} (0,7) ; même seuil
 *                           pour la règle de choix par volatilité
 * @param volWinShare        part minimale d'actifs où le preset choisi bat la moyenne des deux autres (Δ médian) (0,6)
 * @param minConcludedAssets nombre minimal d'actifs hors échantillon conclus pour rendre un verdict autre que {@code NON_CONCLUANT} (5)
 * @param atrPeriod          période de l'ATR (RMA) dont le % du cours, médian sur tout l'historique, mesure la volatilité (14)
 * @param blockMonths        durée d'un bloc (24)
 * @param blockCount         nombre maximal de blocs, alignés sur la dernière bougie de l'actif (4)
 * @param shiftCount         décalages de départ de la bande de bruit du DCA (20)
 * @param shiftMaxDays       plus grand décalage (60)
 * @param replayMs           durée supposée d'un rejeu (estimation avant lancement)
 * @param budgetMinutes      au-delà (série), le runner s'arrête et demande
 */
public record TransferConfig(double cash0, int minBlocks, double blockWinShare, double genericShare, double volWinShare, int minConcludedAssets,
                             int atrPeriod, int blockMonths, int blockCount, int shiftCount, int shiftMaxDays, double replayMs,
                             double budgetMinutes) {

    public static TransferConfig defaults() {
        return new TransferConfig(2484.0, 2, 0.6, 0.7, 0.6, 5, 14, 24, 4, 20, 60, 25.0, 10.0);
    }

    /** Configuration de parité (B') alignée sur la dernière bougie de l'actif ; tolérance, itérations, marges et percentile = B'. */
    public ParityConfig parity(LocalDate lastEnd) {
        return new ParityConfig(lastEnd, blockMonths, blockCount, 0.5, 40, shiftCount, shiftMaxDays, 3, 5.0, 0.8, replayMs, 5.0);
    }

    /** Minutes en série au pire pour {@code pairs} paires (blocs + historique complet, rejeux de calibrage, décalages, preset). */
    public double worstCaseMinutes(int pairs) {
        return pairs * ExposureParity.estimate(parity(LocalDate.EPOCH), blockCount).sequentialMinutes();
    }
}
