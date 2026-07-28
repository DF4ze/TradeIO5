package fr.ses10doigts.tradeIO5.configuration;

import fr.ses10doigts.tradeIO5.repository.market.EtfFlowSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.EtfFlowProvider;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.etfflow.FarsideEtfFlowClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.CachingEtfFlowClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.sosovalue.SosoValueEtfFlowClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Enveloppe {@link SosoValueEtfFlowClient} dans un {@link CachingEtfFlowClient}
 * (docs/etude-cache-etf-flow-historisation.md) — même patron que {@link MarketDataCachingConfig}
 * pour les candles.
 * <p>
 * Type de retour des {@code @Bean} volontairement concret (pas les interfaces) : ces beans doivent
 * satisfaire plusieurs consommateurs différents — {@code EtfFlowIndicator} (injection par
 * l'interface {@link EtfFlowProvider}), {@code EtfFlowHistorizationJob} (a besoin de
 * {@link CachingEtfFlowClient#refresh}, absent de l'interface), et {@code EtfFlowBackfillService}
 * (a besoin de {@link SosoValueEtfFlowClient#fetchHistory}, absent de {@link CachingEtfFlowClient}
 * comme de l'interface — d'où le bean {@code SosoValueEtfFlowClient} séparé plutôt qu'un
 * {@code new} inline comme avant l'ajout du backfill).
 * <p>
 * <b>{@code @Primary} nécessaire sur {@code cachingEtfFlowClient}</b> depuis l'ajout du bean
 * {@code SosoValueEtfFlowClient} : {@code SosoValueEtfFlowClient} implémente lui aussi
 * {@link EtfFlowProvider}, donc le contexte contient désormais 2 beans candidats pour ce type
 * (confirmé par échec de démarrage réel : {@code NoUniqueBeanDefinitionException} sur
 * {@code EtfFlowIndicator}, "found 2: sosoValueEtfFlowClient,cachingEtfFlowClient") — sans
 * {@code @Primary}, plus aucun moyen de savoir lequel des deux {@code EtfFlowIndicator} doit
 * recevoir. {@code CachingEtfFlowClient} doit rester le choix par défaut (jamais le client brut,
 * qui court-circuiterait tout le cache).
 * <p>
 * <b>{@code FarsideEtfFlowClient} réintroduit comme bean le 2026-07-17</b> (addendum backfill
 * Farside, docs/etude-cache-etf-flow-historisation.md) : un 3e candidat {@link EtfFlowProvider}
 * dans le contexte, mais sans casser {@code @Primary} ci-dessus (une seule annotation
 * {@code @Primary} suffit à lever l'ambiguïté quel que soit le nombre de candidats). Uniquement
 * injecté dans {@code EtfFlowBackfillService}, jamais dans {@code EtfFlowIndicator}.
 */
@Configuration
public class EtfFlowCachingConfig {

    @Bean
    public SosoValueEtfFlowClient sosoValueEtfFlowClient() {
        return new SosoValueEtfFlowClient();
    }

    @Bean
    public FarsideEtfFlowClient farsideEtfFlowClient() {
        return new FarsideEtfFlowClient();
    }

    @Bean
    @Primary
    public CachingEtfFlowClient cachingEtfFlowClient(
            SosoValueEtfFlowClient sosoValueEtfFlowClient, EtfFlowSnapshotRepository repository, DomainClock clock) {
        return new CachingEtfFlowClient(sosoValueEtfFlowClient, repository, clock);
    }
}
