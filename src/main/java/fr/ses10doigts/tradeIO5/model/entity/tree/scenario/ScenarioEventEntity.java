package fr.ses10doigts.tradeIO5.model.entity.tree.scenario;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "scenario_events")
public class ScenarioEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String scenarioId;
    private String scenarioType;
    private String owner;
    private String symbol;

    private String eventType;

    /** Stockage JSON pour les causes polymorphes */
    @Column(columnDefinition = "TEXT")
    private String causeJson;

    /** Stockage JSON pour les états avant/après */
    @Column(columnDefinition = "TEXT")
    private String beforeJson;

    @Column(columnDefinition = "TEXT")
    private String afterJson;

    private Instant occurredAt;
}
