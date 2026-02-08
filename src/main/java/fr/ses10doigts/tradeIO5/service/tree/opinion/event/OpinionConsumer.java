package fr.ses10doigts.tradeIO5.service.tree.opinion.event;

import fr.ses10doigts.tradeIO5.model.dto.event.OpinionEvent;

public interface OpinionConsumer {
    void onOpinionEvent(OpinionEvent event);
}