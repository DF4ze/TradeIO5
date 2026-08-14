package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.EventEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByType(EventType type);

    List<EventEntity> findByTargetId(String targetId);

    /**
     * Palier 3, étape 4 : requête delta globale par date (restauration au redémarrage,
     * cf. docs/etudes/etude-branchement-persistance-decision-engine.md §C/§E pt3). Strictement
     * postérieur (Spring Data "After" => {@code isAfter}) : un événement dont le timestamp est
     * exactement égal à {@code timestamp} n'est PAS renvoyé.
     */
    List<EventEntity> findByTimestampAfter(Instant timestamp);

}
