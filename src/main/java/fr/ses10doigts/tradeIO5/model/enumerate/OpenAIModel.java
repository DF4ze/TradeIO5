package fr.ses10doigts.tradeIO5.model.enumerate;

import com.openai.models.ChatModel;

public enum OpenAIModel {
    GPT_4_1_MINI("gpt-4.1-mini"),
    GPT_4_1("gpt-4.1");

    private final String apiName;

    OpenAIModel(String apiName) {
        this.apiName = apiName;
    }

    public ChatModel toChatModel() {
        return ChatModel.of(apiName);
    }
}