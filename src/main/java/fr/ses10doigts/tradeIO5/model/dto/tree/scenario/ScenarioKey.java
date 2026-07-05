package fr.ses10doigts.tradeIO5.model.dto.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;

import java.util.Optional;

/**
 * @param scope Scope de l'opinion à l'origine du scénario (LOCAL/GLOBAL/MACRO/EXTERNAL).
 *              Inclus dans la clé pour qu'une opinion LOCAL et une opinion EXTERNAL portant
 *              sur le même symbole/type ne se recouvrent jamais silencieusement dans la map
 *              de {@code DefaultScenarioEngine} (voir étude "extension-risk-macro-external") :
 *              elles coexistent comme deux scénarios distincts.
 */
public record ScenarioKey(
        ScenarioOwner owner,
        ScenarioType type,
        Optional<String> symbol,
        OpinionScope scope
) {}
