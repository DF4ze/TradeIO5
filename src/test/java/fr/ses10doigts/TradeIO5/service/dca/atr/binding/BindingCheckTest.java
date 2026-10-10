package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BindingCheck : le wallet permet-il de trader l'actif ?")
class BindingCheckTest {

    private final ReadOnlyBalanceReader reader = mock(ReadOnlyBalanceReader.class);
    private final InstrumentCatalog instruments = mock(InstrumentCatalog.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).name("OKX")
            .apiBaseUrl("https://www.okx.com").build();
    private BindingCheck check;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        when(reader.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(instruments.supports(WebProviderCode.OKX)).thenReturn(true);
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC", "USDT"));
        check = new BindingCheck(List.of(reader), instruments, groups);
        ApiCredential credential = ApiCredential.builder().id(1L).webProvider(okx).apiKey("k").secretKey("s")
                .passphrase("p").enabled(true).build();
        wallet = Wallet.builder().id(7L).name("OKX").enabled(true).webProviderCode(WebProviderCode.OKX)
                .credential(credential).build();
    }

    @Test
    @DisplayName("OK : soldes lisibles, paire existante")
    void ok() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of("BTC", BigDecimal.ONE));
        when(instruments.isTradable(any(), eq("BTC"), eq("USDC"))).thenReturn(true);
        BindingCheckResult result = check.check(wallet, "BTC");
        assertTrue(result.isOk());
        assertTrue(result.isExecutable());
        assertEquals(BindingCheckStatus.OK, result.status());
        assertEquals("USDC", result.quoteMember());
    }

    @Test
    @DisplayName("OK : aucun solde du tout (map vide) n'est pas une indisponibilité, USDC absent = solde 0")
    void emptyBalancesAreAccepted() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of());
        when(instruments.isTradable(any(), eq("ETH"), eq("USDC"))).thenReturn(true);
        assertTrue(check.check(wallet, "ETH").isOk());
    }

    @Test
    @DisplayName("KO : wallet désactivé, aucune lecture")
    void walletDisabled() {
        wallet.setEnabled(false);
        assertEquals(BindingCheckStatus.WALLET_DISABLED, check.check(wallet, "BTC").status());
        verify(reader, never()).getAvailableBalances(any());
    }

    @Test
    @DisplayName("KO : pas de credential / credential désactivée")
    void credentialMissing() {
        wallet.setCredential(null);
        assertEquals(BindingCheckStatus.CREDENTIAL_INVALID, check.check(wallet, "BTC").status());
        wallet.setCredential(ApiCredential.builder().id(2L).webProvider(okx).apiKey("k").enabled(false).build());
        assertEquals(BindingCheckStatus.CREDENTIAL_INVALID, check.check(wallet, "BTC").status());
    }

    @Test
    @DisplayName("KO : clé rejetée par l'exchange => CREDENTIAL_INVALID")
    void credentialRejected() {
        when(reader.getAvailableBalances(any())).thenThrow(new CredentialRejectedException("OKX : code=50113"));
        BindingCheckResult result = check.check(wallet, "BTC");
        assertEquals(BindingCheckStatus.CREDENTIAL_INVALID, result.status());
        verify(instruments, never()).isTradable(any(), any(), any());
    }

    @Test
    @DisplayName("KO : panne de lecture => BALANCE_UNAVAILABLE")
    void balanceUnavailable() {
        when(reader.getAvailableBalances(any())).thenThrow(new BalanceUnavailableException("réseau"));
        assertEquals(BindingCheckStatus.BALANCE_UNAVAILABLE, check.check(wallet, "BTC").status());
    }

    @Test
    @DisplayName("Kraken PAXG : ni PAXG/USDC ni PAXG/USDT => NOT_TRADABLE_WITHOUT_FIAT, lecture refusée, exécution bloquée")
    void notTradableWithoutFiat() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of());

        BindingCheckResult result = check.check(wallet, "PAXG");

        assertEquals(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, result.status());
        assertTrue(result.isBlocked());
        assertFalse(result.isOk());
        assertFalse(result.isExecutable());
    }

    @Test
    @DisplayName("OKX PAXG : PAXG/USDC absente, PAXG/USDT et USDC/USDT présentes => TRADABLE_VIA_BRIDGE")
    void viaBridge() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of());
        when(instruments.isTradable(any(), eq("PAXG"), eq("USDT"))).thenReturn(true);
        when(instruments.isTradable(any(), eq("USDC"), eq("USDT"))).thenReturn(true);

        BindingCheckResult result = check.check(wallet, "PAXG");

        assertEquals(BindingCheckStatus.TRADABLE_VIA_BRIDGE, result.status());
        assertEquals("USDT", result.quoteMember());
        assertEquals("USDC/USDT", result.bridgePair());
        assertTrue(result.isOk());
        assertFalse(result.isExecutable());
        assertFalse(result.isBlocked());
    }

    @Test
    @DisplayName("PAXG/USDT présente mais passerelle USDC/USDT absente => NOT_TRADABLE_WITHOUT_FIAT")
    void bridgeMissing() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of());
        when(instruments.isTradable(any(), eq("PAXG"), eq("USDT"))).thenReturn(true);

        assertEquals(BindingCheckStatus.NOT_TRADABLE_WITHOUT_FIAT, check.check(wallet, "PAXG").status());
    }

    @Test
    @DisplayName("KO : endpoint public injoignable => INSTRUMENT_UNAVAILABLE")
    void instrumentUnavailable() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of());
        when(instruments.isTradable(any(), any(), any())).thenThrow(new InstrumentLookupException("timeout"));
        assertEquals(BindingCheckStatus.INSTRUMENT_UNAVAILABLE, check.check(wallet, "BTC").status());
    }

    @Test
    @DisplayName("KO : exchange sans lecteur => PROVIDER_UNSUPPORTED")
    void providerUnsupported() {
        wallet.setWebProviderCode(WebProviderCode.LEDGER);
        assertEquals(BindingCheckStatus.PROVIDER_UNSUPPORTED, check.check(wallet, "BTC").status());
    }
}
