package fr.ses10doigts.tradeIO5.model.entity.tree;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    private String id;

    private String targetId;

    @Enumerated(EnumType.STRING)
    private EventType type;

    private Instant timestamp;

    @Lob
    private String payload; // JSON de l'event

}
