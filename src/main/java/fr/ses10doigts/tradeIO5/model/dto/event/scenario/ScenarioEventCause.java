package fr.ses10doigts.tradeIO5.model.dto.event.scenario;

public sealed interface ScenarioEventCause permits
        OpinionCause,
        EnrichmentCause,
        TimeCause,
        InvalidityCause,
        EngineCause,
        IntentCause
{

}