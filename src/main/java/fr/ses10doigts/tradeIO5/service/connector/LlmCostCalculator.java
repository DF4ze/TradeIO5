package fr.ses10doigts.tradeIO5.service.connector;

import fr.ses10doigts.tradeIO5.configuration.properties.OpenAIProperties;
import fr.ses10doigts.tradeIO5.model.entity.llm.LlmCallLogEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collection;
import java.util.Optional;

/**
 * Calcule le coût réel d'un ou plusieurs {@link LlmCallLogEntity} à la demande, à partir du
 * tarif courant configuré ({@code tradeio.openai.pricing.<model>.input/.output}, prix par
 * million de tokens). Aucun coût n'est stocké : seul le nombre de tokens l'est (cf. l'entité),
 * ce calculateur sert de point unique de lecture pour une requête/rapport ponctuel.
 */
@Service
@RequiredArgsConstructor
public class LlmCostCalculator {

    private final Logger logger = LoggerFactory.getLogger(LlmCostCalculator.class);

    private static final BigDecimal PER_MILLION = BigDecimal.valueOf(1_000_000);

    private final OpenAIProperties props;

    /**
     * Coût du log donné, ou {@link Optional#empty()} si aucun tarif n'est configuré pour son
     * modèle (pas d'exception : un tarif manquant ne doit pas casser un rapport agrégé).
     */
    public Optional<BigDecimal> cost(LlmCallLogEntity log) {
        OpenAIProperties.ModelPricing pricing = props.pricing().get(log.getModel());

        if (pricing == null) {
            logger.warn("Aucun tarif configuré pour le modèle {} (tradeio.openai.pricing.{}.input/.output) — coût non calculable pour le log id={}",
                    log.getModel(), log.getModel(), log.getId());
            return Optional.empty();
        }

        BigDecimal inputCost = pricing.input()
                .multiply(BigDecimal.valueOf(Optional.ofNullable(log.getInputTokens()).orElse(0L)))
                .divide(PER_MILLION, MathContext.DECIMAL64);

        BigDecimal outputCost = pricing.output()
                .multiply(BigDecimal.valueOf(Optional.ofNullable(log.getOutputTokens()).orElse(0L)))
                .divide(PER_MILLION, MathContext.DECIMAL64);

        return Optional.of(inputCost.add(outputCost));
    }

    /**
     * Coût cumulé d'une liste de logs (ex. résultat d'une agrégation par callSite sur une
     * période). Les logs dont le modèle n'a pas de tarif configuré sont ignorés silencieusement
     * (déjà loggés individuellement par {@link #cost(LlmCallLogEntity)}) — le total reste donc
     * une borne basse si un tarif manque.
     */
    public BigDecimal cost(Collection<LlmCallLogEntity> logs) {
        return logs.stream()
                .map(this::cost)
                .flatMap(Optional::stream)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
