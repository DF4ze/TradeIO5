package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.connector.balance.ReadOnlyBalanceReader;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BindingPathQuoteService : devis du chemin d'achat (lecture seule)")
class BindingPathQuoteServiceTest {

    private final RainbowLiveBindingService bindingService = mock(RainbowLiveBindingService.class);
    private final ReadOnlyBalanceReader reader = mock(ReadOnlyBalanceReader.class);
    private final TradingFeeProvider fees = mock(TradingFeeProvider.class);
    private final OrderBookClient books = mock(OrderBookClient.class);
    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("https://okx").build();
    private final User alice = User.builder().id(1L).username("alice").build();
    private BindingPathQuoteService service;
    private RainbowLiveBinding binding;
    private Wallet wallet;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static InstrumentInfo inst(String base, String quote, String min) {
        return new InstrumentInfo(base + "-" + quote, base, quote, d(min), d("0.0001"), d("0.1"));
    }

    private static BookResult book(String bid, String ask, String qty, PathEstimation estimation) {
        return new BookResult(new OrderBookSnapshot(List.of(new OrderBookLevel(d(bid), d(qty))),
                List.of(new OrderBookLevel(d(ask), d(qty)))), estimation);
    }

    @BeforeEach
    void setUp() {
        when(reader.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(fees.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(books.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(catalog.supports(WebProviderCode.OKX)).thenReturn(true);
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC"));
        service = new BindingPathQuoteService(bindingService, List.of(reader), List.of(fees), List.of(books), catalog,
                groups, new PathFinder(new FeeTest()), new FeeTest(), "");
        ApiCredential credential = ApiCredential.builder().id(1L).webProvider(okx).apiKey("k").secretKey("s")
                .passphrase("p").enabled(true).build();
        wallet = Wallet.builder().id(7L).name("OKX").enabled(true).webProviderCode(WebProviderCode.OKX)
                .credential(credential).build();
        binding = RainbowLiveBinding.builder().id(5L).user(alice).assetSymbol("PAXG").wallet(wallet).build();
        when(bindingService.get(alice, 5L)).thenReturn(binding);
        when(catalog.liveInstruments(okx)).thenReturn(List.of(inst("PAXG", "USDC", "0.0001")));
        when(reader.getAvailableBalances(any())).thenReturn(Map.of("USDC", d("1000")));
        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.05"), d("0.05")));
        when(books.fetchBook(any(), anyString(), anyInt())).thenReturn(book("99.9", "100.1", "1000", PathEstimation.BOOK));
    }

    @Test
    @DisplayName("PAXG/OKX avec USDC : 1 jambe, frais du compte + demi-spread = 0,15 % GREEN, aucun avertissement")
    void green() {
        BindingPathQuoteService.Result result = service.quote(alice, 5L, d("1000"));

        assertEquals(PathStatus.OK, result.quote().status());
        assertEquals("USDC", result.quote().source());
        assertEquals("PAXG-USDC", result.quote().legs().getFirst().instId());
        assertEquals(0, d("0.15").compareTo(result.quote().totalCostPct()));
        assertEquals(FeeTestLevel.GREEN, result.quote().feeTestLevel());
        assertNull(result.warning());
        verify(fees).getFeeRate(any(), org.mockito.ArgumentMatchers.eq("PAXG-USDC"));
    }

    @Test
    @DisplayName("Frais élevés : WARNING puis RED, avertissement en % (jamais en bps)")
    void warningAndRed() {
        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.1"), d("0.25")));
        BindingPathQuoteService.Result warn = service.quote(alice, 5L, d("1000"));
        assertEquals(FeeTestLevel.WARNING, warn.quote().feeTestLevel());
        assertTrue(warn.warning().contains("0.35 %") && warn.warning().contains("seuil d'alerte (0.2 %)"), warn.warning());

        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.5"), d("1")));
        BindingPathQuoteService.Result red = service.quote(alice, 5L, d("1000"));
        assertEquals(FeeTestLevel.RED, red.quote().feeTestLevel());
        assertTrue(red.warning().contains("seuil rouge (0.8 %)"), red.warning());
        assertFalse(red.warning().toLowerCase().contains("bps"));
    }

    @Test
    @DisplayName("PAXG/Kraken : route USDC>BTC>PAXG choisie (2 jambes), route ETH chiffrée mais non choisie, fiat ignoré")
    void krakenPaxgViaBtc() {
        WebProvider kraken = WebProvider.builder().code(WebProviderCode.KRAKEN).apiBaseUrl("https://api.kraken.com").build();
        wallet.setWebProviderCode(WebProviderCode.KRAKEN);
        wallet.getCredential().setWebProvider(kraken);
        ReadOnlyBalanceReader krakenReader = mock(ReadOnlyBalanceReader.class);
        when(krakenReader.getProviderCode()).thenReturn(WebProviderCode.KRAKEN);
        when(fees.getProviderCode()).thenReturn(WebProviderCode.KRAKEN);
        when(books.getProviderCode()).thenReturn(WebProviderCode.KRAKEN);
        when(krakenReader.getAvailableBalances(any())).thenReturn(Map.of("USDC", d("1000")));
        when(catalog.supports(WebProviderCode.KRAKEN)).thenReturn(true);
        when(catalog.liveInstruments(kraken)).thenReturn(List.of(inst("PAXG", "USD", "0.003"), inst("PAXG", "EUR", "0.003"),
                inst("PAXG", "BTC", "0.003"), inst("PAXG", "ETH", "0.003"), inst("BTC", "USDC", "0.0001"),
                inst("ETH", "USDC", "0.001")));
        when(books.fetchBook(any(), org.mockito.ArgumentMatchers.eq("BTC-USDC"), anyInt())).thenReturn(book("49990", "50010", "1000", PathEstimation.BOOK));
        when(books.fetchBook(any(), org.mockito.ArgumentMatchers.eq("PAXG-BTC"), anyInt())).thenReturn(book("0.07995", "0.08005", "1000", PathEstimation.BOOK));
        when(books.fetchBook(any(), org.mockito.ArgumentMatchers.eq("ETH-USDC"), anyInt())).thenReturn(book("2999.5", "3000.5", "1000", PathEstimation.BOOK));
        when(books.fetchBook(any(), org.mockito.ArgumentMatchers.eq("PAXG-ETH"), anyInt())).thenReturn(book("1.3300", "1.3340", "1000", PathEstimation.BOOK));
        service = new BindingPathQuoteService(bindingService, List.of(krakenReader), List.of(fees), List.of(books), catalog,
                groups, new PathFinder(new FeeTest()), new FeeTest(), "");

        BindingPathQuoteService.Result result = service.quote(alice, 5L, d("100"));

        assertEquals(PathStatus.OK, result.quote().status());
        assertEquals("USDC>BTC>PAXG", result.quote().routeKey());
        assertEquals(List.of("BTC-USDC", "PAXG-BTC"), result.quote().legs().stream().map(l -> l.instId()).toList());
        assertEquals(2, result.quote().routes().size());
        assertTrue(result.quote().routes().stream().anyMatch(r -> r.routeKey().equals("USDC>ETH>PAXG") && !r.chosen()));
        assertTrue(result.warning().contains("Intermédiaire exposé"), result.warning());
        assertTrue(result.warning().contains("USDC>ETH>PAXG non choisie"), result.warning());
    }

    @Test
    @DisplayName("Aucun solde USDC : NO_PATH avec message dédié")
    void noStableBalance() {
        when(reader.getAvailableBalances(any())).thenReturn(Map.of("BTC", d("1")));

        BindingPathQuoteService.Result result = service.quote(alice, 5L, d("1000"));

        assertEquals(PathStatus.NO_PATH, result.quote().status());
        assertTrue(result.warning().startsWith("Aucun solde disponible"), result.warning());
    }

    @Test
    @DisplayName("Carnet trop peu profond : arête écartée => NO_PATH ; ticker seul : avertissement d'estimation")
    void bookDepthAndTicker() {
        when(books.fetchBook(any(), anyString(), anyInt())).thenReturn(book("99.9", "100.1", "1", PathEstimation.BOOK));
        assertEquals(PathStatus.NO_PATH, service.quote(alice, 5L, d("1000")).quote().status());

        when(books.fetchBook(any(), anyString(), anyInt())).thenReturn(book("99.9", "100.1", "1", PathEstimation.TICKER));
        BindingPathQuoteService.Result ticker = service.quote(alice, 5L, d("1000"));
        assertEquals(PathStatus.OK, ticker.quote().status());
        assertEquals(PathEstimation.TICKER, ticker.quote().estimation());
        assertTrue(ticker.warning().contains("meilleur bid/ask"), ticker.warning());
    }

    @Test
    @DisplayName("Montant sous le minimum de la paire : BELOW_MIN avec le minimum dans l'avertissement")
    void belowMin() {
        when(catalog.liveInstruments(okx)).thenReturn(List.of(inst("PAXG", "USDC", "0.01")));

        BindingPathQuoteService.Result result = service.quote(alice, 5L, d("0.5"));

        assertEquals(PathStatus.BELOW_MIN, result.quote().status());
        assertTrue(result.warning().contains("PAXG-USDC") && result.warning().contains("0.01"), result.warning());
    }

    @Test
    @DisplayName("Solde insuffisant : devis marqué underfunded + avertissement")
    void underfunded() {
        BindingPathQuoteService.Result result = service.quote(alice, 5L, d("5000"));

        assertTrue(result.quote().underfunded());
        assertTrue(result.warning().contains("Solde insuffisant sur USDC"), result.warning());
    }

    @Test
    @DisplayName("Montant invalide => IllegalArgumentException, aucun appel exchange")
    void invalidAmount() {
        assertThrows(IllegalArgumentException.class, () -> service.quote(alice, 5L, null));
        assertThrows(IllegalArgumentException.class, () -> service.quote(alice, 5L, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> service.quote(alice, 5L, d("-1")));
        verify(reader, never()).getAvailableBalances(any());
        verify(fees, never()).getFeeRate(any(), anyString());
        verify(books, never()).fetchBook(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Binding d'un autre utilisateur ou inconnu : RainbowLiveBindingNotFoundException, aucun appel exchange")
    void notOwned() {
        User bob = User.builder().id(2L).username("bob").build();
        when(bindingService.get(bob, 5L)).thenThrow(RainbowLiveBindingNotFoundException.binding(5L));

        assertThrows(RainbowLiveBindingNotFoundException.class, () -> service.quote(bob, 5L, d("100")));
        verify(reader, never()).getAvailableBalances(any());
        verify(fees, never()).getFeeRate(any(), anyString());
        verify(books, never()).fetchBook(any(), anyString(), anyInt());
        verify(catalog, never()).liveInstruments(any());
    }

    @Test
    @DisplayName("Wallet désactivé / sans credential / exchange non pris en charge : PathQuoteUnavailableException")
    void unusableWallet() {
        wallet.setEnabled(false);
        assertThrows(PathQuoteUnavailableException.class, () -> service.quote(alice, 5L, d("100")));
        wallet.setEnabled(true);
        wallet.setCredential(null);
        assertThrows(PathQuoteUnavailableException.class, () -> service.quote(alice, 5L, d("100")));
        wallet.setCredential(ApiCredential.builder().webProvider(okx).apiKey("k").enabled(true).build());
        wallet.setWebProviderCode(WebProviderCode.LEDGER);
        assertThrows(PathQuoteUnavailableException.class, () -> service.quote(alice, 5L, d("100")));
    }

    @Test
    @DisplayName("Exchange sans frais/carnet mais avec un chemin : PathQuoteUnavailableException (pas de frais supposé)")
    void feesUnsupported() {
        service = new BindingPathQuoteService(bindingService, List.of(reader), List.of(), List.of(), catalog, groups,
                new PathFinder(new FeeTest()), new FeeTest(), "");
        assertThrows(PathQuoteUnavailableException.class, () -> service.quote(alice, 5L, d("100")));
    }

    @Test
    @DisplayName("Pannes propagées typées : clé rejetée, soldes/frais indisponibles, catalogue indisponible")
    void failuresPropagate() {
        when(reader.getAvailableBalances(any())).thenThrow(new CredentialRejectedException("OKX : code=50113"));
        assertThrows(CredentialRejectedException.class, () -> service.quote(alice, 5L, d("100")));

        org.mockito.Mockito.doReturn(Map.of("USDC", d("1000"))).when(reader).getAvailableBalances(any());
        when(fees.getFeeRate(any(), anyString())).thenThrow(new BalanceUnavailableException("OKX : frais illisibles"));
        assertThrows(BalanceUnavailableException.class, () -> service.quote(alice, 5L, d("100")));

        when(catalog.liveInstruments(okx)).thenThrow(new InstrumentLookupException("catalogue absent"));
        assertThrows(InstrumentLookupException.class, () -> service.quote(alice, 5L, d("100")));
    }

    @Test
    @DisplayName("Nœuds interdits configurables (propriété) : USDC interdit => plus de chemin via USDC")
    void forbiddenNodesConfigurable() {
        service = new BindingPathQuoteService(bindingService, List.of(reader), List.of(fees), List.of(books), catalog,
                groups, new PathFinder(new FeeTest()), new FeeTest(), "usd, eur ,USDC");

        assertEquals(PathStatus.NO_PATH, service.quote(alice, 5L, d("100")).quote().status());
    }
}
