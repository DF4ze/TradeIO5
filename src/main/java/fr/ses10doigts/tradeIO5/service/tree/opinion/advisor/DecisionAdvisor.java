package fr.ses10doigts.tradeIO5.service.tree.opinion.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.AdvisorType;

public interface DecisionAdvisor {

    AdvisorType getType();

    LlmAdvice advise(OpinionContext context);
}
