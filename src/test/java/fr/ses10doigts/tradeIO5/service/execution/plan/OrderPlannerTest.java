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
        when(groups.members(AssetGroup.USD)).thenReturn(List.of("USDC", "USDT"));
        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.05"), d("0.05")));
        when(books.fetchBook(any(), eq("PAXG-USDT"), anyInt())).thenReturn(book("99.9", "100.1"));
        when(books.fetchBook(any(), eq("BTC-USDC"), anyInt())).thenReturn(book("49990", "50010"));
        when(books.fetchBook(any(), eq("BTC-USDT"), anyInt())).thenReturn(book("49990", "50010"));
        when(books.fetchBook(any(), eq("USDC-USDT"), anyInt())).thenReturn(book("0.9999", "1.0001"));
        when(catalog.liveInstruments(okx)).thenReturn(okxInstruments());
        planner = new OrderPlanner(catalog, List.of(fees), List.of(books), groups, new PathFinder(new FeeTest()), new FeeTest(),
                new ExecutionSettings(), "");
    }

    private static List<InstrumentInfo> okxInstruments() {
        return List.of(inst("PAXG", "USDT", "0.0001", "0.0001", "0.1"), inst("BTC", "USDC", "0.00001", "0.00001", "0.1"),
                inst("BTC", "USDT", "0.00001", "0.00001", "0.1"), inst("USDC", "USDT", "1", "0.0001", "0.0001"));
    }

    private AssetInput buy(String asset, int priority, String amount, Map<String, BigDecimal> cash) {
        return new AssetInput(asset, priority, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BUY, d(amount), d("0.001"), cash);
    }

    private AssetInput sell(String asset, int priority, String quantity, String amount) {
        return new AssetInput(asset, priority, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.SELL, d(amount), d(quantity), Map.of("USDC", BigDecimal.ZERO, "USDT", BigDecimal.ZERO));
    }

    private static Map<String, BigDecimal> cash(String usdc, String usdt) {
        return Map.of("USDC", d(usdc), "USDT", d(usdt));
    }

    private static PlanDraft.AssetDraft asset(PlanDraft plan, String symbol) {
        return plan.assets().stream().filter(a -> a.assetSymbol().equals(symbol)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Achat PAXG/OKX avec USDT suffisant : 1 étape PAXG-USDT, limit IOC plafonné au-dessus de l'ask, GREEN")
    void paxgBuyWithUsdt() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("0", "1000"))));

        PlanDraft.AssetDraft a = asset(plan, "PAXG");
        assertEquals(AssetPlanOutcome.OK, a.outcome());
        assertEquals(1, a.steps().size());
        PlanDraft.StepDraft s = a.steps().getFirst();
        assertEquals("PAXG-USDT", s.instId());
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
    @DisplayName("Achat PAXG avec USDC seul : pont USDC-USDT (vente) puis PAXG-USDT, pont = manque + marge de frais")
    void paxgBuyBridgesFromUsdc() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000", "0"))));

        PlanDraft.AssetDraft a = asset(plan, "PAXG");
        assertEquals(2, a.steps().size());
        PlanDraft.StepDraft bridge = a.steps().getFirst();
        assertEquals("USDC-USDT", bridge.instId());
        assertEquals(StepSide.SELL, bridge.side());
        assertEquals(0, d("100.3").compareTo(bridge.quoteAmount()), "100 x (1 + 0,3 %)");
        assertEquals(2, a.steps().get(1).rank());
        assertEquals("PAXG-USDT", a.steps().get(1).instId());
        assertEquals(0, d("100").compareTo(a.steps().get(1).quoteAmount()));
        assertTrue(a.costPct().compareTo(d("0.2")) > 0 || a.costPct().compareTo(d("0.1")) > 0, "somme brute des 2 jambes");
    }

    @Test
    @DisplayName("USDT partiel : le pont ne couvre que le reliquat (+ marge)")
    void partialUsdtBridgesOnlyShortage() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000", "40"))));

        PlanDraft.AssetDraft a = asset(plan, "PAXG");
        assertEquals(2, a.steps().size());
        assertEquals(0, d("60.18").compareTo(a.steps().getFirst().quoteAmount()), "(100 - 40) x 1,003");
    }

    @Test
    @DisplayName("Bilan virtuel : BTC consomme l'USDC, le pont PAXG est calculé sur le reliquat USDC ; fonds insuffisants => actif BLOCKED")
    void virtualBalanceAcrossAssets() {
        PlanDraft plan = planner.plan(List.of(buy("BTC", 0, "100", cash("200", "0")), buy("PAXG", 1, "50", cash("200", "0"))));

        PlanDraft.AssetDraft btc = asset(plan, "BTC");
        assertEquals(1, btc.steps().size());
        assertEquals("BTC-USDC", btc.steps().getFirst().instId());
        PlanDraft.AssetDraft paxg = asset(plan, "PAXG");
        assertEquals(2, paxg.steps().size());
        assertEquals(0, d("50.15").compareTo(paxg.steps().getFirst().quoteAmount()), "pont sur les 100 USDC restants");

        PlanDraft tooShort = planner.plan(List.of(buy("BTC", 0, "100", cash("120", "0")), buy("PAXG", 1, "50", cash("120", "0"))));
        assertEquals(AssetPlanOutcome.OK, asset(tooShort, "BTC").outcome());
        assertEquals(AssetPlanOutcome.BLOCKED, asset(tooShort, "PAXG").outcome());
        assertEquals(PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES, asset(tooShort, "PAXG").blockReason());
        assertEquals(PlanStatus.PLANNED, tooShort.status(), "un actif bloqué n'empêche pas les autres");
        assertEquals("PAXG:INSUFFICIENT_FUNDS_AFTER_FEES", tooShort.blockReason());
    }

    @Test
    @DisplayName("Fonds insuffisants une fois les frais du pont ajoutés : plan BLOCKED")
    void insufficientAfterFees() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("100", "0"))));

        assertEquals(PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES, asset(plan, "PAXG").blockReason());
        assertEquals(PlanStatus.BLOCKED, plan.status());
    }

    @Test
    @DisplayName("Vente BTC : BTC-USDT forcé quand la paire existe ; ventes non créditées au bilan du jour")
    void sellForcesUsdtAndDoesNotCredit() {
        PlanDraft plan = planner.plan(List.of(sell("BTC", 0, "0.01", "500"), buy("PAXG", 1, "50", cash("0", "0"))));

        PlanDraft.AssetDraft btc = asset(plan, "BTC");
        assertEquals(AssetPlanOutcome.OK, btc.outcome());
        PlanDraft.StepDraft s = btc.steps().getFirst();
        assertEquals("BTC-USDT", s.instId());
        assertEquals(StepSide.SELL, s.side());
        assertEquals(0, d("0.01").compareTo(s.sz()));
        assertEquals(0, d("49940.0").compareTo(s.px()), "bid 49990 - 0,1 % arrondi au tick vers le bas");
        assertNull(btc.warning());
        assertEquals(PlanBlockReason.INSUFFICIENT_FUNDS_AFTER_FEES, asset(plan, "PAXG").blockReason(),
                "le produit de la vente n'est pas crédité le jour même");
    }

    @Test
    @DisplayName("Vente sans paire X-USDT : cotation naturelle (USDC) + avertissement")
    void sellNaturalQuote() {
        when(catalog.liveInstruments(okx)).thenReturn(List.of(inst("BTC", "USDC", "0.00001", "0.00001", "0.1")));

        PlanDraft.AssetDraft btc = asset(planner.plan(List.of(sell("BTC", 0, "0.01", "500"))), "BTC");

        assertEquals("BTC-USDC", btc.steps().getFirst().instId());
        assertTrue(btc.warning().contains("pas de paire BTC-USDT") && btc.warning().contains("USDC"), btc.warning());
    }

    @Test
    @DisplayName("Actif sans paire stable (Kraken PAXG) : BLOCKED NOT_TRADABLE_WITHOUT_FIAT ; lecture NOT_TRADABLE : idem sans appel marché")
    void notTradableWithoutFiat() {
        when(catalog.liveInstruments(okx)).thenReturn(List.of(inst("PAXG", "USD", "0.003", "0.0001", "0.1"),
                inst("PAXG", "EUR", "0.003", "0.0001", "0.1"), inst("BTC", "USDC", "0.00001", "0.00001", "0.1")));
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "100", cash("1000", "0"))));
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
        PlanDraft warn = planner.plan(List.of(buy("PAXG", 0, "100", cash("0", "1000"))));
        assertEquals(AssetPlanOutcome.OK, asset(warn, "PAXG").outcome());
        assertEquals(FeeTestLevel.WARNING, asset(warn, "PAXG").feeTestLevel());
        assertTrue(asset(warn, "PAXG").warning().contains("seuil d'alerte"), asset(warn, "PAXG").warning());
        assertEquals(FeeTestLevel.WARNING, warn.feeTestLevel());

        when(fees.getFeeRate(any(), anyString())).thenReturn(new FeeRate(d("0.5"), d("1")));
        PlanDraft red = planner.plan(List.of(buy("PAXG", 0, "100", cash("0", "1000"))));
        assertEquals(AssetPlanOutcome.OK, asset(red, "PAXG").outcome());
        assertEquals(FeeTestLevel.RED, asset(red, "PAXG").feeTestLevel());
        assertFalse(asset(red, "PAXG").steps().isEmpty());
        assertTrue(asset(red, "PAXG").warning().contains("seuil rouge"), asset(red, "PAXG").warning());
        assertEquals(PlanStatus.PLANNED, red.status());
    }

    @Test
    @DisplayName("Sous le minimum de l'exchange : BELOW_MIN")
    void belowMin() {
        PlanDraft plan = planner.plan(List.of(buy("PAXG", 0, "0.005", cash("0", "1000"))));

        assertEquals(PlanBlockReason.BELOW_MIN, asset(plan, "PAXG").blockReason());
    }

    @Test
    @DisplayName("Binding non activé => DISABLED ; action NONE/BLOCKED ou lecture indisponible => aucune étape")
    void disabledNoActionUnavailable() {
        AssetInput disabled = new AssetInput("BTC", 0, false, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BUY, d("100"), d("0.001"), cash("500", "0"));
        PlanDraft onlyDisabled = planner.plan(List.of(disabled));
        assertEquals(AssetPlanOutcome.DISABLED, asset(onlyDisabled, "BTC").outcome());
        assertEquals(PlanStatus.DISABLED, onlyDisabled.status());

        AssetInput none = new AssetInput("ETH", 1, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.NONE, null, null, cash("500", "0"));
        AssetInput upstreamBlocked = new AssetInput("PAXG", 2, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.OK,
                RainbowLiveAction.BLOCKED, d("100"), d("0.001"), cash("500", "0"));
        AssetInput stale = new AssetInput("SOL", 3, true, 7L, WebProviderCode.OKX, credential, PortfolioStatus.STALE,
                RainbowLiveAction.BUY, d("100"), d("0.001"), cash("500", "0"));
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
        when(fees.getFeeRate(any(), eq("PAXG-USDT"))).thenThrow(new IllegalStateException("fees down"));

        PlanDraft plan = planner.plan(List.of(buy("BTC", 0, "100", cash("500", "0")), buy("PAXG", 1, "50", cash("500", "0"))));

        assertEquals(AssetPlanOutcome.OK, asset(plan, "BTC").outcome());
        assertEquals(PlanBlockReason.READING_UNAVAILABLE, asset(plan, "PAXG").blockReason());
    }

    @Test
    @DisplayName("Un carnet n'est lu qu'une fois par plan et par instrument")
    void bookReadOncePerInstrument() {
        planner.plan(List.of(buy("PAXG", 0, "100", cash("1000", "0")), buy("BTC", 1, "100", cash("1000", "0"))));

        verify(books, times(1)).fetchBook(any(), eq("USDC-USDT"), anyInt());
    }

    @Test
    @DisplayName("Hash des entrées : déterministe, insensible à l'ordre, sensible aux montants")
    void inputsHash() {
        AssetInput a = buy("BTC", 0, "100", cash("500", "0"));
        AssetInput b = buy("PAXG", 1, "50", cash("500", "0"));
        assertEquals(OrderPlanner.inputsHash(List.of(a, b)), OrderPlanner.inputsHash(new ArrayList<>(List.of(b, a))));
        assertNotEquals(OrderPlanner.inputsHash(List.of(a, b)), OrderPlanner.inputsHash(List.of(a, buy("PAXG", 1, "51", cash("500", "0")))));
        assertEquals(64, OrderPlanner.inputsHash(List.of(a)).length());
    }

    @Test
    @DisplayName("clOrdId : déterministe, alphanumérique, 32 caractères maximum")
    void clOrdId() {
        String id = ClOrdIds.of(1234567L, "PAXG", LocalDate.of(2026, 10, 9), fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass.T2355, 2);

        assertEquals(id, ClOrdIds.of(1234567L, "PAXG", LocalDate.of(2026, 10, 9), fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass.T2355, 2));
        assertEquals("t5qgljpaxg20261009102", id);
        assertTrue(id.length() <= 32 && id.matches("[a-z0-9]+"));
        assertNotEquals(id, ClOrdIds.of(1234567L, "PAXG", LocalDate.of(2026, 10, 9), fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass.T2355, 1));
    }
}
