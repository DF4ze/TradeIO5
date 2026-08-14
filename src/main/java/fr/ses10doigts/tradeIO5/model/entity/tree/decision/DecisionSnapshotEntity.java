package fr.ses10doigts.tradeIO5.model.entity.tree.decision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Photo quotidienne de l'état courant d'une {@code Decision} (Palier 3, étape 4,
 * docs/etudes/etude-branchement-persistance-decision-engine.md §C/§E pt3). Une ligne par décision
 * ({@code decisionId} = clé primaire = {@code Decision.getId()}, PAS {@code
 * DecisionSnapshot.decisionId()} — les deux sont des identifiants distincts, cf. javadoc du
 * constructeur de reconstruction de {@code Decision}) : {@code save(...)} sert déjà d'upsert, pas
 * d'historique de photos accumulées (déjà couvert par le log d'événements existant).
 */
@Data
@Entity
@Table(name = "decision_snapshots")
public class DecisionSnapshotEntity {

    @Id
    private String decisionId; // = Decision.getId()

    private String symbol;
    private String owner; // ScenarioOwner.getId()
    private String type;
    private String status;

    @Column(columnDefinition = "TEXT")
    private String snapshotJson; // DecisionSnapshotPayload sérialisé (Jackson)

    private Instant snapshotAt;
}
