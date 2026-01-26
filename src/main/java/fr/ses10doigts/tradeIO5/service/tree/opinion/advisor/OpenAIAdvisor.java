package fr.ses10doigts.tradeIO5.service.tree.opinion.advisor;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.OpinionContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.LlmAdvice;
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
    protected LlmAdvice callModel(OpinionContext ctx) {
        return service.ask(buildPrompt(ctx));
    }
}
