package fr.ses10doigts.tradeIO5.repository.decision;

import fr.ses10doigts.tradeIO5.model.entity.tree.EventEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Palier 3, étape 4 (étape 2 du prompt d'implémentation) : {@code findByTimestampAfter(...)},
 * requête dérivée Spring Data utilisée pour le rejeu delta (restauration au redémarrage). Vérifie
 * explicitement le comportement aux bornes : "After" => strictement postérieur ({@code isAfter}),
 * pas "supérieur ou égal".
 */
@DataJpaTest
@DisplayName("EventRepository")
class EventRepositoryTest {

    @Autowired
    private EventRepository eventRepository;

    private EventEntity newEvent(Instant timestamp) {
        EventEntity entity = new EventEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTargetId("target-1");
        entity.setType(EventType.DECISION);
        entity.setTimestamp(timestamp);
        entity.setPayload("{}");
        return entity;
    }

    @Test
    @DisplayName("findByTimestampAfter renvoie uniquement les entités strictement postérieures, ni l'égale ni les antérieures")
    void findByTimestampAfter_returnsOnlyStrictlyLaterEntities() {
        Instant reference = Instant.parse("2026-08-13T10:00:00Z");

        EventEntity before = eventRepository.save(newEvent(reference.minusSeconds(60)));
        EventEntity exactlyAt = eventRepository.save(newEvent(reference));
        EventEntity after = eventRepository.save(newEvent(reference.plusSeconds(60)));

        List<EventEntity> result = eventRepository.findByTimestampAfter(reference);

        assertEquals(1, result.size());
        assertEquals(after.getId(), result.getFirst().getId());

        List<String> resultIds = result.stream().map(EventEntity::getId).toList();
        assertFalse(resultIds.contains(before.getId()), "L'entité antérieure ne doit pas être renvoyée");
        assertFalse(resultIds.contains(exactlyAt.getId()), "L'entité exactement égale au timestamp ne doit pas être renvoyée (After = strictement postérieur)");
    }
}
