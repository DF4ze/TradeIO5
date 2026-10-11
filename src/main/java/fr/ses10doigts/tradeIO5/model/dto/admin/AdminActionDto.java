package fr.ses10doigts.tradeIO5.model.dto.admin;

import java.time.Instant;
import java.util.List;

/** Ligne du journal admin (lecture seule). {@code payload} = JSON texte sans secret, affiché tel quel. */
public record AdminActionDto(Long id, String actor, String action, String target, String payload, String outcome,
                             Instant createdAt) {

    public record Page(int page, int size, long totalElements, List<AdminActionDto> items) {
    }
}
