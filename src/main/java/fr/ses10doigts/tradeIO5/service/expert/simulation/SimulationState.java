package fr.ses10doigts.tradeIO5.service.expert.simulation;

/** Cycle de vie d'une simulation asynchrone. */
public enum SimulationState {
    QUEUED, LOADING_DATA, COMPUTING, DONE, FAILED;

    public boolean isActive() {
        return this == QUEUED || this == LOADING_DATA || this == COMPUTING;
    }
}
