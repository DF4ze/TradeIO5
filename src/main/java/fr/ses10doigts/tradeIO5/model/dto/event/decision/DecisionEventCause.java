package fr.ses10doigts.tradeIO5.model.dto.event.decision;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Palier 3, étape 4 : {@code @JsonTypeInfo}/{@code @JsonSubTypes} ajoutés (même correctif et même
 * découverte que {@link fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner}, cf. sa
 * javadoc).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ActionStepExecutedCause.class, name = "ACTION_STEP_EXECUTED"),
        @JsonSubTypes.Type(value = ActionStepFailedCause.class, name = "ACTION_STEP_FAILED"),
        @JsonSubTypes.Type(value = DecisionCreatedCause.class, name = "DECISION_CREATED"),
        @JsonSubTypes.Type(value = DecisionExecutedCause.class, name = "DECISION_EXECUTED"),
        @JsonSubTypes.Type(value = DecisionClosedCause.class, name = "DECISION_CLOSED")
})
public sealed interface DecisionEventCause
        permits ActionStepExecutedCause,
                ActionStepFailedCause,
                DecisionCreatedCause,
                DecisionExecutedCause,
                DecisionClosedCause
{}
