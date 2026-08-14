package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;

/**
 * Adaptateur {@link ConfidenceModulator} pour
 * {@link MarketOpinionHelper#computeSentimentShiftDampening} (étude
 * "unification-confidence-modulator", 2026-07-16, §2), seul consommateur : {@code GlobalMarketOpinion}.
 * La fonction enrobée n'est pas modifiée (même signature, même comportement) — cette classe se
 * contente de porter les valeurs déjà résolues par {@code GlobalMarketOpinion.decide} (lecture du
 * snapshot FEAR_GREED) et de les lui transmettre telles quelles.
 * <p>
 * {@link #evaluate} ignore {@code context}/{@code parameters} : contrairement à
 * {@link StrategyConfidenceModulator}, cette modulation ne dépend d'aucun état de la décision en
 * cours au moment de l'évaluation — seulement des valeurs déjà connues au moment de la construction.
 */
public class SentimentShiftModulator implements ConfidenceModulator {

    private final double now;
    private final Double yesterday;
    private final double buyThreshold;
    private final double sellThreshold;
    private final double deltaThreshold;

    public SentimentShiftModulator(
            double now, Double yesterday, double buyThreshold, double sellThreshold, double deltaThreshold) {
        this.now = now;
        this.yesterday = yesterday;
        this.buyThreshold = buyThreshold;
        this.sellThreshold = sellThreshold;
        this.deltaThreshold = deltaThreshold;
    }

    @Override
    public ModulationResult evaluate(OpinionContext context, MarketOpinionParameters parameters) {
        if (yesterday == null) {
            // Même repli que MarketOpinionHelper.computeSentimentShiftDampening : pas de donnée
            // "yesterday", pas d'atténuation possible.
            return new ModulationResult(false, 1.0, "yesterday indisponible, aucune atténuation possible");
        }
        double factor = MarketOpinionHelper.computeSentimentShiftDampening(
                now, yesterday, buyThreshold, sellThreshold, deltaThreshold);
        String reason = String.format(
                "sentimentShift now=%.2f yesterday=%.2f factor=%.4f", now, yesterday, factor);
        return new ModulationResult(true, factor, reason);
    }
}
