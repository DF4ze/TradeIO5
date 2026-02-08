package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;

import java.time.Instant;

public record ScenarioSnapshot(

        String scenarioId,

        ScenarioStatus state,

        Instant createdAt,

        Instant lastUpdatedAt,

        /** Nombre d’opinions observées */
        int observationsCount,

        /** Dernière opinion reçue */
        OpinionSignal lastOpinion,

        /** Score / confiance interne du scénario */
        double confidence,

        /** Raison synthétique (lisible humain) */
        String reason

) {}
