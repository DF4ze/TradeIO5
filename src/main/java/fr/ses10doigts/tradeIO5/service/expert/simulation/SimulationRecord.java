package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationErrorDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationResultDto;

import java.time.Instant;

/** Simulation en mémoire (jamais en base) : identité immuable, état et issue publiés par le thread de calcul (volatile). */
final class SimulationRecord {

    private static final double PROGRESS_LOADING = 0.1;
    private static final double PROGRESS_COMPUTING = 0.6;

    final String id;
    final Long ownerId;
    final SimulationType type;
    final String assetSymbol;
    final Instant createdAt;

    private volatile SimulationState state = SimulationState.QUEUED;
    private volatile double progress;
    private volatile Instant finishedAt;
    private volatile SimulationResultDto result;
    private volatile SimulationErrorDto error;
    private volatile boolean cancelled;

    SimulationRecord(String id, Long ownerId, SimulationType type, String assetSymbol, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.assetSymbol = assetSymbol;
        this.createdAt = createdAt;
    }

    SimulationState state() {
        return state;
    }

    double progress() {
        return progress;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    SimulationResultDto result() {
        return result;
    }

    SimulationErrorDto error() {
        return error;
    }

    boolean cancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
    }

    void loading() {
        progress = PROGRESS_LOADING;
        state = SimulationState.LOADING_DATA;
    }

    void computing() {
        progress = PROGRESS_COMPUTING;
        state = SimulationState.COMPUTING;
    }

    void done(SimulationResultDto outcome, Instant now) {
        result = outcome;
        progress = 1.0;
        finishedAt = now;
        state = SimulationState.DONE;
    }

    void fail(SimulationErrorDto failure, Instant now) {
        error = failure;
        finishedAt = now;
        state = SimulationState.FAILED;
    }
}
