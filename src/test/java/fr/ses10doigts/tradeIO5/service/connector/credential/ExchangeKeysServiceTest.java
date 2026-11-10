package fr.ses10doigts.tradeIO5.service.connector.credential;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.credential.ExchangeKeysService.ExchangeView;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Page Exchanges : état des clés, suppression")
class ExchangeKeysServiceTest {

    private final ApiCredentialRepository credentials = mock(ApiCredentialRepository.class);
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final WalletRepository wallets = mock(WalletRepository.class);
    private final RainbowLiveBindingService bindings = mock(RainbowLiveBindingService.class);
    private final User user = mock(User.class);
    private final WebProvider okx = mock(WebProvider.class);
    private ExchangeKeysService service;
    private Wallet wallet;
    private ApiCredential read;
    private RainbowLiveBinding binding;

    @BeforeEach
    void setUp() {
        service = new ExchangeKeysService(credentials, providers, wallets, bindings, org.mockito.Mockito.mock(fr.ses10doigts.tradeIO5.service.connector.keypair.ProviderKeyPairService.class), List.of());
        when(providers.findByCode(WebProviderCode.OKX)).thenReturn(Optional.of(okx));
        when(providers.findByCode(WebProviderCode.KRAKEN)).thenReturn(Optional.empty());
        read = ApiCredential.builder().user(user).webProvider(okx).scope(CredentialScope.READ).apiKey("abcdefgh1234").secretKey("s").enabled(true).build();
        read.setId(7L);
        wallet = Wallet.builder().name("OKX").enabled(true).credential(read).build();
        wallet.setId(3L);
        binding = mock(RainbowLiveBinding.class);
        when(binding.getId()).thenReturn(11L);
        when(binding.getWallet()).thenReturn(wallet);
        when(binding.getAssetSymbol()).thenReturn("BTC");
        when(binding.isArchived()).thenReturn(false);
        when(bindings.list(user)).thenReturn(List.of(binding));
        when(wallets.findByUserAndName(user, "OKX")).thenReturn(Optional.of(wallet));
        when(credentials.findReadByUserAndWebProvider(user, okx)).thenReturn(Optional.of(read));
        when(credentials.findByUserAndWebProviderAndScope(user, okx, CredentialScope.READ)).thenReturn(Optional.of(read));
    }

    @Test
    @DisplayName("list : seuls les exchanges avec une clé apparaissent ; indice = 4 derniers caractères, actifs liés listés")
    void listOnlyExchangesWithKeys() {
        when(credentials.findByUserAndWebProviderAndScope(user, okx, CredentialScope.TRADE)).thenReturn(Optional.empty());

        List<ExchangeView> list = service.list(user);

        assertEquals(1, list.size());
        ExchangeView view = list.getFirst();
        assertEquals(WebProviderCode.OKX, view.provider());
        assertTrue(view.needsPassphrase());
        assertTrue(view.read().configured());
        assertEquals("1234", view.read().hint());
        assertFalse(view.trade().configured());
        assertEquals(List.of("BTC"), view.linkedAssets());
    }

    @Test
    @DisplayName("hasTradeKey : vrai seulement avec une clé d'écriture active")
    void hasTradeKey() {
        assertFalse(service.hasTradeKey(user, WebProviderCode.OKX));
        ApiCredential trade = ApiCredential.builder().user(user).webProvider(okx).scope(CredentialScope.TRADE).apiKey("k").enabled(true).build();
        when(credentials.findByUserAndWebProviderAndScope(user, okx, CredentialScope.TRADE)).thenReturn(Optional.of(trade));
        assertTrue(service.hasTradeKey(user, WebProviderCode.OKX));
        trade.setEnabled(false);
        assertFalse(service.hasTradeKey(user, WebProviderCode.OKX));
    }

    @Test
    @DisplayName("supprimer la clé de lecture : liens archivés, wallet détaché et désactivé")
    void deleteReadKey() {
        service.deleteKey(user, WebProviderCode.OKX, CredentialScope.READ);

        verify(bindings).delete(user, 11L);
        assertNull(wallet.getCredential());
        assertFalse(wallet.isEnabled());
        verify(credentials).delete(read);
    }

    @Test
    @DisplayName("supprimer la clé d'écriture : exécution réelle coupée, liens conservés")
    void deleteTradeKey() {
        ApiCredential trade = ApiCredential.builder().user(user).webProvider(okx).scope(CredentialScope.TRADE).apiKey("k").enabled(true).build();
        when(credentials.findByUserAndWebProviderAndScope(user, okx, CredentialScope.TRADE)).thenReturn(Optional.of(trade));
        when(binding.isExecutionEnabled()).thenReturn(true);

        service.deleteKey(user, WebProviderCode.OKX, CredentialScope.TRADE);

        verify(bindings).setExecution(user, 11L, false);
        verify(bindings, never()).delete(any(), any());
        verify(credentials).delete(trade);
        assertTrue(wallet.isEnabled());
    }

    @Test
    @DisplayName("exchange non géré : refusé")
    void unsupportedProvider() {
        assertThrows(IllegalArgumentException.class, () -> service.deleteKey(user, WebProviderCode.BINANCE, CredentialScope.READ));
    }
}
