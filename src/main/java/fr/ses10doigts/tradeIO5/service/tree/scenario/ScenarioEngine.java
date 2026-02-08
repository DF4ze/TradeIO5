package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface ScenarioEngine {

    /**
     * Injection d’une nouvelle opinion de marché.
     * Peut créer, mettre à jour, invalider ou expirer des scénarios.
     */
    void onMarketOpinion(
            OpinionSignal opinion,
            ScenarioContext context
    );

    /**
     * Tous les scénarios actifs (EMERGING / CONFIRMING / VALIDATED)
     */
    List<MarketScenario> getActiveScenarios(ScenarioOwner owner, Duration maxAge, Instant now);

    /**
     * Scénarios prêts à proposer une intention d’action
     */
    List<ActionIntent> collectActionIntents(ScenarioOwner owner, Instant now);

    /**
     * Nettoyage explicite (expiration, purge)
     */
    void cleanup(Duration maxAge, Instant now);
}
