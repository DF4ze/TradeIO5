package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot.OrderBookLevel;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetGroup;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.AssetPlanOutcome;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.service.connector.fee.TradingFeeProvider;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.currency.AssetGroupService;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderPlanner : plan d'ordres dry-run (achats, ponts, ventes, Fee Test)")
class OrderPlannerTest {

    private final TradingFeeProvider fees = mock(TradingFeeProvider.class);
    private final OrderBookClient books = mock(OrderBookClient.class);
    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final AssetGroupService groups = mock(AssetGroupService.class);
    private final WebProvider okx = WebProvider.builder().code(WebProviderCode.OKX).apiBaseUrl("https://okx").build();
    private final ApiCredential credential = ApiCredential.builder().id(1L).webProvider(okx).apiKey("k").secretKey("s")
            .passphrase("p").enabled(true).build();
    private OrderPlanner planner;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static InstrumentInfo inst(String base, String quote, String minSz, String lotSz, String tick) {
        return new InstrumentInfo(base + "-" + quote, base, quote, d(minSz), d(lotSz), d(tick));
    }

    private static BookResult book(String bid, String ask) {
        return new BookResult(new OrderBookSnapshot(List.of(new OrderBookLevel(d(bid), d("100000"))),
                List.of(new OrderBookLevel(d(ask), d("100000")))), PathEstimation.BOOK);
    }

