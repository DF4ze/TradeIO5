package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

/**
 * Résultat d'une modulation de confidence (étude "unification-confidence-modulator", 2026-07-16,
 * §2). Contrat volontairement minimal — seulement ce dont un appelant a besoin pour composer
 * plusieurs modulations sans jamais toucher au score directionnel d'une {@code MarketOpinion}.
 *
 * @param applied {@code false} = donnée indisponible, ignorer purement et simplement (ne doit
 *                jamais invalider la {@code MarketOpinion} appelante, ni son score) ; {@code true} =
 *                la modulation a bien été calculée, que {@code factor} vaille {@code 1.0} (aucune
 *                atténuation nécessaire) ou moins.
 * @param factor  facteur multiplicatif appliqué uniquement à la confidence, jamais au score
 *                directionnel. Toujours dans {@code ]0,1]} : {@code 1.0} = aucune atténuation,
 *                jamais {@code 0} — une seule modulatrice ne doit jamais pouvoir annuler entièrement
 *                la confidence (garantie portée par chaque fonction {@code MarketOpinionHelper}
 *                enrobée, pas par ce record lui-même).
 * @param reason  explication courte à des fins de log/traçabilité.
 */
public record ModulationResult(boolean applied, double factor, String reason) {
}
