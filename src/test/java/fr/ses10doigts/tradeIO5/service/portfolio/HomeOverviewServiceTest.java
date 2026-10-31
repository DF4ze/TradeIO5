package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.HoldingsDto;
import fr.ses10doigts.tradeIO5.model.dto.portfolio.PortfolioHistoryDto;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.MarketDatasetEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("HomeOverviewService : soldes live, historique reconstruit, scope wallet")
class HomeOverviewServiceTest {

    private static final Instant AT = Instant.parse("2026-10-30T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final ReadOnlyBalanceReader kraken = mock(ReadOnlyBalanceReader.class);
    private final ReadOnlyBalanceReader okx = mock(ReadOnlyBalanceReader.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final MarketDatasetEngine engine = mock(MarketDatasetEngine.class);
    private final User user = new User();
    private Wallet w1;
    private Wallet w2;
    private HomeOverviewService service;

    @BeforeEach
    void setUp() {
        w1 = Wallet.builder().id(1L).name("Kraken").webProviderCode(WebProviderCode.KRAKEN).credential(new ApiCredential()).build();
        w2 = Wallet.builder().id(2L).name("OKX").webProviderCode(WebProviderCode.OKX).credential(new ApiCredential()).build();
        when(kraken.getProviderCode()).thenReturn(WebProviderCode.KRAKEN);
        when(okx.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(groups.members(any())).thenReturn(List.of("USDC"));
        when(walletRepository.findByUserAndEnabledTrue(user)).thenReturn(List.of(w1, w2));
        when(kraken.getAvailableBalances(any())).thenReturn(Map.of("BTC", new BigDecimal("1"), "USDC", new BigDecimal("1000")));
        when(okx.getAvailableBalances(any())).thenReturn(Map.of("BTC", new BigDecimal("0.5"), "USDT", new BigDecimal("500")));
        when(engine.getDailyDatasetForAsset(eq("BTC"), anyInt(), any(), any())).thenReturn(daily("BTC", 100.0));
        when(transactionRepository.findByWalletOrderByTimestampAsc(any())).thenReturn(List.of());
        service = new HomeOverviewService(walletRepository, transactionRepository, List.of(kraken, okx), groups, engine,
                new FixedDomainClock(AT));
    }

    private static DailyDataset daily(String symbol, double close) {
        List<MarketData> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            candles.add(MarketData.builder().pair(symbol).timestamp(TODAY.minusDays(i).atStartOfDay().toInstant(ZoneOffset.UTC))
                    .close(BigDecimal.valueOf(close)).build());
        }
        MarketDataset ds = MarketDataset.builder().pair(symbol).marketDatas(candles).size(candles.size()).build();
        return new DailyDataset(ds, null, symbol, false);
    }

    @Test
    @DisplayName("Tous les wallets : actifs regroupés (parts par wallet), stables à part, exposition")
    void holdingsAllWallets() {
        HoldingsDto h = service.holdings(user, null);
        assertEquals(1650.0, h.total(), 1e-9);
        assertEquals(1500.0, h.stable(), 1e-9);
        assertEquals(150.0, h.assetsValue(), 1e-9);
        assertEquals(150.0 / 1650.0 * 100.0, h.exposurePct(), 1e-9);
        HoldingsDto.Line btc = h.assets().stream().filter(l -> l.assetSymbol().equals("BTC")).findFirst().orElseThrow();
        assertEquals(1.5, btc.quantity(), 1e-9);
        assertEquals(2, btc.parts().size());
        assertTrue(h.assets().stream().filter(l -> l.stable()).map(HoldingsDto.Line::assetSymbol).toList().containsAll(List.of("USDC", "USDT")));
        assertTrue(h.unavailable().isEmpty());
    }

    @Test
    @DisplayName("Un seul wallet : seuls ses soldes ; wallet inconnu ⇒ 404")
    void holdingsSingleWalletAndUnknown() {
        HoldingsDto h = service.holdings(user, 1L);
        assertEquals(1100.0, h.total(), 1e-9);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.holdings(user, 99L));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    @DisplayName("Actif sans cours : exclu des totaux, partial=true, valeur nulle")
    void unpricedAssetIsPartial() {
        when(kraken.getAvailableBalances(any())).thenReturn(Map.of("XYZ", new BigDecimal("3"), "USDC", new BigDecimal("10")));
        when(engine.getDailyDatasetForAsset(eq("XYZ"), anyInt(), any(), any())).thenThrow(new IllegalStateException("aucun provider"));
        HoldingsDto h = service.holdings(user, 1L);
        assertTrue(h.partial());
        assertEquals(10.0, h.total(), 1e-9);
        assertNull(h.assets().stream().filter(l -> l.assetSymbol().equals("XYZ")).findFirst().orElseThrow().value());
    }

    @Test
    @DisplayName("Actif non configuré : valorisé chez l'exchange du wallet où il est détenu (OKB sur OKX)")
    void unconfiguredAssetPricedOnItsExchange() {
        when(okx.getAvailableBalances(any())).thenReturn(Map.of("OKB", new BigDecimal("2")));
        when(engine.getDailyDatasetForAsset(eq("OKB"), anyInt(), any(), any())).thenThrow(new IllegalStateException("aucun provider"));
        when(engine.getDailyDatasetOnSource(eq("OKB"), eq(MarketDataSource.OKX), anyInt(), any())).thenReturn(daily("OKB", 50.0));
        HoldingsDto h = service.holdings(user, 2L);
        assertTrue(!h.partial());
        assertEquals(100.0, h.total(), 1e-9);
    }

    @Test
    @DisplayName("Historique : un achat est annulé à rebours (qté et stable avant le trade)")
    void historyUndoesTrades() {
        when(transactionRepository.findByWalletOrderByTimestampAsc(w1)).thenReturn(List.of(Transaction.builder()
                .asset("BTC").side(TradeSide.BUY).quantity(new BigDecimal("0.5")).price(new BigDecimal("80"))
                .timestamp(LocalDateTime.of(TODAY.minusDays(5), java.time.LocalTime.NOON)).wallet(w1).build()));
        PortfolioHistoryDto h = service.history(user, 1L, PortfolioPeriod.D30);
        assertTrue(h.estimated());
        assertTrue(!h.flowsIncluded());
        var byDay = h.series().stream().collect(java.util.stream.Collectors.toMap(p -> p.day(), p -> p));
        assertEquals(1100.0, byDay.get(TODAY).total(), 1e-9);
        assertEquals(1100.0, byDay.get(TODAY.minusDays(5)).total(), 1e-9);
        assertEquals(1090.0, byDay.get(TODAY.minusDays(6)).total(), 1e-9);   // 0,5 BTC x 100 + 1000 + 40
        assertEquals(30, h.series().size());
    }

    @Test
    @DisplayName("Prix moyen : pondéré sur les achats enregistrés, comparé au cours live")
    void assetPerformanceUsesTrades() {
        when(transactionRepository.findByWalletOrderByTimestampAsc(w1)).thenReturn(List.of(
                Transaction.builder().asset("BTC").side(TradeSide.BUY).quantity(new BigDecimal("0.5")).price(new BigDecimal("80"))
                        .timestamp(LocalDateTime.of(TODAY.minusDays(5), java.time.LocalTime.NOON)).wallet(w1).build(),
                Transaction.builder().asset("BTC").side(TradeSide.BUY).quantity(new BigDecimal("0.5")).price(new BigDecimal("60"))
                        .timestamp(LocalDateTime.of(TODAY.minusDays(3), java.time.LocalTime.NOON)).wallet(w1).build()));
        var perf = service.assetPerformance(user, 1L);
        assertEquals(1, perf.size());                       // le stable n'y figure pas
        assertEquals(70.0, perf.getFirst().avgBuyPrice(), 1e-9);
        assertEquals(100.0, perf.getFirst().price(), 1e-9);
        assertEquals(100.0 - 70.0, perf.getFirst().pnl(), 1e-9);
    }

    @Test
    @DisplayName("Prix moyen d'un actif acheté en BTC : converti en dollars au cours du jour (PAXG/BTC)")
    void assetPerformanceConvertsCryptoQuote() {
        when(kraken.getAvailableBalances(any())).thenReturn(Map.of("PAXG", new BigDecimal("1")));
        when(engine.getDailyDatasetForAsset(eq("PAXG"), anyInt(), any(), any())).thenReturn(daily("PAXG", 8000.0));
        when(transactionRepository.findByWalletOrderByTimestampAsc(w1)).thenReturn(List.of(Transaction.builder()
                .asset("PAXG").quoteCurrency("BTC").side(TradeSide.BUY).quantity(BigDecimal.ONE).price(new BigDecimal("80"))
                .timestamp(LocalDateTime.of(TODAY.minusDays(2), java.time.LocalTime.NOON)).wallet(w1).build()));
        var perf = service.assetPerformance(user, 1L);
        assertEquals(8000.0, perf.getFirst().avgBuyPrice(), 1e-9);   // 80 BTC x 100 $ (cours BTC du jour de l'achat)
    }
}
