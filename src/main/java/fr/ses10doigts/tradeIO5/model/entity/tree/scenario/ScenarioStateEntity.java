package fr.ses10doigts.tradeIO5.model.entity.tree.scenario;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioType;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.ScenarioStatus;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.SignalType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "scenario_state")
@Getter
@AllArgsConstructor
public class ScenarioStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Correspond exactement à ScenarioState
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioType scenario;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignalType signal;

    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false)
    private boolean stable;

    @Column(nullable = false)
    private Instant lastUpdated;

    @Column(nullable = false)
    private Instant createdAt;

/*    @Column(nullable = false)
    private String userId; // pour savoir à quel user appartient le scenario
*/
}
