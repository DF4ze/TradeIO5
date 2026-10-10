package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PathFinder : chemin d'achat le moins coûteux, sans fiat, ≤ 2 sauts")
class PathFinderTest {

    private static final Set<String> STABLES = Set.of("USDC", "USDT");
    private static final Set<String> FIAT = ExecutionDefaults.FIAT_CURRENCIES;

    private final PathFinder finder = new PathFinder(new FeeTest());

    static class StubSource implements PathFinder.LegMarketSource {
        final Map<String, LegMarket> markets = new HashMap<>();
        final Set<String> stables;
        int marketCalls;

        StubSource(Set<String> stables) {
            this.stables = stables;
        }

        StubSource with(String instId, LegMarket market) {
            markets.put(instId, market);
            return this;
        }

        @Override
        public boolean accepts(InstrumentInfo instrument, LegSide side) {
            return stables.contains(instrument.quote());
        }

        @Override
        public Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notionalAmount) {
            marketCalls++;
            return Optional.ofNullable(markets.get(instrument.instId()));
        }
    }

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static InstrumentInfo inst(String base, String quote, String min, String lot, String tick) {
        return new InstrumentInfo(base + "-" + quote, base, quote, d(min), d(lot), d(tick));
    }

    private static LegMarket market(String fee, String bid, String ask, String slippage) {
        return new LegMarket(d(fee), d(bid), d(ask), d(slippage), PathEstimation.BOOK);
    }

    private static final InstrumentInfo PAXG_USDT = inst("PAXG", "USDT", "0.0001", "0.0001", "0.1");
    private static final InstrumentInfo PAXG_USDC = inst("PAXG", "USDC", "0.0001", "0.0001", "0.1");
    private static final InstrumentInfo USDC_USDT = inst("USDC", "USDT", "1", "0.01", "0.0001");
    private static final InstrumentInfo BTC_USDC = inst("BTC", "USDC", "0.00001", "0.00000001", "0.1");

    /** Coût 0,08 + demi-spread 0,1 + slippage 0,01 = 0,19 %. */
    private static final LegMarket PAXG_MARKET = market("0.08", "99.9", "100.1", "0.01");
    /** Coût 0,05 + demi-spread 0,01 = 0,06 %. */
    private static final LegMarket BRIDGE_MARKET = market("0.05", "0.9999", "1.0001", "0");

    private PathFinder.Request request(List<InstrumentInfo> instruments, Map<String, BigDecimal> sources, String target,
                                       String amount, String preferredQuote) {
        return new PathFinder.Request(instruments, FIAT, sources, target, d(amount), 2, preferredQuote);
    }

    private static void assertPct(String expected, BigDecimal actual) {
        assertEquals(0, d(expected).compareTo(actual), "attendu " + expected + " obtenu " + actual);
    }

    @Test
    @DisplayName("PAXG/OKX avec USDT seul : 1 saut PAXG-USDT, coût 0,19 % GREEN, taille ⌊⌋ au lot")
    void paxgWithUsdtOnly() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET);

        PathQuote quote = finder.find(request(List.of(PAXG_USDT, USDC_USDT), Map.of("USDT", d("1000")), "PAXG", "1000", null), source);

        assertEquals(PathStatus.OK, quote.status());
        assertEquals("USDT", quote.source());
        assertEquals(1, quote.legs().size());
        PathLeg leg = quote.legs().getFirst();
        assertEquals("PAXG-USDT", leg.instId());
        assertEquals(LegSide.BUY, leg.side());
        assertPct("9.99", leg.sz());
        assertPct("100.1", leg.refPrice());
        assertPct("0.08", leg.feePct());
        assertPct("0.1", leg.spreadPct());
        assertPct("0.01", leg.slippagePct());
        assertPct("0.19", quote.totalCostPct());
        assertEquals(FeeTestLevel.GREEN, quote.feeTestLevel());
        assertEquals(PathEstimation.BOOK, quote.estimation());
        assertFalse(quote.underfunded());
    }

    @Test
    @DisplayName("PAXG/OKX avec USDC seul : USDC-USDT puis PAXG-USDT (2 sauts), somme brute 0,25 % WARNING, ⌈⌉ sur la vente")
    void paxgWithUsdcOnly() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET).with("USDC-USDT", BRIDGE_MARKET);

        PathQuote quote = finder.find(request(List.of(PAXG_USDT, USDC_USDT), Map.of("USDC", d("1000")), "PAXG", "1000", null), source);

        assertEquals(PathStatus.OK, quote.status());
        assertEquals("USDC", quote.source());
        assertEquals(List.of("USDC-USDT", "PAXG-USDT"), quote.legs().stream().map(PathLeg::instId).toList());
        assertEquals(List.of(LegSide.SELL, LegSide.BUY), quote.legs().stream().map(PathLeg::side).toList());
        assertPct("1000.11", quote.legs().getFirst().sz());
        assertPct("0.9999", quote.legs().getFirst().refPrice());
        assertPct("0.06", quote.legs().getFirst().costPct());
        assertPct("0.19", quote.legs().getLast().costPct());
        assertPct("0.25", quote.totalCostPct());
        assertEquals(FeeTestLevel.WARNING, quote.feeTestLevel());
    }

    @Test
    @DisplayName("Coût total = somme brute des coûts des jambes")
    void totalIsGrossSum() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET).with("USDC-USDT", BRIDGE_MARKET);

        PathQuote quote = finder.find(request(List.of(PAXG_USDT, USDC_USDT), Map.of("USDC", d("1000")), "PAXG", "1000", null), source);

        BigDecimal sum = quote.legs().stream().map(PathLeg::costPct).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.setScale(6, RoundingMode.HALF_UP).compareTo(quote.totalCostPct()));
        quote.legs().forEach(l -> assertEquals(0, l.feePct().add(l.spreadPct()).add(l.slippagePct())
                .setScale(6, RoundingMode.HALF_UP).compareTo(l.costPct())));
    }

    @Test
    @DisplayName("USDT et USDC détenus : le chemin direct moins cher l'emporte")
    void cheapestSourceWins() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET).with("USDC-USDT", BRIDGE_MARKET);

        PathQuote quote = finder.find(request(List.of(PAXG_USDT, USDC_USDT),
                Map.of("USDC", d("1000"), "USDT", d("1000")), "PAXG", "1000", null), source);

        assertEquals("USDT", quote.source());
        assertEquals(1, quote.legs().size());
    }

    @Test
    @DisplayName("PAXG/Kraken (USD, EUR, BTC, ETH seulement) : NO_PATH, aucune donnée de marché demandée")
    void paxgKrakenNoPath() {
        List<InstrumentInfo> kraken = List.of(inst("PAXG", "USD", "0.003", "0.00000001", "0.01"),
                inst("PAXG", "EUR", "0.003", "0.00000001", "0.01"), inst("PAXG", "BTC", "0.003", "0.00000001", "0.00001"),
                inst("PAXG", "ETH", "0.003", "0.00000001", "0.0001"), inst("BTC", "USDC", "0.0001", "0.00000001", "0.1"),
                inst("BTC", "USDT", "0.0001", "0.00000001", "0.1"), inst("ETH", "USDC", "0.002", "0.00000001", "0.01"),
                inst("USDC", "USD", "1", "0.01", "0.0001"), inst("USDT", "USD", "1", "0.01", "0.0001"));
        StubSource source = new StubSource(STABLES);

        PathQuote quote = finder.find(request(kraken, Map.of("USDC", d("1000"), "USDT", d("500")), "PAXG", "100", null), source);

        assertEquals(PathStatus.NO_PATH, quote.status());
        assertTrue(quote.legs().isEmpty());
        assertNull(quote.totalCostPct());
        assertNull(quote.feeTestLevel());
        assertEquals(0, source.marketCalls);
    }

    @Test
    @DisplayName("Fiat jamais dans un chemin, même si la source de marché accepterait la paire")
    void fiatNodesForbidden() {
        StubSource acceptAll = new StubSource(Set.of("USD", "EUR", "USDC")) {
            @Override
            public boolean accepts(InstrumentInfo instrument, LegSide side) {
                return true;
            }
        }.with("PAXG-USD", PAXG_MARKET).with("USDC-USD", BRIDGE_MARKET);
        List<InstrumentInfo> instruments = List.of(inst("PAXG", "USD", "0.003", "0.0001", "0.01"),
                inst("USDC", "USD", "1", "0.01", "0.0001"));

        assertEquals(PathStatus.NO_PATH, finder.find(request(instruments, Map.of("USDC", d("1000")), "PAXG", "100", null), acceptAll).status());
        assertEquals(PathStatus.NO_PATH, finder.find(request(instruments, Map.of("USD", d("1000")), "PAXG", "100", null), acceptAll).status());
        assertEquals(0, acceptAll.marketCalls);
    }

    @Test
    @DisplayName("BTC/OKX avec BTC-USDC : 1 saut")
    void btcDirect() {
        StubSource source = new StubSource(STABLES).with("BTC-USDC", market("0.08", "70000", "70010", "0"));

        PathQuote quote = finder.find(request(List.of(BTC_USDC, USDC_USDT), Map.of("USDC", d("500")), "BTC", "500", null), source);

        assertEquals(PathStatus.OK, quote.status());
        assertEquals(List.of("BTC-USDC"), quote.legs().stream().map(PathLeg::instId).toList());
        assertPct("0.00714183", quote.legs().getFirst().sz());
        assertPct("70010", quote.legs().getFirst().refPrice());
    }

    @Test
    @DisplayName("Profondeur 3 jamais explorée, même si la demande l'autorise")
    void depthThreeNeverExplored() {
        Set<String> nodes = Set.of("AAA", "BBB", "CCC");
        List<InstrumentInfo> chain = List.of(inst("BBB", "AAA", "1", "1", "1"), inst("CCC", "BBB", "1", "1", "1"),
                inst("TGT", "CCC", "1", "1", "1"));
        StubSource source = new StubSource(nodes);
        chain.forEach(i -> source.with(i.instId(), market("0.01", "1", "1", "0")));

        PathQuote three = finder.find(new PathFinder.Request(chain, FIAT, Map.of("AAA", d("100")), "TGT", d("10"), 5, null), source);

        assertEquals(PathStatus.NO_PATH, three.status());
        assertEquals(0, source.marketCalls);

        PathQuote two = finder.find(new PathFinder.Request(chain.subList(1, 3), FIAT, Map.of("BBB", d("100")), "TGT", d("10"), 5, null), source);
        assertEquals(PathStatus.OK, two.status());
        assertEquals(2, two.legs().size());
    }

    @Test
    @DisplayName("sz < minSz => BELOW_MIN (coût tout de même chiffré) ; sz arrondie à 0 aussi")
    void belowMin() {
        InstrumentInfo bigMin = inst("PAXG", "USDT", "0.01", "0.0001", "0.1");
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET);

        PathQuote quote = finder.find(request(List.of(bigMin), Map.of("USDT", d("1000")), "PAXG", "0.5", null), source);

        assertEquals(PathStatus.BELOW_MIN, quote.status());
        assertTrue(quote.legs().getFirst().belowMin());
        assertPct("0.0049", quote.legs().getFirst().sz());
        assertPct("0.19", quote.totalCostPct());

        PathQuote zero = finder.find(request(List.of(PAXG_USDT), Map.of("USDT", d("1000")), "PAXG", "0.001", null), source);
        assertEquals(PathStatus.BELOW_MIN, zero.status());
        assertPct("0", zero.legs().getFirst().sz());

        assertEquals(PathStatus.OK, finder.find(request(List.of(bigMin), Map.of("USDT", d("1000")), "PAXG", "5", null), source).status());
    }

    @Test
    @DisplayName("Arrondis : lot ⌊⌋/⌈⌉, tick ↑ achat / ↓ vente")
    void lotAndTickRounding() {
        assertPct("1.24", PathFinder.roundToStep(d("1.2345"), d("0.01"), RoundingMode.UP));
        assertPct("1.23", PathFinder.roundToStep(d("1.2345"), d("0.01"), RoundingMode.DOWN));
        assertPct("1.23", PathFinder.roundToStep(d("1.23"), d("0.01"), RoundingMode.UP));

        InstrumentInfo paxgUsdt = inst("PAXG", "USDT", "0.0001", "0.001", "0.1");
        StubSource buy = new StubSource(STABLES).with("PAXG-USDT", market("0.1", "100.07", "100.13", "0"));
        PathLeg buyLeg = finder.find(request(List.of(paxgUsdt), Map.of("USDT", d("1000")), "PAXG", "1000", null), buy).legs().getFirst();
        assertPct("100.2", buyLeg.refPrice());
        assertPct("9.987", buyLeg.sz());

        InstrumentInfo xxxUsdt = inst("XXX", "USDT", "0.001", "0.001", "0.1");
        StubSource sell = new StubSource(STABLES).with("XXX-USDT", market("0.1", "99.87", "100.13", "0"));
        PathLeg sellLeg = finder.find(request(List.of(xxxUsdt), Map.of("XXX", d("50")), "USDT", "100", null), sell).legs().getFirst();
        assertEquals(LegSide.SELL, sellLeg.side());
        assertPct("99.8", sellLeg.refPrice());
        assertPct("1.002", sellLeg.sz());
    }

    @Test
    @DisplayName("preferredQuote : préfère PAXG-USDT quand il existe ; sans effet s'il n'existe pas ; par défaut coût minimal")
    void preferredQuote() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET)
                .with("PAXG-USDC", market("0.05", "99.95", "100.05", "0"));
        List<InstrumentInfo> instruments = List.of(PAXG_USDT, PAXG_USDC);
        Map<String, BigDecimal> sources = Map.of("USDT", d("1000"), "USDC", d("1000"));

        assertEquals("PAXG-USDC", finder.find(request(instruments, sources, "PAXG", "1000", null), source).legs().getFirst().instId());
        assertEquals("PAXG-USDT", finder.find(request(instruments, sources, "PAXG", "1000", "USDT"), source).legs().getFirst().instId());
        assertEquals("PAXG-USDC", finder.find(request(instruments, sources, "PAXG", "1000", "EUR"), source).legs().getFirst().instId());
    }

    @Test
    @DisplayName("Source insuffisante : devis marqué underfunded ; une source suffisante est préférée")
    void underfunded() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET).with("USDC-USDT", BRIDGE_MARKET);
        List<InstrumentInfo> instruments = List.of(PAXG_USDT, USDC_USDT);

        PathQuote poor = finder.find(request(instruments, Map.of("USDT", d("10")), "PAXG", "1000", null), source);
        assertEquals(PathStatus.OK, poor.status());
        assertTrue(poor.underfunded());

        PathQuote mixed = finder.find(request(instruments, Map.of("USDT", d("10"), "USDC", d("2000")), "PAXG", "1000", null), source);
        assertEquals("USDC", mixed.source());
        assertFalse(mixed.underfunded());
    }

    @Test
    @DisplayName("Aucun solde exploitable (nul, fiat, cible) ou marché indisponible : NO_PATH")
    void noUsableSource() {
        StubSource source = new StubSource(STABLES).with("PAXG-USDT", PAXG_MARKET);
        assertEquals(PathStatus.NO_PATH, finder.find(request(List.of(PAXG_USDT), Map.of("USDT", BigDecimal.ZERO), "PAXG", "100", null), source).status());
        assertEquals(PathStatus.NO_PATH, finder.find(request(List.of(PAXG_USDT), Map.of(), "PAXG", "100", null), source).status());
        assertEquals(PathStatus.NO_PATH, finder.find(request(List.of(PAXG_USDT), Map.of("PAXG", d("5")), "PAXG", "100", null), source).status());

        StubSource unavailable = new StubSource(STABLES);
        assertEquals(PathStatus.NO_PATH, finder.find(request(List.of(PAXG_USDT), Map.of("USDT", d("1000")), "PAXG", "100", null), unavailable).status());
    }

    @Test
    @DisplayName("Fee Test sur le coût total : frais élevés => RED ; estimation TICKER propagée")
    void feeTestAndEstimation() {
        StubSource red = new StubSource(STABLES).with("PAXG-USDT",
                new LegMarket(d("0.9"), d("99.9"), d("100.1"), BigDecimal.ZERO, PathEstimation.TICKER));

        PathQuote quote = finder.find(request(List.of(PAXG_USDT), Map.of("USDT", d("1000")), "PAXG", "1000", null), red);

        assertPct("1", quote.totalCostPct());
        assertEquals(FeeTestLevel.RED, quote.feeTestLevel());
        assertEquals(PathEstimation.TICKER, quote.estimation());
    }
}