    @BeforeEach
    void setUp() {
        when(fees.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(books.getProviderCode()).thenReturn(WebProviderCode.OKX);
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC"));
        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.05"), d("0.05")));
        when(books.fetchBook(any(), eq("PAXG-USDC"), anyInt())).thenReturn(book("99.9", "100.1"));
        when(books.fetchBook(any(), eq("BTC-USDC"), anyInt())).thenReturn(book("49990", "50010"));
        when(books.fetchBook(any(), eq("ETH-USDC"), anyInt())).thenReturn(book("2999.5", "3000.5"));
        when(books.fetchBook(any(), eq("PAXG-BTC"), anyInt())).thenReturn(book("0.07995", "0.08005"));
        when(books.fetchBook(any(), eq("PAXG-ETH"), anyInt())).thenReturn(book("1.3300", "1.3340"));
        when(catalog.liveInstruments(okx)).thenReturn(okxInstruments());
        planner = new OrderPlanner(catalog, List.of(fees), List.of(books), groups, new PathFinder(new FeeTest()), new FeeTest(),
                new ExecutionSettings(), "");
    }

    /** Exchange à paires USDC directes (type OKX une fois USDT exclu). */
    private static List<InstrumentInfo> okxInstruments() {
        return List.of(inst("PAXG", "USDC", "0.0001", "0.0001", "0.1"), inst("BTC", "USDC", "0.00001", "0.00001", "0.1"));
    }

    /** Exchange sans PAXG/USDC (type Kraken) : PAXG ne se cote qu'en fiat, BTC et ETH. */
    private static List<InstrumentInfo> krakenInstruments() {
        return List.of(inst("PAXG", "USD", "0.001", "0.00000001", "0.01"), inst("PAXG", "BTC", "0.001", "0.00001", "0.00001"),
                inst("PAXG", "ETH", "0.001", "0.00001", "0.0001"), inst("BTC", "USDC", "0.00005", "0.00000001", "0.1"),
                inst("ETH", "USDC", "0.001", "0.00000001", "0.01"));
    }

    private AssetInput buy(String asset, int priority, String amount, Map<String, BigDecimal> cash) {
        return new AssetInput(asset, priority, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BUY, d(amount), d("0.001"), cash);
    }

    private AssetInput sell(String asset, int priority, String quantity, String amount) {
        return new AssetInput(asset, priority, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.SELL, d(amount), d(quantity), Map.of("USDC", BigDecimal.ZERO));
    }

    private static Map<String, BigDecimal> cash(String usdc) {
        return Map.of("USDC", d(usdc));
    }

    private static PlanDraft.AssetDraft asset(PlanDraft plan, String symbol) {
        return plan.assets().stream().filter(a -> a.assetSymbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Achat PAXG avec paire USDC directe : 1 étape, limit IOC plafonné au-dessus de l'ask, GREEN")
    void paxgBuyDirectUsdc() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000"))));

        PlanDraft.AssetDraft a = asset(plan, "PAXG");
        assertEquals(AssetPlanOutcome.OK, a.outcome());
        assertEquals(1, a.steps().size());
        PlanDraft.StepDraft s = a.steps().getFirst();
        assertEquals("PAXG-USDC", s.instId());
        assertEquals(StepSide.BUY, s.side());
        assertEquals(1, s.rank());
        assertEquals(0, d("100").compareTo(s.quoteAmount()));
        assertEquals(0, d("0.9990").compareTo(s.sz()), "100 / 100.1 arrondi au lot vers le bas");
        assertEquals(0, d("100.3").compareTo(s.px()), "ask 100.1 + 0,1 % arrondi au tick vers le haut");
        assertEquals(FeeTestLevel.GREEN, a.feeTestLevel());
        assertEquals(PlanStatus.PLANNED, plan.status());
        assertNull(plan.blockReason());
    }

    @Test
    @DisplayName("Achat PAXG sans paire USDC : USDC -> BTC -> PAXG, jambe 2 sur le reçu net, avertissement « intermédiaire exposé », ETH non choisie")
    void paxgBuyViaBtc() {
        when(catalog.liveInstruments(okx)).thenReturn(krakenInstruments());

        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000"))));

        PlanDraft.AssetDraft a = asset(plan, "PAXG");
        assertEquals(AssetPlanOutcome.OK, a.outcome());
        assertEquals(2, a.steps().size());
        PlanDraft.StepDraft leg1 = a.steps().getFirst();
        PlanDraft.StepDraft leg2 = a.steps().get(1);
        assertEquals(List.of(1, 2), a.steps().stream().map(PlanDraft.StepDraft::rank).toList());
        assertEquals("BTC-USDC", leg1.instId());
        assertEquals(StepSide.BUY, leg1.side());
        assertEquals(0, d("100").compareTo(leg1.quoteAmount()));
        assertEquals("PAXG-BTC", leg2.instId());
        assertEquals(StepSide.BUY, leg2.side());
        assertTrue(leg2.quoteAmount().compareTo(d("0.01")) < 0, "jambe 2 en BTC : " + leg2.quoteAmount());
        // reçu net jambe 1 = 0.00199960 x 0.9995 ; sz2 = ⌊reçu / (0.08005 x 1.0005)⌋ au lot 1e-5
        assertEquals(0, d("0.02495").compareTo(leg2.sz()), "sz jambe 2 = " + leg2.sz());
        assertEquals(FeeTestLevel.GREEN, a.feeTestLevel());
        assertTrue(a.warning().contains("Intermédiaire exposé"), a.warning());
        assertTrue(a.warning().contains("USDC>ETH>PAXG non choisie"), a.warning());
    }

    @Test
    @DisplayName("Jambe 2 sous le minimum sur le reçu net de la jambe 1 : BELOW_MIN")
    void secondLegBelowMinOnNet() {
        when(catalog.liveInstruments(okx)).thenReturn(krakenInstruments());

        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "3", cash("1000"))));

