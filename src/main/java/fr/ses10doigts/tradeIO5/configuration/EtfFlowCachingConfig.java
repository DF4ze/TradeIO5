package fr.ses10doigts.tradeIO5.configuration;

import fr.ses10doigts.tradeIO5.repository.market.EtfFlowSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.CachingEtfFlowClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.SosoValueEtfFlowClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enveloppe {@link SosoValueEtfFlowClient} dans un {@link CachingEtfFlowClient}
 * (docs/etude-cache-etf-flow-historisation.md) — même patron que {@link MarketDataCachingConfig}
 * pour les candles.
 * <p>
 * Type de retour {@link CachingEtfFlowClient} (concret), pas {@link EtfFlowProvider} : ce bean doit
 * satisfaire deux consommateurs différents — {@code EtfFlowIndicator} (injection par l'interface
 * {@link EtfFlowProvider}, seul bean de ce type dans le contexte désormais que
 * {@code SosoValueEtfFlowClient} n'est plus {@code @Component}) et {@code EtfFlowHistorizationJob}
 * (a besoin de {@link CachingEtfFlowClient#refresh}, absent de l'interface). Spring résout les deux
 * injections vers la même instance singleton, exposée sous son type concret ET ses interfaces.
 */
@Configuration
public class EtfFlowCachingConfig {

    @Bean
    public CachingEtfFlowClient cachingEtfFlowClient(EtfFlowSnapshotRepository repository, DomainClock clock) {
        return new CachingEtfFlowClient(new SosoValueEtfFlowClient(), repository, clock);
    }
}
