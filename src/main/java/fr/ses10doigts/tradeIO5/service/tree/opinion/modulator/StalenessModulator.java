package fr.ses10doigts.tradeIO5.service.tree.opinion.modulator;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;

import java.time.Instant;
import java.util.List;

/**
 * Adaptateur {@link ConfidenceModulator} pour {@link MarketOpinionHelper#computeStalenessDampening}
 * (étude "unification-confidence-modulator", 2026-07-16, §2), seul consommateur :
 * {@code MacroMarketOpinion} (fraîcheur des quotes SP500/NASDAQ). La fonction enrobée n'est pas
 * modifiée (même signature, même comportement).
 * <p>
 * <b>Décision de conception</b> (étude §5, point 2 — instance unique vs une par indicateur) : une
 * seule instance de {@code StalenessModulator} reçoit la liste des {@link StalenessInput} (un par
 * quote : SP500, NASDAQ) et retient le facteur le plus conservateur (minimum), exactement comme le
 * faisait {@code MacroMarketOpinion.decide} avant ce lot ({@code Math.min(sp500Staleness,
 * nasdaqStaleness)}). C'est l'option la plus fidèle au comportement existant à l'identique — une
 * instance par indicateur aurait exigé de recomposer le {@code Math.min} dans
 * {@code MacroMarketOpinion} elle-même, ce qui aurait juste déplacé la logique sans la simplifier.
 * <p>
 * {@code applied} vaut toujours {@code true} ici (contrairement à {@link SentimentShiftModulator}) :
 * {@link MarketOpinionHelper#computeStalenessDampening} gère déjà nativement l'absence de timestamp
 * (retombe sur {@code 1.0}, neutre) pour chaque entrée individuellement — il n'y a donc pas de cas où
 * la modulation "ne peut pas être calculée" au niveau de cet adaptateur, seulement des cas où elle
 * ne change rien.
 */
public class StalenessModulator implements ConfidenceModulator {

    /** Une quote à surveiller (ex. SP500, NASDAQ), avec son timestamp de dernière transaction. */
    public record StalenessInput(String label, Long lastTradeTimeEpochSeconds) {
    }

    private final Instant now;
    private final double staleThresholdHours;
    private final List<StalenessInput> inputs;

    public StalenessModulator(Instant now, double staleThresholdHours, StalenessInput... inputs) {
        this.now = now;
        this.staleThresholdHours = staleThresholdHours;
        this.inputs = List.of(inputs);
    }

    @Override
    public ModulationResult evaluate(OpinionContext context, MarketOpinionParameters parameters) {
        double factor = 1.0;
        String mostStaleLabel = null;

        for (StalenessInput input : inputs) {
            double indicatorFactor = MarketOpinionHelper.computeStalenessDampening(
                    input.lastTradeTimeEpochSeconds(), now, staleThresholdHours);
            if (indicatorFactor < factor) {
                factor = indicatorFactor;
                mostStaleLabel = input.label();
            }
        }

        String reason = mostStaleLabel != null
                ? String.format("staleness factor=%.4f (plus limitant: %s)", factor, mostStaleLabel)
                : "aucune donnée de fraîcheur limitante, aucune atténuation";
        return new ModulationResult(true, factor, reason);
    }
}
