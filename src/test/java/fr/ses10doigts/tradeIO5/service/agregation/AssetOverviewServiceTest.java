package fr.ses10doigts.tradeIO5.service.agregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.ses10doigts.tradeIO5.model.dto.AssetOverview;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.service.TransactionService;
import fr.ses10doigts.tradeIO5.service.WalletService;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;

@DisplayName("AssetOverviewService : la Home agrège USDC + USDT sous un seul actif « USD »")
class AssetOverviewServiceTest {

    private final ProviderApiService api = mock(ProviderApiService.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final WalletService wallets = mock(WalletService.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final Wallet wallet = Wallet.builder().id(1L).build();
    private AssetOverviewService service;

    @BeforeEach
    void setUp() {
        service = new AssetOverviewService(api, transactions, wallets, groups);
        when(wallets.getWalletsForCurrentUser()).thenReturn(List.of(wallet));
        when(groups.groupBySymbol()).thenReturn(Map.of("USDC", "USD", "USDT", "USD"));
        when(transactions.getWeightedAverageBuyPrice(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.getWeightedAverageSellPrice(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.getTotalBuyValue(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(transactions.getTotalSellValue(anyString(), any())).thenReturn(BigDecimal.ZERO);
        when(api.getMarketPrice(any(), eq("BTC"), anyString())).thenReturn(new BigDecimal("60000"));
    }

    private AssetOverview row(List<AssetOverview> rows, String asset) {
        return rows.stream().filter(o -> o.getAsset().equals(asset)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("USDC + USDT + LDUSDC => une ligne USD (quantité sommée, valeur nominale), BTC inchangé")
    void aggregatesUsdGroup() {
        when(api.getAllBalances(wallet)).thenReturn(Map.of("USDC", new BigDecimal("100"), "USDT", new BigDecimal("50"),
                "LDUSDC", new BigDecimal("10"), "BTC", BigDecimal.ONE));

        List<AssetOverview> rows = service.getUserHoldings("USDC", Optional.empty());

        AssetOverview usd = row(rows, "USD");
        assertEquals(0, new BigDecimal("160").compareTo(usd.getQuantity()));
        assertEquals(0, new BigDecimal("160").compareTo(usd.getValue()));
        assertEquals(0, BigDecimal.ONE.compareTo(usd.getMarketPrice()));
        assertEquals(0, new BigDecimal("60000").compareTo(row(rows, "BTC").getValue()));
        assertEquals(0, new BigDecimal("60160").compareTo(row(rows, "Total").getValue()));
        assertEquals(3, rows.size());
        verify(api, never()).getMarketPrice(any(), eq("USDT"), anyString());
        verify(api, never()).getMarketPrice(any(), eq("USDC"), anyString());
    }

    @Test
    @DisplayName("Fiat « USD » (ZUSD Kraken) ignoré : il ne fusionne pas avec le groupe")
    void fiatUsdIsIgnored() {
        when(api.getAllBalances(wallet)).thenReturn(Map.of("USD", new BigDecimal("999"), "USDT", new BigDecimal("50")));

        List<AssetOverview> rows = service.getUserHoldings("USDC", Optional.empty());

        assertEquals(0, new BigDecimal("50").compareTo(row(rows, "USD").getQuantity()));
        assertTrue(rows.stream().noneMatch(o -> o.getQuantity().compareTo(new BigDecimal("999")) == 0));
    }
}
