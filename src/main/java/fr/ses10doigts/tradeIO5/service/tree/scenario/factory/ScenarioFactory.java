package fr.ses10doigts.tradeIO5.service.tree.scenario.factory;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;

import java.util.List;

public interface ScenarioFactory {

    /**
     * Crée des scénarios émergents à partir d'une opinion et d'un contexte.
     * Peut retourner une liste vide si aucun pattern détecté.
     */
    List<MarketScenario> create(MarketOpinionResult opinion, ScenarioContext context);

}