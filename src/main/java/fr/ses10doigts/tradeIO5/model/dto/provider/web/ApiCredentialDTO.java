package fr.ses10doigts.tradeIO5.model.dto.provider.web;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;

public record ApiCredentialDTO(
        WebProviderCode provider,
        String apiKey,
        String secretKey,
        String baseUrl
) {
    @Override
    public String toString() {
        return "ApiCredentialDTO[provider=" + provider + ", baseUrl=" + baseUrl + "]";
    }
}