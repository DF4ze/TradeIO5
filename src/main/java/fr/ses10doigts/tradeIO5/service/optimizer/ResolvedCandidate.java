package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.service.dca.atr.AthCap;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicy;

/**
 * Ce que le scorer comprend d'un jeu de paramètres : politique de modulation, plafond ATH, multiple du montant de base de référence
 * de chaque lien ({@code null} = 1). Le scorer ne voit jamais les noms de paramètres.
 */
public record ResolvedCandidate(ModulationPolicy policy, AthCap athCap, Double baseMultiplier) {
}
