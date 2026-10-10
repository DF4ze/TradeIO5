package fr.ses10doigts.tradeIO5.model.entity.exchange;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Secrets exclus des toString")
class ApiCredentialToStringTest {

    private static final String KEY = "api-key-SECRET-1";
    private static final String SECRET = "secret-SECRET-2";
    private static final String PASSPHRASE = "pass-SECRET-3";

    private static ApiCredential credential() {
        return ApiCredential.builder().id(1L).apiKey(KEY).secretKey(SECRET).passphrase(PASSPHRASE).build();
    }

    private static void assertNoSecret(String text) {
        assertFalse(text.contains("SECRET"), text);
    }

    @Test
    @DisplayName("ApiCredential#toString n'imprime ni clé, ni secret, ni passphrase")
    void credentialToString() {
        assertNoSecret(credential().toString());
    }

    @Test
    @DisplayName("Wallet#toString n'imprime pas sa credential")
    void walletToString() {
        assertNoSecret(Wallet.builder().name("OKX").credential(credential()).build().toString());
    }

    @Test
    @DisplayName("ApiCredentialDTO#toString n'imprime pas clé ni secret")
    void dtoToString() {
        assertNoSecret(new ApiCredentialDTO(WebProviderCode.OKX, KEY, SECRET, "https://www.okx.com").toString());
    }
}
