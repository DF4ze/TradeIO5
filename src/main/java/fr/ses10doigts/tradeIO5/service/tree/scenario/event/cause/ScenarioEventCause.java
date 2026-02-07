package fr.ses10doigts.tradeIO5.service.tree.scenario.event.cause;

public sealed interface ScenarioEventCause
        permits OpinionCause, EnrichmentCause, TimeCause, InvalidityCause, EngineCause {

    String type();
}