package fr.ses10doigts.tradeIO5.service.tree.scenario;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.EngineCause;
import fr.ses10doigts.tradeIO5.model.dto.event.scenario.IntentCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionSignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ActionIntent;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioStatus;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.factory.ScenarioFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Le moteur doit faire 3 choses seulement :
 * Créer / injecter un ScenarioEventEmitter
 * Appeler observe(...) et enrichFrom(...)
 * Stocker les scénarios vivants
 * 👉 Et c’est tout.
 */

@Service
public class DefaultScenarioEngine implements ScenarioEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioEngine.class);

    private final DomainClock clock;
    private final EventBus eventBus;

    final Map<ScenarioKey, MarketScenario> scenarios = new ConcurrentHashMap<>();

    // Mémoire des scénarios ayant déjà proposé un intent pour leur épisode de validation
    // continue en cours. Reset dès que le scénario quitte VALIDATED/stable (cf. collectActionIntents).
    // ⚠️ Règle "une fois par épisode" volontairement simple (Palier 1, 2026-08) : ne tient pas
    // compte d'un changement de force du signal au sein d'un même épisode (ex: BUY qui se
    // renforce fortement pendant qu'il reste VALIDATED ne redéclenche pas d'intent). À revisiter
    // si l'algorithmie décisionnelle a besoin de cette granularité plus tard.
    private final Set<String> proposedScenarioIds = ConcurrentHashMap.newKeySet();

    public DefaultScenarioEngine(DomainClock clock, EventBus eventBus) {
        this.clock = clock;
        this.eventBus = eventBus;
        // Palier 3, étape 1 : plus d'auto-abonnement à OpinionEvent ici — OpinionEvent ne porte
        // volontairement pas d'owner (lecture de marché non personnalisée, étude §A.3), donc sous
        // un moteur singleton partagé il n'existe plus de valeur sensée pour un owner implicite à
        // cet endroit. onOpinionEvent(...) reste disponible comme méthode utilitaire explicite,
        // prête à être appelée owner par owner par le futur orchestrateur (étape 7).
    }

    /**
     * Point d'entrée explicite pour traiter un {@link OpinionEvent} déjà publié sur le bus, owner
     * par owner. Non appelé par auto-abonnement depuis l'étape 1 (option B3, moteur unique
     * partagé) : {@code OpinionEvent} ne porte pas d'owner (lecture de marché non personnalisée,
     * étude §A.3), donc un moteur partagé ne peut pas savoir "pour qui" réagir via une simple
     * diffusion sur le bus — c'est à l'appelant de le décider, owner par owner.
     * <p>
     * Palier 3, étape 2 — décision actée sur la question laissée ouverte par l'addendum du
     * 2026-08-12 (étude §B) : la publication d'un {@code OpinionEvent} sur le bus est conservée,
     * pour deux raisons distinctes à toujours citer ensemble (l'une seule ne suffit pas à elle
     * seule à la justifier si l'autre disparaissait un jour) :
     * <ol>
     *     <li>Audit/persistance (étude §A.6) : {@code JpaEventStore}/{@code InMemoryEventStore}
     *     s'abonnent inconditionnellement à {@code PersistableEvent} et persistent déjà tout ce
     *     qui passe sur le bus, {@code OpinionEvent} y compris.</li>
     *     <li>Unique canal de transmission du résultat : {@code MarketOpinion.decide(...)}
     *     retourne {@code void} par contrat ("Must emit an event.") — aucune des 5 implémentations
     *     ne renvoie l'{@code OpinionSignal} calculé autrement qu'en le publiant sur le bus.
     *     Supprimer la publication reviendrait à supprimer la seule façon d'obtenir le résultat
     *     d'une Opinion, y compris pour un futur orchestrateur.</li>
     * </ol>
     * <p>
     * Patron attendu pour un futur appelant (ex: orchestrateur, étape 7) : capture synchrone via
     * {@code eventBus.subscribe(OpinionEvent.class, ...)} suivie d'un
     * {@link EventBus#unsubscribe}, puis appel à cette méthode par owner concerné — pas une
     * méthode à inventer, celle-ci existe déjà depuis l'étape 1.
     */
    @SuppressWarnings("unused") // utilitaire prêt pour le futur orchestrateur owner par owner (étape 7)
    public void onOpinionEvent(OpinionEvent event, ScenarioOwner owner) {
        log.debug("OpinionEvent reçu pour owner {}, symbole {}", owner, event.getSymbol());

        OpinionSignal result = eventToOpinionSignal(event);
        ScenarioContext context = new ScenarioContext(
                owner,
                event.getSymbol(),
                clock,
                getGlobalScenarios(owner)
        );

        onMarketOpinion(result, context);
    }


    @Override
    public void onMarketOpinion(
            OpinionSignal opinion,
            ScenarioContext context
    ) {

        // 1. enrichir le contexte avec les scénarios globaux actifs
        ScenarioContext enrichedContext = context.withGlobalScenarios(getGlobalScenarios(context.owner()));

        // 2. observer les scénarios existants
        scenarios.forEach((key, scenario) -> {
            if (isVisibleForOwner(key.owner(), enrichedContext.owner())) {
                scenario.observe(opinion, enrichedContext);
            }
        });

        // 3. proposer de nouveaux scénarios
        List<MarketScenario> created = ScenarioFactory.create(opinion, enrichedContext, eventBus);

        // 4. Si scenario existe, merge
        for (MarketScenario scenario : created) {
            scenarios.merge(
                    keyOf(scenario),
                    scenario,
                    (existing, incoming) -> {
                        existing.enrichFrom(incoming, context.clock().now());
                        return existing;
                    }
            );
        }

        // 5. Récupération des scenarios mûrs
        // Palier 3, étape 1 : bug corrigé — utiliser l'owner et l'horloge du contexte reçu en
        // paramètre, jamais un champ d'instance (qui n'existe plus depuis ce lot).
        List<ActionIntent> actionIntents = collectActionIntents(context.owner(), context.clock().now());

        log.debug("Owner {}: {} scenarios actifs, {} Intent(s)",
                context.owner(),
                scenarios.size(),
                actionIntents.size());
    }

    // ------------ Scenario manipulations -------------

    @Override
    public List<MarketScenario> getActiveScenarios(ScenarioOwner owner, Duration maxAge, Instant now) {
        return scenarios.entrySet().stream()
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .filter(s -> s.isActive(now, maxAge))
                .toList();
    }

    @Override
    public List<MarketScenario> getAllActiveScenarios(Duration maxAge, Instant now) {
        return scenarios.values().stream()
                .filter(s -> s.isActive(now, maxAge))
                .toList();
    }

    @Override
    public List<ActionIntent> collectActionIntents(ScenarioOwner owner, Instant now) {
        List<ActionIntent> intents = new ArrayList<>();

        for (var entry : scenarios.entrySet()) {
            if (!isVisibleForOwner(entry.getKey().owner(), owner)) {
                continue;
            }

            MarketScenario marketScenario = entry.getValue();

            if (marketScenario.getState().getStatus() != ScenarioStatus.VALIDATED
                    || !marketScenario.getState().isStable()) {
                // Scénario pas (ou plus) dans un épisode de validation continue : reset, libère
                // un futur nouvel épisode.
                proposedScenarioIds.remove(marketScenario.getId());
                continue;
            }

            if (proposedScenarioIds.contains(marketScenario.getId())) {
                // Déjà proposé pour cet épisode : ne pas reproposer le même intent en boucle.
                continue;
            }

            Optional<ActionIntent> proposedIntent = marketScenario.proposeIntent(now);
            if (proposedIntent.isEmpty()) {
                continue;
            }

            ActionIntent intent = proposedIntent.get();

            eventBus.publish(
                    new ScenarioEvent(
                            marketScenario,
                            ScenarioEventType.ACTION_PROPOSED,
                            new IntentCause(
                                    marketScenario.getId(),
                                    intent,
                                    intent.reason()
                            ),
                            marketScenario.getState(),
                            now
                    )
            );

            proposedScenarioIds.add(marketScenario.getId());
            intents.add(intent);
        }

        return intents;
    }

    @Override
    public void cleanup( Duration maxAge, Instant now ) {
        List<MarketScenario> toRemove = scenarios.values().stream()
                .filter(s -> !s.isActive(now, maxAge))
                .toList();

        // On retire
        toRemove.forEach(s -> scenarios.remove(keyOf(s)));

        // Purge de la mémoire de dédup : éviter une fuite mémoire silencieuse sur des scénarios
        // qui n'existent plus.
        toRemove.forEach(s -> proposedScenarioIds.remove(s.getId()));

        // On émet l'événement pour chaque scénario supprimé
        toRemove.forEach(s -> eventBus.publish(
                new ScenarioEvent(
                        s,
                        ScenarioEventType.SCENARIO_EXPIRED,
                        new EngineCause(s.getId(), "Scenario removed", "No more active"),
                        s.getState(),
                        now
                )
        ));
    }

    @Override
    public void restoreScenarios(List<MarketScenario> scenarios) {
        scenarios.forEach(s -> this.scenarios.put(keyOf(s), s));
    }

    @Override
    public void evictOwner(ScenarioOwner owner) {
        scenarios.keySet().removeIf(key -> key.owner().equals(owner));
    }

    // ---------- Purge de scénarios par symbole ----------

    /**
     * Purge tous les scénarios actifs d'un symbole donné et publie un {@code SCENARIO_EXPIRED}
     * pour chacun. Anciennement {@code removeSymbolSurvey} : renommée (Palier 3, étape 2) parce
     * que le mécanisme de liste de surveillance ({@code symbols}/{@code addSymbolSurvey}) qui
     * donnait son nom à cette méthode a été retiré — devenu inerte depuis l'étape 1 (l'auto-
     * abonnement `OpinionEvent` qu'il servait à filtrer n'existe plus, cf. addendum étude §B du
     * 2026-08-12). Cette méthode n'a jamais fait autre chose que purger ; seul son nom était mal
     * aligné sur son propre comportement réel. Aucun appelant externe trouvé au moment du
     * renommage (recherche effectuée avant de renommer).
     */
    @SuppressWarnings("unused") // future purge explicite d'un symbole retiré du périmètre suivi (roadmap Palier 3, étape 7)
    public void purgeScenariosForSymbol( String symbol ){
        List<MarketScenario> toRemove = scenarios.values().stream()
                .filter(s -> s.getSymbol().isPresent() && s.getSymbol().get().equals(symbol))
                .toList();

        toRemove.forEach(s -> {
            MarketScenario toBeDelScenario = scenarios.get(keyOf(s));
            eventBus.publish(new ScenarioEvent(
                    s,
                    ScenarioEventType.SCENARIO_EXPIRED,
                    new EngineCause(
                            toBeDelScenario.getId(),
                            "Removing all scenarios for symbol "+ symbol,
                            "Symbol purged: "+ symbol
                    ),
                    s.getState(),
                    clock.now()
            ));
            scenarios.remove(keyOf(s));
        });
    }

    // ---------- helpers ----------

    ScenarioKey keyOf(MarketScenario s) {
        return new ScenarioKey(
                s.getOwner(),
                s.getType(),
                s.getSymbol(),
                s.getScope()
        );
    }

    private boolean isVisibleForOwner(ScenarioOwner scenarioOwner, ScenarioOwner requester) {
        if (scenarioOwner instanceof ScenarioOwner.SystemOwner) return true;
        return scenarioOwner.equals(requester);
    }

    private List<MarketScenario> getGlobalScenarios(ScenarioOwner owner) {
        return scenarios.entrySet().stream()
                .filter(e -> e.getKey().symbol().isEmpty())
                .filter(e -> isVisibleForOwner(e.getKey().owner(), owner))
                .map(Map.Entry::getValue)
                .toList();
    }

    private OpinionSignal eventToOpinionSignal( OpinionEvent event ){
        return new OpinionSignal(
                event.getOpinionId(),
                event.getSymbol(),
                event.getMajoritySignal(),
                event.getWeightedSignal(),
                event.getConfidence(),
                event.getScore(),
                event.getScope(),
                event.getSources(),
                event.getReason(),
                event.getTimestamp()
        );
    }

}