        assertEquals(PlanBlockReason.BELOW_MIN, asset(plan, "PAXG").blockReason());
    }

    @Test
    @DisplayName("Stock en transit : la jambe 2 du stock est planifiée en tête, avant le nouvel achat")
    void transitStockFinishedFirst() {
        when(catalog.liveInstruments(okx)).thenReturn(krakenInstruments());
        AssetInput withStock = new AssetInput("PAXG", 0, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BUY, d("100"), d("0.001"), cash("1000"), Map.of("BTC", d("0.002")));

        PlanDraft.AssetDraft a = asset(planner.plan(List.of(withStock)), "PAXG");

        assertEquals(3, a.steps().size());
        PlanDraft.StepDraft finish = a.steps().getFirst();
        assertEquals("PAXG-BTC", finish.instId());
        assertEquals(StepSide.BUY, finish.side());
        assertEquals(1, finish.rank());
        assertEquals(0, d("0.002").compareTo(finish.quoteAmount()));
        assertEquals(0, d("0.02497").compareTo(finish.sz()), "⌊0,002 / (0,08005 x 1,0005)⌋");
        assertEquals("BTC-USDC", a.steps().get(1).instId());
        assertEquals("PAXG-BTC", a.steps().get(2).instId());
        assertTrue(a.warning().contains("stock en transit BTC"), a.warning());
        assertNotEquals(OrderPlanner.inputsHash(List.of(withStock)),
                OrderPlanner.inputsHash(List.of(buy("PAXG", 0, "100", cash("1000")))), "le stock fait partie des entrées");
    }

    @Test
    @DisplayName("Bilan virtuel : BTC consomme l'USDC, PAXG est calculé sur le reliquat ; fonds insuffisants => actif BLOCKED")
    void virtualBalanceAcrossAssets() {
        PlanDraft plan = planner.plan(List.of(buy("BTC", 0, "100", cash("200")), buy("PAXG", 1, "50", cash("200"))));
        assertEquals(AssetPlanOutcome.OK, asset(plan, "BTC").outcome());
        assertEquals(AssetPlanOutcome.OK, asset(plan, "PAXG").outcome());

        PlanDraft tooShort = planner.plan(List.of(buy("BTC", 0, "100", cash("120")), buy("PAXG", 1, "50", cash("120"))));
        assertEquals(AssetPlanOutcome.OK, asset(tooShort, "BTC").outcome());
        assertEquals(AssetPlanOutcome.BLOCKED, asset(tooShort, "PAXG").outcome());
        assertEquals(PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES, asset(tooShort, "PAXG").blockReason());
        assertEquals(PlanStatus.PLANNED, tooShort.status(), "un actif bloqué n'empêche pas les autres");
        assertEquals("PAXG:INSUFFICIENT_FUNDS_AFTER_FEES", tooShort.blockReason());
    }

    @Test
    @DisplayName("Solde USDC inférieur au montant : plan BLOCKED")
    void insufficientFunds() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("50"))));

        assertEquals(PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES, asset(plan, "PAXG").blockReason());
        assertEquals(PlanStatus.BLOCKED, plan.status());
    }

    @Test
    @DisplayName("Vente BTC : paire USDC directe ; ventes non créditées au bilan du jour")
    void sellDirectAndDoesNotCredit() {
        PlanDraft plan = planner.plan(List.of(sell("BTC", 0, "0.01", "500"), buy("PAXG", 1, "50", cash("0"))));

        PlanDraft.AssetDraft btc = asset(plan, "BTC");
        assertEquals(AssetPlanOutcome.OK, btc.outcome());
        PlanDraft.StepDraft s = btc.steps().getFirst();
        assertEquals("BTC-USDC", s.instId());
        assertEquals(StepSide.SELL, s.side());
        assertEquals(0, d("0.01").compareTo(s.sz()));
        assertEquals(0, d("49940.0").compareTo(s.px()), "bid 49990 - 0,1 % arrondi au tick vers le bas");
        assertNull(btc.warning());
        assertEquals(PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES, asset(plan, "PAXG").blockReason(),
                "le produit de la vente n'est pas crédité le jour même");
    }

    @Test
    @DisplayName("Vente PAXG sans paire USDC : PAXG -> BTC -> USDC, jambe 2 sur le reçu net, intermédiaire jamais conservé")
    void sellPaxgViaBtc() {
        when(catalog.liveInstruments(okx)).thenReturn(krakenInstruments());

        PlanDraft.AssetDraft a = asset(planner.plan(List.of(sell("PAXG", 0, "1", "4000"))), "PAXG");

        assertEquals(AssetPlanOutcome.OK, a.outcome());
        assertEquals(List.of("PAXG-BTC", "BTC-USDC"), a.steps().stream().map(PlanDraft.StepDraft::instId).toList());
        assertEquals(List.of(StepSide.SELL, StepSide.SELL), a.steps().stream().map(PlanDraft.StepDraft::side).toList());
        assertEquals(0, d("1").compareTo(a.steps().getFirst().sz()));
        // reçu net = 1 x 0.07995 x 0.9995 = 0.079910025 ; ⌊⌋ au lot 1e-8
        assertEquals(0, d("0.07991002").compareTo(a.steps().get(1).sz()), "sz jambe 2 = " + a.steps().get(1).sz());
        assertTrue(a.warning().contains("Intermédiaire exposé"), a.warning());
    }

    @Test
    @DisplayName("Actif sans route (PAXG fiat seul) : BLOCKED NOT_TRADABLE_WITHOUT_FIAT ; lecture NOT_TRADABLE : idem sans appel marché")
    void notTradableWithoutFiat() {
        when(catalog.liveInstruments(okx)).thenReturn(List.of(inst("PAXG", "USD", "0.003", "0.0001", "0.1"),
                inst("PAXG", "EUR", "0.003", "0.0001", "0.1"), inst("BTC", "USDC", "0.00001", "0.00001", "0.1")));
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000"))));
        assertEquals(PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT, asset(plan, "PAXG").blockReason());
        assertEquals(PlanStatus.BLOCKED, plan.status());

        AssetInput notTradable = new AssetInput("PAXG", 0, true, 7L, WebProviderCode.OKX, credential,
                PortfolioStatus.NOT_TRADABLE, null, null, null, Map.of());
        assertEquals(PlanBlockReason.NOT_TRADABLE_WITHOUT_FIAT, asset(planner.plan(List.of(notTradable)), "PAXG").blockReason());
        verify(books, never()).fetchBook(any(), eq("PAXG-USD"), anyInt());
    }

    @Test
    @DisplayName("Fee Test : WARNING et RED => étapes + avertissement (pas de blocage)")
    void feeTest() {
        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.1"), d("0.2")));
        PlanDraft warn = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000"))));
        assertEquals(AssetPlanOutcome.OK, asset(warn, "PAXG").outcome());
        assertEquals(FeeTestLevel.WARNING, asset(warn, "PAXG").feeTestLevel());
        assertTrue(asset(warn, "PAXG").warning().contains("seuil d'alerte"), asset(warn, "PAXG").warning());
        assertEquals(FeeTestLevel.WARNING, warn.feeTestLevel());

        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.5"), d("1")));
        PlanDraft red = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000"))));
        assertEquals(AssetPlanOutcome.OK, asset(red, "PAXG").outcome());
        assertEquals(FeeTestLevel.RED, asset(red, "PAXG").feeTestLevel());
        assertFalse(asset(red, "PAXG").steps().isEmpty());
        assertTrue(asset(red, "PAXG").warning().contains("seuil rouge"), asset(red, "PAXG").warning());
        assertEquals(PlanStatus.PLANNED, red.status());
    }

    @Test
    @DisplayName("Sous le minimum de l'exchange : BELOW_MIN")
    void belowMin() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "0.005", cash("1000"))));

        assertEquals(PlanBlockReason.BELOW_MIN, asset(plan, "PAXG").blockReason());
    }

    @Test
    @DisplayName("Binding non activé => DISABLED ; action NONE/BLOCKED ou lecture indisponible => aucune étape")
    void disabledNoActionUnavailable() {
        AssetInput disabled = new AssetInput("BTC", 0, false, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BUY, d("100"), d("0.001"), cash("500"));
        PlanDraft onlyDisabled = planner.plan(List.of(disabled));
        assertEquals(AssetPlanOutcome.DISABLED, asset(onlyDisabled, "BTC").outcome());
        assertEquals(PlanStatus.DISABLED, onlyDisabled.status());

        AssetInput none = new AssetInput("ETH", 1, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.NONE, null, null, cash("500"));
        AssetInput upstreamBlocked = new AssetInput("PAXG", 2, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BLOCKED, d("100"), d("0.001"), cash("500"));
        AssetInput stale = new AssetInput("SOL", 3, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.STALE,
                RainbowLiveAction.BUY, d("100"), d("0.001"), cash("500"));
        PlanDraft plan = planner.plan(List.of(none, upstreamBlocked, stale));
        assertEquals(AssetPlanOutcome.NO_ACTION, asset(plan, "ETH").outcome());
        assertEquals(AssetPlanOutcome.NO_ACTION, asset(plan, "PAXG").outcome());
        assertEquals(PlanBlockReason.READING_UNAVAILABLE, asset(plan, "SOL").blockReason());
        assertEquals(PlanStatus.BLOCKED, plan.status());
        verify(books, never()).fetchBook(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Marché illisible pour un actif : cet actif est bloqué, les autres sont planifiés")
    void marketFailureIsolatedPerAsset() {
        when(fees.getFeeRate(any(), eq("PAXG-USDC"))).thenThrow(new IllegalStateException("fees down"));

        PlanDraft plan = planner.plan(List.of(buy("BTC", 0, "100", cash("500")), buy("PAXG", 1, "50", cash("500"))));

        assertEquals(AssetPlanOutcome.OK, asset(plan, "BTC").outcome());
        assertEquals(PlanBlockReason.READING_UNAVAILABLE, asset(plan, "PAXG").blockReason());
    }

    @Test
    @DisplayName("Un carnet n'est lu qu'une fois par plan et par instrument")
    void bookReadOncePerInstrument() {
        planner.plan(List.of(buy("BTC", 0, "100", cash("1000")), buy("BTC", 1, "100", cash("1000"))));

        verify(books, times(1)).fetchBook(any(), eq("BTC-USDC"), anyInt());
    }

    @Test
    @DisplayName("Hash des entrées : déterministe, insensible à l'ordre, sensible aux montants")
    void inputsHash() {
        AssetInput a = buy("BTC", 0, "100", cash("500"));
        AssetInput b = buy("PAXG", 1, "50", cash("500"));
        assertEquals(OrderPlanner.inputsHash(List.of(a, b)), OrderPlanner.inputsHash(new ArrayList<>(List.of(b, a))));
        assertNotEquals(OrderPlanner.inputsHash(List.of(a, b)), OrderPlanner.inputsHash(List.of(a, buy("PAXG", 1, "51", cash("500")))));
        assertEquals(64, OrderPlanner.inputsHash(List.of(a)).length());
    }

    @Test
    @DisplayName("clOrdId : déterministe, alphanumérique, 32 caractères maximum, distinct par wallet")
    void clOrdId() {
        var t2355 = fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass.T2355;
        LocalDate day = LocalDate.of(2026, 10, 9);
        String id = ClOrdIds.of(1234567L, 12L, "PAXG", day, t2355, 2);

        assertEquals(id, ClOrdIds.of(1234567L, 12L, "PAXG", day, t2355, 2));
        assertEquals("t54qglj1cpaxg20261009102", id);
        assertTrue(id.length() <= 32 && id.matches("[a-z0-9]+"));
        assertNotEquals(id, ClOrdIds.of(1234567L, 12L, "PAXG", day, t2355, 1));
        assertNotEquals(id, ClOrdIds.of(1234567L, 13L, "PAXG", day, t2355, 2), "un autre wallet donne un autre identifiant");
        assertNotEquals(ClOrdIds.of(1L, 11L, "BTC", day, t2355, 1), ClOrdIds.of(11L, 1L, "BTC", day, t2355, 1),
                "userId/walletId non ambigus");
    }

    @Test
    @DisplayName("clOrdId : longueur maximale avec des identifiants réalistes (user 6 chiffres, wallet 6 chiffres, PAXG)")
    void clOrdIdMaxLength() {
        String id = ClOrdIds.of(999_999L, 999_999L, "PAXG", LocalDate.of(2026, 12, 31),
                fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass.T0005, 99);
        assertTrue(id.length() <= ClOrdIds.MAX_LENGTH, id);
    }
}
