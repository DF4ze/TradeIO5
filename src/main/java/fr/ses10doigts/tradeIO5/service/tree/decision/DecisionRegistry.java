package fr.ses10doigts.tradeIO5.service.tree.decision;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.DecisionType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre central des Decision disponibles.
 * Permet de les retrouver par type.
 */
@Component
public class DecisionRegistry {

    private final Map<String, Decision> registry = new ConcurrentHashMap<>();

    public DecisionRegistry( List<Decision> decisions ){
        decisions.forEach(d -> registry.put(d.getName(), d));
    }

    /**
     * Récupère les décisions par type.
     */
    public List<Decision> get(DecisionType type) {
        return registry.values().stream()
                        .filter(d -> d.getType() == type)
                        .toList();
    }

    /**
     * Récupère une décision par son nom.
     */
    public Decision get(String name) {
        return registry.get(name);
    }

    /**
     * Récupère toutes les décisions enregistrées.
     */
    public Collection<Decision> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * Récupère les décisions activées selon les paramètres.
     */
    public List<Decision> getEnabled(DecisionParameters parameters) {
        return registry.values().stream()
                .filter(d -> parameters == null || parameters.isEnabled(d.getName()))
                .toList();
    }
}
