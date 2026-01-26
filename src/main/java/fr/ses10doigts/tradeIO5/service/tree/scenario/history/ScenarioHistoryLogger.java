package fr.ses10doigts.tradeIO5.service.tree.scenario.history;

import fr.ses10doigts.tradeIO5.model.enumerate.market.ExecutionMode;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;

public interface ScenarioHistoryLogger {

    /**
     * Enregistre un changement d'état d'un scénario.
     * Peut persister en DB, log en console, ou les deux selon mode.
     */
    void logChange(MarketScenario scenario, ExecutionMode mode);

}
