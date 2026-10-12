package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("KrakenTradingFeeProvider : parsing de TradeVolume (frais déjà en %, jamais supposés)")
class KrakenTradingFeeProviderTest {

    @Test
    @DisplayName("fees = taker, fees_maker = maker, en % du montant")
    void parsesTakerAndMaker() {
        FeeRate rate = KrakenTradingFeeProvider.parse("""
                {"error":[],"result":{"currency":"ZUSD","volume":"1234.5",
                 "fees":{"PAXGXBT":{"fee":"0.2500","minfee":"0.1000","maxfee":"0.2600","nextfee":"0.2400"}},
                 "fees_maker":{"PAXGXBT":{"fee":"0.1500","minfee":"0.0000","maxfee":"0.1600"}}}}""");

        assertEquals(0, new BigDecimal("0.25").compareTo(rate.takerPct()));
        assertEquals(0, new BigDecimal("0.15").compareTo(rate.makerPct()));
    }

    @Test
    @DisplayName("Sans fees_maker : maker = taker")
    void makerFallsBackToTaker() {
        FeeRate rate = KrakenTradingFeeProvider.parse("{\"error\":[],\"result\":{\"fees\":{\"XBTUSDC\":{\"fee\":\"0.0800\"}}}}");

        assertEquals(0, new BigDecimal("0.08").compareTo(rate.takerPct()));
        assertEquals(0, rate.makerPct().compareTo(rate.takerPct()));
    }

    @Test
    @DisplayName("Clé rejetée => CredentialRejectedException ; autre erreur, champ absent ou illisible => BalanceUnavailableException")
    void errors() {
        assertThrows(CredentialRejectedException.class, () -> KrakenTradingFeeProvider.parse("{\"error\":[\"EAPI:Invalid key\"]}"));
        assertThrows(CredentialRejectedException.class, () -> KrakenTradingFeeProvider.parse("{\"error\":[\"EGeneral:Permission denied\"]}"));
        assertThrows(BalanceUnavailableException.class, () -> KrakenTradingFeeProvider.parse("{\"error\":[\"EService:Unavailable\"]}"));
        assertThrows(BalanceUnavailableException.class, () -> KrakenTradingFeeProvider.parse("{\"error\":[],\"result\":{}}"));
        assertThrows(BalanceUnavailableException.class,
                () -> KrakenTradingFeeProvider.parse("{\"error\":[],\"result\":{\"fees\":{\"X\":{\"fee\":\"abc\"}}}}"));
        assertThrows(BalanceUnavailableException.class, () -> KrakenTradingFeeProvider.parse("pas du json"));
    }
}
