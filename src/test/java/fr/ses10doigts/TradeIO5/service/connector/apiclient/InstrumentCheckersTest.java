package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Vérificateurs d'instruments publics (OKX, Kraken) : parsing")
class InstrumentCheckersTest {

    @Test
    @DisplayName("OKX : live => négociable ; data vide ou 51001 => absent ; suspend => non négociable")
    void okx() {
        assertTrue(OkxInstrumentChecker.parse("{\"code\":\"0\",\"data\":[{\"instId\":\"BTC-USDC\",\"state\":\"live\"}]}"));
        assertFalse(OkxInstrumentChecker.parse("{\"code\":\"0\",\"data\":[]}"));
        assertFalse(OkxInstrumentChecker.parse("{\"code\":\"51001\",\"msg\":\"Instrument ID does not exist\",\"data\":[]}"));
        assertFalse(OkxInstrumentChecker.parse("{\"code\":\"0\",\"data\":[{\"state\":\"suspend\"}]}"));
    }

    @Test
    @DisplayName("OKX : autre erreur ou réponse illisible => InstrumentLookupException")
    void okxFailures() {
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentChecker.parse("{\"code\":\"50011\",\"msg\":\"rate\"}"));
        assertThrows(InstrumentLookupException.class, () -> OkxInstrumentChecker.parse("<html>"));
    }

    @Test
    @DisplayName("Kraken : online => négociable ; Unknown asset pair / result vide / cancel_only => non")
    void kraken() {
        assertTrue(KrakenInstrumentChecker.parse("{\"error\":[],\"result\":{\"PAXGUSDC\":{\"status\":\"online\"}}}"));
        assertFalse(KrakenInstrumentChecker.parse("{\"error\":[\"EQuery:Unknown asset pair\"],\"result\":{}}"));
        assertFalse(KrakenInstrumentChecker.parse("{\"error\":[],\"result\":{}}"));
        assertFalse(KrakenInstrumentChecker.parse("{\"error\":[],\"result\":{\"X\":{\"status\":\"cancel_only\"}}}"));
    }

    @Test
    @DisplayName("Kraken : autre erreur => InstrumentLookupException")
    void krakenFailures() {
        assertThrows(InstrumentLookupException.class,
                () -> KrakenInstrumentChecker.parse("{\"error\":[\"EGeneral:Temporary lockout\"]}"));
    }

    @Test
    @DisplayName("OKX balance : codes 50100-50114 => CredentialRejectedException")
    void okxCredentialRejected() {
        assertThrows(CredentialRejectedException.class,
                () -> OkxBalanceReader.parseBalances("{\"code\":\"50113\",\"msg\":\"Invalid Sign\",\"data\":[]}"));
        assertThrows(CredentialRejectedException.class,
                () -> OkxBalanceReader.parseBalances("{\"code\":\"50105\",\"msg\":\"Passphrase incorrect\",\"data\":[]}"));
        try {
            OkxBalanceReader.parseBalances("{\"code\":\"50011\",\"msg\":\"Too many\",\"data\":[]}");
        } catch (CredentialRejectedException e) {
            throw new AssertionError("50011 n'est pas un rejet de clé", e);
        } catch (RuntimeException expected) {
            // BalanceUnavailableException simple
        }
    }
}
