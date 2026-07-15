package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;

import java.util.List;

/**
 * Boucle commune d'application des {@link ConfidenceModulator} (étude
 * "unification-confidence-modulator", 2026-07-16, §2 et §5 point 4 — "où vit la boucle
 * d'application commune").
 * <p>
 * <b>Décision de conception</b> : classe utilitaire statique plutôt qu'un service injecté/bean
 * Spring. Les trois {@code MarketOpinion} concernées ({@code GlobalMarketOpinion},
 * {@code MacroMarketOpinion}, {@code AbstractMarketOpinion}) construisent déjà leurs modulateurs "à
 * la main" avec des valeurs qu'elles viennent de résoudre elles-mêmes (indicateurs fetchés via
 * {@code IndicatorEngine}, {@code Strategy} à évaluer) : il n'y a ni état partagé ni dépendance à
 * injecter, seulement une fonction pure appliquée à une liste. Une classe statique évite l'aller-
 * retour Spring pour un simple produit de facteurs.
 * <p>
 * {@link #evaluateAll} et {@link #combinedFactor} sont volontairement séparées plutôt qu'une seule
 * méthode qui ne retournerait que le facteur combiné : {@code AbstractMarketOpinion} a besoin du
 * détail {@link ModulationResult} par modulateur (traçabilité dans
 * {@code AggregatedStrategySignal.signals}, log warn sur les modulateurs invalides), là où
 * {@code GlobalMarketOpinion}/{@code MacroMarketOpinion} ne consomment que le facteur combiné final.
 */
public final class ConfidenceModulation {

    private ConfidenceModulation() {
    }

    /** Évalue chaque modulateur une seule fois (jamais de ré-évaluation), dans l'ordre fourni. */
    public static List<ModulationResult> evaluateAll(
            List<? extends ConfidenceModulator> modulators, OpinionContext context, MarketOpinionParameters parameters) {
        return modulators.stream().map(m -> m.evaluate(context, parameters)).toList();
    }

    /**
     * Produit des facteurs des résultats {@code applied}, les autres étant simplement ignorés
     * (jamais d'atténuation calculée à partir d'une donnée indisponible) — jamais de score
     * directionnel touché, cf. {@link ModulationResult}.
     */
    public static double combinedFactor(List<ModulationResult> results) {
        double factor = 1.0;
        for (ModulationResult result : results) {
            if (result.applied()) {
                factor *= result.factor();
            }
        }
        return factor;
    }
}
