package fr.ses10doigts.tradeIO5.service.tree.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.event.DecisionEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.ScenarioEvent;
import fr.ses10doigts.tradeIO5.model.dto.event.decision.DecisionCreatedCause;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionSnapshotPayload;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioDefinition;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioOwner;
import fr.ses10doigts.tradeIO5.model.dto.tree.scenario.ScenarioState;
import fr.ses10doigts.tradeIO5.model.entity.tree.EventEntity;
import fr.ses10doigts.tradeIO5.model.entity.tree.decision.DecisionSnapshotEntity;
import fr.ses10doigts.tradeIO5.model.entity.tree.scenario.ScenarioSnapshotEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionEventType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.decision.DecisionStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.opinion.OpinionScope;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.scenario.ScenarioType;
import fr.ses10doigts.tradeIO5.repository.decision.DecisionSnapshotRepository;
import fr.ses10doigts.tradeIO5.repository.decision.EventRepository;
import fr.ses10doigts.tradeIO5.repository.scenario.ScenarioSnapshotRepository;
import fr.ses10doigts.tradeIO5.service.tree.event.engine.EventBus;
import fr.ses10doigts.tradeIO5.service.tree.scenario.DefaultMarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.MarketScenario;
import fr.ses10doigts.tradeIO5.service.tree.scenario.ScenarioEngine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reconstruction de l'état {@code ScenarioEngine}/{@code DecisionEngine} depuis photo + rejeu delta
 * (Palier 3, étape 4, docs/etudes/etude-branchement-persistance-decision-engine.md §C/§E pt3).
 * <p>
 * Extrait de {@link DecisionScenarioRestoreRunner} à l'étape 6 (archivage sur inactivité) pour être
 * réutilisable en dehors du seul démarrage de l'application : {@link #restoreAll()} couvre le cas
 * historique (tout restaurer, utilisé par {@code DecisionScenarioRestoreRunner}), {@link
 * #restoreOwner(ScenarioOwner)} restaure un seul owner à la demande (hook de reconnexion après
 * archivage, cf. {@code AuthController#authenticateUserForm}). Toute la logique de départage
 * (créations concurrentes via {@code ScenarioStatus.ordinal()}, UUID déterministe pour les {@code
 * Decision} jamais snapshottées, fallback {@code DEFAULT_SCOPE_FOR_LEGACY_EVENTS}) est réutilisée
 * telle quelle, pas réécrite.
 */
@Service
@RequiredArgsConstructor
public class DecisionScenarioRestoreService {

    private static final Logger log = LoggerFactory.getLogger(DecisionScenarioRestoreService.class);

    private final ScenarioSnapshotRepository scenarioSnapshotRepository;
    private final DecisionSnapshotRepository decisionSnapshotRepository;
    private final EventRepository eventRepository;
    private final ScenarioEngine scenarioEngine;
    private final DecisionEngine decisionEngine;
    private final ObjectMapper objectMapper;
    private final EventBus eventBus;

    public RestoreSummary restoreAll() {
        return restore(Optional.empty());
    }

    /**
     * Restauration owner-scopée (Palier 3, étape 6) : réinjecte uniquement les scénarios/décisions
     * de {@code owner} dans le moteur partagé. Utilisée à la reconnexion d'un utilisateur archivé.
     */
    public RestoreSummary restoreOwner(ScenarioOwner owner) {
        return restore(Optional.of(owner));
    }

    private RestoreSummary restore(Optional<ScenarioOwner> ownerFilter) {
        List<MarketScenario> scenarios = restoreScenarios(ownerFilter);
        List<Decision> decisions = restoreDecisions(ownerFilter);
        scenarioEngine.restoreScenarios(scenarios);
        decisionEngine.restoreDecisions(decisions);
        return new RestoreSummary(scenarios.size(), decisions.size());
    }

    // ---------------------------------------------------------------- scénarios ----

    private List<MarketScenario> restoreScenarios(Optional<ScenarioOwner> ownerFilter) {
        List<ScenarioSnapshotEntity> allSnapshotEntities = scenarioSnapshotRepository.findAll();
        List<ScenarioSnapshotEntity> snapshotEntities = ownerFilter.map(scenarioOwner -> allSnapshotEntities.stream()
                .filter(e -> scenarioOwner.equals(ScenarioOwner.fromString(e.getOwner())))
                .toList()).orElse(allSnapshotEntities);
        Map<String, ScenarioSnapshotEntity> snapshotsById = snapshotEntities.stream()
                .collect(Collectors.toMap(ScenarioSnapshotEntity::getScenarioId, e -> e));

        // Référence pour la recherche des créations nouvelles (jamais snapshottées) : la PLUS
        // ANCIENNE snapshotAt parmi toutes les photos existantes (pas la plus récente), pour ne
        // jamais manquer une création survenue entre deux dates de photo différentes selon les
        // scénarios (chaque ligne peut avoir sa propre snapshotAt, cf. "upsert par entité active" —
        // toutes les photos ne sont pas forcément prises exactement au même instant). Choix
        // délibérément conservateur ("complétude d'abord"), documenté ici plutôt que tranché en
        // silence (cf. consigne explicite du prompt d'implémentation de cette étape). Aucune photo du
        // tout => Instant.EPOCH (scanne tout l'historique d'événements disponible). Quand un filtre
        // owner est actif, recalculée sur l'ensemble DÉJÀ FILTRÉ (Palier 3, étape 6) — sinon une
        // restauration owner-scopée scannerait inutilement tout l'historique d'événements de tous
        // les owners.
        Instant referenceInstant = snapshotEntities.stream()
                .map(ScenarioSnapshotEntity::getSnapshotAt)
                .min(Instant::compareTo)
                .orElse(Instant.EPOCH);

        Map<String, List<ScenarioEvent>> eventsByScenarioId = eventRepository.findByTimestampAfter(referenceInstant).stream()
                .filter(e -> e.getType() == EventType.SCENARIO)
                .map(this::deserializeScenarioEvent)
                .filter(Objects::nonNull)
                // Palier 3, étape 6 : filtre owner appliqué ICI, avant le regroupement par id — sans
                // lui, une restauration owner-scopée récupérerait par erreur les créations nouvelles
                // d'autres owners.
                .filter(e -> ownerFilter.isEmpty() || ownerFilter.get().equals(e.getOwner()))
                .collect(Collectors.groupingBy(ScenarioEvent::getScenarioId));

        List<MarketScenario> restored = new ArrayList<>();
        Set<ScenarioKey> claimedKeys = new HashSet<>();

        // 1 & 2. Scénarios déjà photographiés : état de la photo, sauf si un événement postérieur à
        // SA propre snapshotAt existe (raccourci "dernier état complet" via ScenarioEvent.after, pas
        // de rejeu séquentiel nécessaire pour un scénario, cf. étude §C). Priorité absolue sur toute
        // création concurrente partageant la même ScenarioKey (cf. point ci-dessous).
        for (ScenarioSnapshotEntity entity : snapshotEntities) {
            ScenarioEvent latestEvent = latestEvent(eventsByScenarioId.get(entity.getScenarioId()));
            MarketScenario scenario = (latestEvent != null && latestEvent.getTimestamp().isAfter(entity.getSnapshotAt()))
                    ? scenarioFromEvent(entity.getScenarioId(), latestEvent)
                    : scenarioFromSnapshot(entity);
            if (scenario == null) {
                continue; // désérialisation échouée (déjà journalisée), scénario ignoré plutôt que NPE
            }
            restored.add(scenario);
            claimedKeys.add(keyOf(scenario));
        }

        // 3. Créations nouvelles : scenarioId présent parmi les events postérieurs mais absent des
        // photos.
        // ⚠️ Découverte en écrivant ce lot (bugs préexistants, hors périmètre de ce prompt, signalés
        // en détail dans le rapport final) :
        // (a) DefaultScenarioEngine.onMarketOpinion crée, via ScenarioFactory.create(...), un NOUVEL
        //     objet à chaque appel même quand une ScenarioKey identique existe déjà dans scenarios —
        //     scenarios.merge(...) garde alors l'objet existant (enrichFrom(...) est un no-op pour un
        //     scénario non-global, cf. DefaultMarketScenario), donc ce nouvel objet est aussitôt jeté.
        //     Mais AVANT d'être jeté, ScenarioFactory.create(...) a déjà appelé observe(...) dessus
        //     (ligne 41), ce qui publie un ScenarioEvent SCENARIO_CREATED bien réel et persisté.
        //     Plusieurs scenarioId distincts pour la MÊME ScenarioKey peuvent donc légitimement
        //     apparaître ici.
        // (b) ScenarioEvent.id (donc la clé primaire EventEntity.id, cf. constructeur métier de
        //     ScenarioEvent) est une fonction du seul scenarioId — TOUS les événements successifs
        //     d'un même scénario écrasent donc la MÊME ligne en base (upsert, pas append-only comme
        //     le nom "event store" le suggère), y compris pour le scénario réellement conservé.
        //     Conséquence directe pour ce départage : le timestamp du DERNIER événement connu pour le
        //     scénario réel ne reflète PAS sa date de création (souvent postérieure à celle d'un
        //     objet jeté créé plus tard) — un départage par timestamp le plus ancien serait donc
        //     erroné (vérifié empiriquement en écrivant DecisionScenarioRestoreIntegrationTest).
        // Départage retenu, robuste face à (b) : le scénario réellement conservé est le seul à avoir
        // pu recevoir plusieurs observe() successifs (voir onMarketOpinion, étape "observer les
        // scénarios existants") et donc à avoir progressé dans la machine à états
        // (ScenarioStatus) au-delà d'un simple appel isolé — on garde, par ScenarioKey, le candidat
        // dont le DERNIER statut connu est le plus avancé (ScenarioStatus.ordinal() le plus élevé),
        // et parmi des statuts égaux, l'événement le plus récent.
        Map<ScenarioKey, String> bestScenarioIdByKey = new HashMap<>();
        Map<ScenarioKey, ScenarioEvent> bestEventByKey = new HashMap<>();
        for (Map.Entry<String, List<ScenarioEvent>> entry : eventsByScenarioId.entrySet()) {
            String scenarioId = entry.getKey();
            if (snapshotsById.containsKey(scenarioId)) {
                continue;
            }
            ScenarioEvent latestEvent = latestEvent(entry.getValue());

            ScenarioKey key = new ScenarioKey(
                    latestEvent.getOwner(), latestEvent.getScenarioType(), latestEvent.getSymbol(),
                    latestEvent.getScope() != null ? latestEvent.getScope() : DEFAULT_SCOPE_FOR_LEGACY_EVENTS
            );
            if (claimedKeys.contains(key)) {
                continue; // une photo existe déjà pour cette clé : priorité absolue, cf. ci-dessus.
            }

            ScenarioEvent currentBest = bestEventByKey.get(key);
            boolean better = currentBest == null
                    || latestEvent.getAfter().getStatus().ordinal() > currentBest.getAfter().getStatus().ordinal()
                    || (latestEvent.getAfter().getStatus().ordinal() == currentBest.getAfter().getStatus().ordinal()
                        && latestEvent.getTimestamp().isAfter(currentBest.getTimestamp()));
            if (better) {
                bestEventByKey.put(key, latestEvent);
                bestScenarioIdByKey.put(key, scenarioId);
            }
        }
        bestScenarioIdByKey.forEach((key, scenarioId) ->
                restored.add(scenarioFromEvent(scenarioId, bestEventByKey.get(key))));

        log.info("DecisionScenarioRestoreService: {} scénario(s) restauré(s) ({} depuis photo, {} création(s) nouvelle(s)){}.",
                restored.size(), snapshotEntities.size(), restored.size() - snapshotEntities.size(),
                ownerFilter.map(o -> " pour owner " + o.getId()).orElse(""));

        return restored;
    }

    private ScenarioEvent latestEvent(List<ScenarioEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        return events.stream().max(Comparator.comparing(ScenarioEvent::getTimestamp)).orElseThrow();
    }

    private ScenarioKey keyOf(MarketScenario scenario) {
        return new ScenarioKey(scenario.getOwner(), scenario.getType(), scenario.getSymbol(), scenario.getScope());
    }

    private MarketScenario scenarioFromSnapshot(ScenarioSnapshotEntity entity) {
        ScenarioState state = deserialize(entity.getStateJson(), ScenarioState.class);
        if (state == null) {
            log.warn("DecisionScenarioRestoreService: échec de désérialisation de l'état pour le scénario {}, ignoré.",
                    entity.getScenarioId());
            return null;
        }
        ScenarioDefinition definition = new ScenarioDefinition(
                ScenarioType.valueOf(entity.getScenarioType()),
                ScenarioOwner.fromString(entity.getOwner()),
                Optional.ofNullable(entity.getSymbol()),
                resolveScope(entity.getScope()),
                state.getCreatedAt()
        );
        return new DefaultMarketScenario(entity.getScenarioId(), state, definition, eventBus);
    }

    private MarketScenario scenarioFromEvent(String scenarioId, ScenarioEvent event) {
        ScenarioDefinition definition = new ScenarioDefinition(
                event.getScenarioType(),
                event.getOwner(),
                event.getSymbol(),
                event.getScope() != null ? event.getScope() : DEFAULT_SCOPE_FOR_LEGACY_EVENTS,
                event.getAfter().getCreatedAt()
        );
        return new DefaultMarketScenario(scenarioId, event.getAfter(), definition, eventBus);
    }

    /**
     * {@code ScenarioSnapshotEntity.scope} est toujours renseigné par {@link
     * DecisionScenarioSnapshotService} (jamais null pour les photos de ce lot).
     */
    private OpinionScope resolveScope(String scope) {
        return scope == null ? DEFAULT_SCOPE_FOR_LEGACY_EVENTS : OpinionScope.valueOf(scope);
    }

    /**
     * {@code ScenarioEvent.scope} est nullable pour les lignes persistées avant l'introduction du
     * champ (Palier 3, étape 3) : défaut LOCAL, seul scope qui existait implicitement avant cette
     * extension (cf. étude "extension-risk-macro-external"). Choix documenté, à signaler dans le
     * rapport final plutôt que laissé implicite.
     */
    private static final OpinionScope DEFAULT_SCOPE_FOR_LEGACY_EVENTS = OpinionScope.LOCAL;

    // ---------------------------------------------------------------- décisions ----

    private List<Decision> restoreDecisions(Optional<ScenarioOwner> ownerFilter) {
        List<DecisionSnapshotEntity> allSnapshotEntities = decisionSnapshotRepository.findAll();
        List<DecisionSnapshotEntity> snapshotEntities = ownerFilter.map(scenarioOwner -> allSnapshotEntities.stream()
                .filter(e -> scenarioOwner.equals(ScenarioOwner.fromString(e.getOwner())))
                .toList()).orElse(allSnapshotEntities);
        Map<String, DecisionSnapshotEntity> snapshotsById = snapshotEntities.stream()
                .collect(Collectors.toMap(DecisionSnapshotEntity::getDecisionId, e -> e));

        // Même choix "plus ancienne snapshotAt, sur l'ensemble déjà filtré" que pour les scénarios
        // ci-dessus, même justification (Palier 3, étape 6).
        Instant referenceInstant = snapshotEntities.stream()
                .map(DecisionSnapshotEntity::getSnapshotAt)
                .min(Instant::compareTo)
                .orElse(Instant.EPOCH);

        Map<String, List<DecisionEvent>> eventsByDecisionId = eventRepository.findByTimestampAfter(referenceInstant).stream()
                .filter(e -> e.getType() == EventType.DECISION)
                .map(this::deserializeDecisionEvent)
                .filter(Objects::nonNull)
                // Palier 3, étape 6 : même filtre owner que pour les scénarios, appliqué avant le
                // regroupement par id.
                .filter(e -> ownerFilter.isEmpty() || ownerFilter.get().equals(e.getOwner()))
                .sorted(Comparator.comparing(DecisionEvent::getTimestamp))
                .collect(Collectors.groupingBy(DecisionEvent::getDecisionId));

        List<Decision> restored = new ArrayList<>();

        // 1 & 2. Décisions déjà photographiées : reconstruction depuis la photo (constructeur de
        // reconstruction, étape 4), puis rejeu SÉQUENTIEL des DecisionEvent postérieurs à SA propre
        // snapshotAt via Decision.apply(...) — DecisionEvent ne porte pas d'état before/after,
        // contrairement à ScenarioEvent, pas de raccourci possible ici (cf. lecture préalable du
        // prompt d'implémentation, point 8).
        for (DecisionSnapshotEntity entity : snapshotEntities) {
            Decision decision = decisionFromSnapshot(entity);
            if (decision == null) {
                continue; // désérialisation échouée (déjà journalisée), décision ignorée plutôt que NPE
            }
            eventsByDecisionId.getOrDefault(entity.getDecisionId(), List.of()).stream()
                    .filter(e -> e.getTimestamp().isAfter(entity.getSnapshotAt()))
                    .forEach(decision::apply);
            restored.add(decision);
        }

        // 3. Créations nouvelles : decisionId présent parmi les events postérieurs mais absent des
        // photos — reconstruction depuis leur DECISION_CREATED (DecisionCreatedCause.actionSteps()),
        // puis rejeu séquentiel du reste.
        eventsByDecisionId.forEach((decisionId, events) -> {
            if (snapshotsById.containsKey(decisionId)) {
                return;
            }
            Decision decision = decisionFromEvents(decisionId, events);
            if (decision != null) {
                restored.add(decision);
            }
        });

        log.info("DecisionScenarioRestoreService: {} décision(s) restaurée(s) ({} depuis photo, {} création(s) nouvelle(s)){}.",
                restored.size(), snapshotEntities.size(), restored.size() - snapshotEntities.size(),
                ownerFilter.map(o -> " pour owner " + o.getId()).orElse(""));

        return restored;
    }

    private Decision decisionFromSnapshot(DecisionSnapshotEntity entity) {
        DecisionSnapshotPayload payload = deserialize(entity.getSnapshotJson(), DecisionSnapshotPayload.class);
        if (payload == null) {
            log.warn("DecisionScenarioRestoreService: échec de désérialisation du snapshot pour la décision {}, ignorée.",
                    entity.getDecisionId());
            return null;
        }
        return new Decision(
                entity.getDecisionId(),
                payload.snapshot(),
                payload.steps(),
                DecisionStatus.valueOf(entity.getStatus()),
                payload.executedStepIds(),
                payload.lastUpdatedAt()
        );
    }

    /**
     * Reconstruction d'une Decision jamais snapshottée, depuis son seul historique d'événements.
     * <p>
     * ⚠️ Point non tranché seul à l'origine, discuté avec Clem le 2026-08-13 (cf. rapport final de
     * l'étape 4) : {@code DecisionEvent} ne porte JAMAIS {@code DecisionSnapshot.decisionId()}
     * (seulement {@code Decision.getId()}, cf. {@code DecisionEvent.decisionId}) — cette valeur,
     * utilisée comme clé de {@code DecisionEngine.activeDecisions} (voir {@code onScenarioEvent}/
     * {@code restoreDecisions}), est donc bien **irrécupérable** depuis les seuls événements
     * persistés pour une décision jamais snapshottée : aucune régénération, déterministe ou non, ne
     * peut reconstituer la valeur d'origine puisqu'elle n'a jamais été écrite nulle part ailleurs
     * que dans une photo qui, par définition ici, n'existe pas.
     * <p>
     * Confirmé sans risque aujourd'hui : {@code decision.getSnapshot().decisionId()} n'est lu nulle
     * part ailleurs que comme clé de {@code activeDecisions} au moment de l'insertion — {@code
     * getActiveDecision(String)} (le seul point qui la consulterait par la suite) n'a aucun appelant
     * actuel (confirmé par recherche dans le code).
     * <p>
     * Un aléa restait toutefois inutile : avec {@code UUID.randomUUID()}, deux redémarrages
     * successifs sans photo intermédiaire attribuaient une clé interne DIFFÉRENTE à la même décision
     * logique (même {@code Decision.id}, pourtant stable). Remplacé par un UUID déterministe dérivé
     * de {@code decisionId} ({@code Decision.id}, lui bien porté par chaque {@code DecisionEvent}) :
     * la clé interne régénérée est désormais stable d'un redémarrage à l'autre pour une même
     * décision, tant qu'aucune photo ne vient la remplacer par sa vraie valeur d'origine.
     */
    private Decision decisionFromEvents(String decisionId, List<DecisionEvent> events) {
        DecisionEvent createdEvent = events.stream()
                .filter(e -> e.getDecisionEventType() == DecisionEventType.DECISION_CREATED)
                .findFirst()
                .orElse(null);

        if (createdEvent == null) {
            log.warn("DecisionScenarioRestoreService: événements pour decisionId={} sans DECISION_CREATED dans la fenêtre restaurée, décision ignorée.", decisionId);
            return null;
        }

        DecisionCreatedCause cause = (DecisionCreatedCause) createdEvent.getCause();
        DecisionSnapshot snapshot = new DecisionSnapshot(
                // cf. javadoc ci-dessus : irrécupérable, régénéré de façon déterministe (stable
                // entre redémarrages) plutôt qu'aléatoire.
                UUID.nameUUIDFromBytes(("DecisionSnapshot.decisionId:" + decisionId).getBytes(StandardCharsets.UTF_8)).toString(),
                createdEvent.getSymbol(),
                createdEvent.getOwner(),
                createdEvent.getDecisionType(),
                createdEvent.getTimestamp()
        );

        Decision decision = new Decision(
                decisionId,
                snapshot,
                cause.actionSteps(),
                DecisionStatus.CREATED,
                Set.of(),
                createdEvent.getTimestamp()
        );

        events.stream()
                .filter(e -> e != createdEvent)
                .forEach(decision::apply);

        return decision;
    }

    // ---------------------------------------------------------------- helpers ----

    private ScenarioEvent deserializeScenarioEvent(EventEntity entity) {
        return deserialize(entity.getPayload(), ScenarioEvent.class);
    }

    private DecisionEvent deserializeDecisionEvent(EventEntity entity) {
        return deserialize(entity.getPayload(), DecisionEvent.class);
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.error("DecisionScenarioRestoreService: échec de désérialisation ({}): {}", type.getSimpleName(), e.getMessage(), e);
            return null;
        }
    }
}
