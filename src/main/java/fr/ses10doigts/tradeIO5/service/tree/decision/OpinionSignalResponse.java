package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;

import java.time.Instant;
import java.util.Set;

/**
 * Réponse JSON pour {@code OpinionAdminController} (plan de test manuel Palier 3, 2026-08-17) : forme
 * plate d'un {@link OpinionSignal}, mêmes noms de champs que {@code TreeAnalysisMcpTools.opinionResponse}
 * (le tool MCP {@code get_opinion}), pour que la comparaison REST/MCP soit directe lors d'un test
 * manuel. Nécessaire parce que {@code OpinionSignal.symbol()} est un {@code Optional<String>} — le
 * projet n'embarque pas {@code jackson-datatype-jdk8} (vérifié dans {@code pom.xml}), donc le
 * sérialiser tel quel produirait un JSON incorrect ; ce record le convertit en {@code String} nullable
 * avant sérialisation, comme le fait déjà {@code opinionResponse} côté MCP.
 */
public record OpinionSignalResponse(
        String opinionId,
        String symbol,
        String majoritySignal,
        String weightedSignal,
        double confidence,
        double score,
        String scope,
        Set<String> sources,
        String reason,
        Instant timestamp
) {
    public static OpinionSignalResponse from(OpinionSignal signal) {
        return new OpinionSignalResponse(
                signal.opinionId(),
                signal.symbol().orElse(null),
                signal.majoritySignal() != null ? signal.majoritySignal().name() : null,
                signal.weightedSignal() != null ? signal.weightedSignal().name() : null,
                signal.confidence(),
                signal.score(),
                signal.scope() != null ? signal.scope().name() : null,
                signal.sources(),
                signal.reason(),
                signal.timestamp()
        );
    }
}
