package fr.ses10doigts.tradeIO5.model.dto.event.scenario;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Palier 3, étape 4 : {@code @JsonTypeInfo}/{@code @JsonSubTypes} ajoutés (même correctif et même
 * découverte que {@link fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner}, cf. sa
 * javadoc).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = OpinionCause.class, name = "OPINION"),
        @JsonSubTypes.Type(value = EnrichmentCause.class, name = "ENRICHMENT"),
        @JsonSubTypes.Type(value = TimeCause.class, name = "TIME"),
        @JsonSubTypes.Type(value = InvalidityCause.class, name = "INVALIDITY"),
        @JsonSubTypes.Type(value = EngineCause.class, name = "ENGINE"),
        @JsonSubTypes.Type(value = IntentCause.class, name = "INTENT")
})
public sealed interface ScenarioEventCause permits
        OpinionCause,
        EnrichmentCause,
        TimeCause,
        InvalidityCause,
        EngineCause,
        IntentCause
{

}