package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioOwner;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


/**
 *
 * @param owner Propriétaire qui détient le scenario
 * @param symbol Un éventuel symbol, sinon contexte global
 * @param clock Source du temps
 * @param now Dernier instant observé
 * @param globalScenarios Scenarios qui sont globaux
 */
public record ScenarioContext(

        ScenarioOwner owner,
        Optional<String> symbol,
        DomainClock clock,
        Instant now,
        List<MarketScenario> globalScenarios

) {
    public ScenarioContext {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(now);
    }

    public ScenarioContext withGlobalScenarios(List<MarketScenario> globals) {
        return new ScenarioContext(owner, symbol, clock, now, globals);
    }
}
