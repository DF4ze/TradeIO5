package fr.ses10doigts.tradeIO5.model.entity.tree.scenario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Photo quotidienne de l'état courant d'un {@code MarketScenario} (Palier 3, étape 4,
 * docs/etudes/etude-branchement-persistance-decision-engine.md §C/§E pt3). Une ligne par scénario
 * ({@code scenarioId} = clé primaire) : {@code save(...)} sert déjà d'upsert, la photo n'accumule
 * pas d'historique — celui-ci est déjà couvert par le log d'événements existant
 * ({@code scenario_events} / la table générique {@code events} via {@link
 * fr.ses10doigts.tradeIO5.service.tree.event.engine.JpaEventStore}). À ne pas confondre avec
 * {@link ScenarioEventEntity} : ceci est une photo de l'état courant, pas un log append-only.
 */
@Data
@Entity
@Table(name = "scenario_snapshots")
public class ScenarioSnapshotEntity {

    @Id
    private String scenarioId; // = MarketScenario.getId()

    private String scenarioType;
    private String owner; // ScenarioOwner.getId() — cf. ScenarioOwner.fromString(...) pour le sens inverse
    private String symbol; // nullable : scénario global
    private String scope;

    @Column(columnDefinition = "TEXT")
    private String stateJson; // ScenarioState sérialisé (Jackson), même convention que EventEntity.payload

    private Instant snapshotAt;
}
