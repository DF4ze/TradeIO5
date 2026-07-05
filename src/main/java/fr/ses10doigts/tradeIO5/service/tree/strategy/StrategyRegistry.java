package fr.ses10doigts.tradeIO5.service.tree.strategy;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class StrategyRegistry {

    private static final Logger logger = LoggerFactory.getLogger(StrategyRegistry.class);

    private final Map<String, Strategy> strategies;

    public StrategyRegistry(List<Strategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(
                        s -> s.getClass().getSimpleName(),
                        Function.identity()
                ));
    }

    public List<Strategy> getAll() {
        return List.copyOf(strategies.values());
    }

    public List<Strategy> getByType(StrategyType strategyName) {
        return strategies.values().stream()
                .filter(s -> s.getType().contains(strategyName))
                .toList();
    }

    public Strategy get(String name) {
        if( !strategies.containsKey(name) )
            throw new IllegalArgumentException("Unknown strategy : "+name);

        return strategies.get(name);
    }

    /**
     * Résout une Strategy par {@link StrategyType}, en désambiguïsant entre plusieurs
     * candidates via {@link Strategy#accepts(StrategyParameters)} plutôt que de prendre le
     * premier match au hasard (cf. bug découvert en testant l'opinion GLOBAL/FearGreedStrategy
     * via le MCP : {@code TreeAnalysisFacade}/{@code TreeAnalysisMcpTools} résolvaient toutes
     * deux par type seul, ce qui sélectionnait TrendConfirmationStrategy même quand l'appelant
     * fournissait des paramètres FEAR_GREED destinés à FearGreedStrategy).
     */
    public Strategy resolveBestMatch(StrategyType type, StrategyParameters parameters) {
        List<Strategy> matches = getByType(type);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No Strategy registered for type: " + type);
        }

        List<Strategy> accepted = matches.stream()
                .filter(s -> s.accepts(parameters))
                .toList();

        if (accepted.size() == 1) {
            return accepted.get(0);
        }

        if (accepted.size() > 1) {
            logger.warn("Several strategies of type {} accept these parameters ({}), using the first one: {}",
                    type, accepted, accepted.get(0).getName());
            return accepted.get(0);
        }

        // Aucune Strategy n'accepte explicitement ces paramètres (ex: Strategy qui ne
        // surcharge pas accepts(), ou paramètres ne correspondant à aucune Strategy connue) :
        // on retombe sur l'ancien comportement (premier match par type) plutôt que d'échouer,
        // pour ne pas casser un appelant existant qui fonctionnait déjà ainsi.
        logger.warn("No strategy of type {} explicitly accepts these parameters ; falling back to the first match: {}",
                type, matches.get(0).getName());
        return matches.get(0);
    }
}
