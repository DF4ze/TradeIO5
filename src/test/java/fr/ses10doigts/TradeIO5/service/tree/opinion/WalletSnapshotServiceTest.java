package fr.ses10doigts.tradeIO5.service.tree.opinion;

import fr.ses10doigts.tradeIO5.model.dto.tree.opinion.WalletSnapshot;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.WalletService;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("WalletSnapshotService")
@ExtendWith(MockitoExtension.class)
class WalletSnapshotServiceTest {

    @Mock
    private WalletService walletService;

    @Mock
    private ProviderApiService providerApiService;

    private final User user = User.builder().id(1L).username("alice").build();

    @Test
    @DisplayName("Agrège les balances d'un même actif sur plusieurs wallets (somme, pas écrasement)")
    void buildSnapshot_aggregatesSameAssetAcrossWallets() {
        Wallet binanceWallet = Wallet.builder().id(1L).name("binance").webProviderCode(WebProviderCode.BINANCE).user(user).build();
        Wallet krakenWallet = Wallet.builder().id(2L).name("kraken").webProviderCode(WebProviderCode.KRAKEN).user(user).build();

        when(walletService.getWalletsByUser(user)).thenReturn(List.of(binanceWallet, krakenWallet));

        when(providerApiService.getAllBalances(binanceWallet))
                .thenReturn(Map.of("BTC", new BigDecimal("0.1")));
        when(providerApiService.getAllBalances(krakenWallet))
                .thenReturn(Map.of("BTC", new BigDecimal("0.05")));

        when(providerApiService.getMarketPrice(eq(binanceWallet), eq("BTC"), any()))
                .thenReturn(new BigDecimal("50000"));
        when(providerApiService.getMarketPrice(eq(krakenWallet), eq("BTC"), any()))
                .thenReturn(new BigDecimal("50000"));

        WalletSnapshotService service = new WalletSnapshotService(walletService, providerApiService);

        WalletSnapshot snapshot = service.buildSnapshot(user, "USDC");

        assertEquals(0.15, snapshot.getBalances().get("BTC"), 1e-9);
        assertEquals(0.15 * 50000, snapshot.getTotalValue(), 1e-6);
    }

    @Test
    @DisplayName("Utilisateur sans wallet : snapshot vide, pas d'exception")
    void buildSnapshot_noWallets_returnsEmptySnapshot() {
        when(walletService.getWalletsByUser(user)).thenReturn(List.of());

        WalletSnapshotService service = new WalletSnapshotService(walletService, providerApiService);

        WalletSnapshot snapshot = service.buildSnapshot(user, "USDC");

        assertTrue(snapshot.getBalances().isEmpty());
        assertEquals(0, snapshot.getTotalValue());
    }
}
