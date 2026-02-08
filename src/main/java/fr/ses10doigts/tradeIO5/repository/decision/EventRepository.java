package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.EventEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, String> {

    List<EventEntity> findByType(EventType type);

    List<EventEntity> findByTargetId(String targetId);

}
