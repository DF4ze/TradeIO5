package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.execution.exchange.SpotOrderPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Routage des ports d'ordres par exchange : <b>seul à voir l'ensemble des {@link SpotOrderPort}</b> ({@code OrderExecutor},
 * {@code StepRunner} et la page Admin passent par lui). Un port n'existe que si LIVE est déverrouillé : sans port pour un
 * provider, {@link #forProvider} est vide et seules les étapes de ce provider sont bloquées. Deux ports pour le même
 * provider font échouer le démarrage.
 */
@Component
public class SpotOrderRouter {

    private final Map<WebProviderCode, SpotOrderPort> byProvider = new EnumMap<>(WebProviderCode.class);

    @Autowired
    public SpotOrderRouter(ObjectProvider<SpotOrderPort> ports) {
        this(ports.stream().toList());
    }

    public SpotOrderRouter(Collection<SpotOrderPort> ports) {
        for (SpotOrderPort port : ports) {
            if (byProvider.putIfAbsent(port.provider(), port) != null) {
                throw new IllegalStateException("Deux ports d'ordres pour le même exchange : " + port.provider());
            }
        }
    }

    public Optional<SpotOrderPort> forProvider(WebProviderCode provider) {
        return Optional.ofNullable(provider).map(byProvider::get);
    }

    /** Exchanges disposant d'un port d'ordres actif (vide hors LIVE déverrouillé), triés. */
    public List<WebProviderCode> providers() {
        return byProvider.keySet().stream().sorted().toList();
    }
}
