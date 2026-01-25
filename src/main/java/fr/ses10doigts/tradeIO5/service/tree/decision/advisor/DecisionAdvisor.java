package fr.ses10doigts.tradeIO5.service.tree.decision.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.decision.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.AdvisorType;

public interface DecisionAdvisor {

    AdvisorType getType();

    LlmAdvice advise(DecisionContext context);
}
