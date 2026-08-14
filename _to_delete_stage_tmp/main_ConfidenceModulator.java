package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;

/**
 * Contrat commun aux mécanismes de modulation de confidence (étude
 * "unification-confidence-modulator", 2026-07-16). Avant ce lot, trois fonctions vivaient côte à
 * côte dans {@code MarketOpinionHelper} ({@code computeSentimentShiftDampening},
 * {@code computeStalenessDampening}, {@code computeConfidenceModulationFactor}), chacune branchée
 * séparément dans une seule {@code MarketOpinion}, sans aucun contrat commun. Cette interface les
 * unifie sans changer leur comportement : chaque implémentation enrobe une des trois fonctions
 * existantes ({@link SentimentShiftModulator}, {@link StalenessModulator},
 * {@link StrategyConfidenceModulator}) sans les modifier — mêmes signatures, même comportement.
 * <p>
 * Un {@code ConfidenceModulator} n'atténue jamais que la {@code confidence} d'une
 * {@code MarketOpinion} — jamais son score directionnel — et ne peut jamais l'annuler complètement
 * (cf. {@link ModulationResult#factor()}).
 * <p>
 * <b>Décision de conception</b> (étude §5, point 1 — nom/package de l'interface) : nom et package
 * retenus tels que proposés dans l'étude. {@code ConfidenceModulator} décrit précisément le rôle
 * (moduler une confidence, jamais un score) sans référence à {@code Strategy} : seul
 * {@link StrategyConfidenceModulator} en dépend, les deux autres implémentations n'en ont besoin
 * d'aucune. Package {@code service.tree.opinion.modulator} : rattaché à {@code opinion} (les trois
 * implémentations sont consommées par des {@code MarketOpinion}) plutôt qu'à {@code strategy} ou
 * {@code helper}.
 * <p>
 * <b>Décision de conception</b> (étude §5, point 3 — Registry ou liste manuelle) : pas de
 * {@code ConfidenceModulatorRegistry} dans ce lot. Avec seulement 3 implémentations, chacune
 * construite avec des valeurs déjà résolues au moment de l'appel (indicateurs fetchés, Strategy à
 * évaluer), un registry n'apporterait ni découverte dynamique utile ni simplification — seulement de
 * l'indirection. Les trois {@code MarketOpinion} concernées construisent leur(s) modulateur(s) "à la
 * main" dans {@code decide()}. À reconsidérer si de nouveaux modulateurs (ATR, calendrier macro,
 * ETF_FLOW — étude §4, hors scope ici) rendent la liste significativement plus longue.
 */
public interface ConfidenceModulator {

    /**
     * @param context    contexte de la décision en cours (utilisé par
     *                   {@link StrategyConfidenceModulator} pour évaluer sa {@code Strategy}
     *                   sous-jacente ; ignoré par les implémentations qui reçoivent déjà leurs
     *                   valeurs résolues à la construction, ex. {@link SentimentShiftModulator}/
     *                   {@link StalenessModulator})
     * @param parameters paramètres de la {@code MarketOpinion} appelante ; même remarque que
     *                   {@code context}
     * @return le résultat de la modulation, jamais {@code null}
     */
    ModulationResult evaluate(OpinionContext context, MarketOpinionParameters parameters);
}
