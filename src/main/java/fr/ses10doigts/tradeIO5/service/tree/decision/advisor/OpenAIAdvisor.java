package fr.ses10doigts.tradeIO5.service.tree.decision.advisor;

import fr.ses10doigts.tradeIO5.model.dto.decision.DecisionContext;
import fr.ses10doigts.tradeIO5.model.dto.decision.LlmAdvice;
import fr.ses10doigts.tradeIO5.model.enumerate.decision.AdvisorType;
import fr.ses10doigts.tradeIO5.service.connector.OpenAIService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OpenAIAdvisor extends AbstractAdvisor{

    private final OpenAIService service;

    @Override
    public AdvisorType getType() {
        return AdvisorType.LLM_OPENAI;
    }

    @Override
    protected LlmAdvice callModel(DecisionContext ctx) {
        return service.ask(buildPrompt(ctx));
    }
}
