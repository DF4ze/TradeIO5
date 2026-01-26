package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.MarketOpinionParameters;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.OpinionType;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registre central des Opinions disponibles.
 * Permet de les retrouver par type.
 */
@Component
public class MarketOpinionRegistry {

    private final Map<String, MarketOpinion> registry = new ConcurrentHashMap<>();

    public MarketOpinionRegistry(List<MarketOpinion> marketOpinions){
        marketOpinions.forEach(d -> registry.put(d.getName(), d));
    }

    /**
     * Récupère les opinions par type.
     */
    public List<MarketOpinion> get(OpinionType type) {
        return registry.values().stream()
                        .filter(d -> d.getType() == type)
                        .toList();
    }

    /**
     * Récupère une opinion par son nom.
     */
    public MarketOpinion get(String name) {
        return registry.get(name);
    }

    /**
     * Récupère toutes les opinions enregistrées.
     */
    public Collection<MarketOpinion> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /**
     * Récupère les MarketOpinion activées selon les paramètres.
     */
    public List<MarketOpinion> getEnabled(MarketOpinionParameters parameters) {
        return registry.values().stream()
                .filter(d -> parameters == null || parameters.isEnabled(d.getName()))
                .toList();
    }
}
