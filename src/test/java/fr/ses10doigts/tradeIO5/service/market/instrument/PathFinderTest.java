package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegMarket;
import fr.ses10doigts.tradeIO5.model.dto.execution.LegSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathLeg;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.dto.execution.RouteQuote;
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
        return new PathFinder.Request(instruments, FIAT, sources, target, d(amount), 2);
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
        assertPct("1000", quote.legs().getFirst().sz());
        assertPct("0.9999", quote.legs().getFirst().refPrice());
        assertPct("0.06", quote.legs().getFirst().costPct());
        assertPct("0.19", quote.legs().getLast().costPct());
        assertPct("0.25", quote.totalCostPct());
        assertEquals(FeeTestLevel.WARNING, quote.feeTestLevel());
    }

    @Test
    @DisplayName("costMin (Kraken) : coût de la jambe sous le minimum en cotation => BELOW_MIN ; sans costMin (OKX) inchangé")
    void costMinBelowMin() {
        InstrumentInfo withCostMin = new InstrumentInfo("BTC-USDC", "BTC", "USDC", d("0.00001"), d("0.00000001"), d("0.1"), d("50"), "USD");
        StubSource source = new StubSource(STABLES).with("BTC-USDC", market("0.05", "99.9", "100.1", "0.01"));

        assertEquals(PathStatus.BELOW_MIN, finder.find(request(List.of(withCostMin), Map.of("USDC", d("10")), "BTC", "10", null), source).status());
        assertEquals(PathStatus.OK, finder.find(request(List.of(BTC_USDC), Map.of("USDC", d("10")), "BTC", "10", null), source).status());
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

        PathQuote three = finder.find(new PathFinder.Request(chain, FIAT, Map.of("AAA", d("100")), "TGT", d("10"), 5), source);

        assertEquals(PathStatus.NO_PATH, three.status());
        assertEquals(0, source.marketCalls);

        PathQuote two = finder.find(new PathFinder.Request(chain.subList(1, 3), FIAT, Map.of("BBB", d("100")), "TGT", d("10"), 5), source);
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
        assertPct("100", sellLeg.sz());
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

    // ------------------------------------------------------------------ routes via intermédiaire non stable

    /** Source de marché qui valorise aussi BTC/ETH comme cotations et donne leur prix USD. */
    static class CryptoSource extends StubSource {
        final Map<String, BigDecimal> usd = new HashMap<>(Map.of("USDC", BigDecimal.ONE, "BTC", new BigDecimal("50000"),
                "ETH", new BigDecimal("3000")));

        CryptoSource() {
            super(Set.of("USDC", "BTC", "ETH"));
        }

        @Override
        public Optional<BigDecimal> usdPrice(String currency) {
            return Optional.ofNullable(usd.get(currency));
        }
    }

    private static final InstrumentInfo BTC_USDC_K = inst("BTC", "USDC", "0.00005", "0.00000001", "0.1");
    private static final InstrumentInfo ETH_USDC_K = inst("ETH", "USDC", "0.001", "0.00000001", "0.01");
    private static final InstrumentInfo PAXG_BTC = inst("PAXG", "BTC", "0.001", "0.00000001", "0.00001");
    private static final InstrumentInfo PAXG_ETH = inst("PAXG", "ETH", "0.001", "0.00000001", "0.0001");
    private static final InstrumentInfo PAXG_USD = inst("PAXG", "USD", "0.001", "0.00000001", "0.01");
    private static final List<InstrumentInfo> KRAKEN = List.of(BTC_USDC_K, ETH_USDC_K, PAXG_BTC, PAXG_ETH, PAXG_USD);

    private CryptoSource krakenSource() {
        return (CryptoSource) new CryptoSource()
                .with("BTC-USDC", market("0.08", "49999", "50001", "0"))
                .with("ETH-USDC", market("0.08", "2999.9", "3000.1", "0"))
                .with("PAXG-BTC", market("0.08", "0.07999", "0.08001", "0"))
                .with("PAXG-ETH", market("0.08", "1.3300", "1.3340", "0"));
    }

    private PathFinder.Request krakenRequest(Map<String, BigDecimal> sources, String amount, Set<String> displayOnly) {
        return new PathFinder.Request(KRAKEN, FIAT_AND_USDT, sources, "PAXG", d(amount), 2, displayOnly);
    }

    private static final Set<String> FIAT_AND_USDT = ExecutionDefaults.DEFAULT_FORBIDDEN_NODES;

    @Test
    @DisplayName("PAXG/Kraken : USDC>BTC>PAXG choisi (2 jambes), jambe 2 dimensionnée sur le reçu net de la jambe 1")
    void paxgViaBtc() {
        PathQuote quote = finder.find(krakenRequest(Map.of("USDC", d("1000")), "100", Set.of("ETH")), krakenSource());

        assertEquals(PathStatus.OK, quote.status());
        assertEquals("USDC>BTC>PAXG", quote.routeKey());
        assertEquals(List.of("BTC-USDC", "PAXG-BTC"), quote.legs().stream().map(PathLeg::instId).toList());
        PathLeg leg1 = quote.legs().get(0);
        PathLeg leg2 = quote.legs().get(1);
        assertPct("0.00199996", leg1.sz()); // ⌊100 / 50001⌋ au lot 1e-8
        // reçu net = sz × (1 - 0,08 %) ; sz2 = ⌊reçu / (ask × (1 + 0,08 %))⌋
        BigDecimal received = leg1.sz().multiply(d("0.9992"));
        BigDecimal expected = PathFinder.roundToStep(received.divide(d("0.08001").multiply(d("1.0008")), 18, RoundingMode.DOWN),
                d("0.00000001"), RoundingMode.DOWN);
        assertEquals(0, expected.compareTo(leg2.sz()), "jambe 2 = " + leg2.sz());
        assertFalse(leg2.belowMin());
        assertFalse(quote.underfunded());
        // somme brute : frais + demi-spread de chaque jambe
        assertEquals(0, leg1.costPct().add(leg2.costPct()).setScale(6, RoundingMode.HALF_UP).compareTo(quote.totalCostPct()));
    }

    @Test
    @DisplayName("Route ETH chiffrée dans routes mais jamais choisie, même moins chère ; route BTC listée avec avertissements")
    void ethDisplayedNeverChosen() {
        CryptoSource source = krakenSource();
        source.with("PAXG-BTC", market("0.5", "0.07999", "0.08001", "0")); // BTC volontairement plus chère

        PathQuote quote = finder.find(krakenRequest(Map.of("USDC", d("1000")), "100", Set.of("ETH")), source);

        assertEquals("USDC>BTC>PAXG", quote.routeKey());
        assertEquals(2, quote.routes().size());
        RouteQuote eth = quote.routes().stream().filter(r -> r.routeKey().equals("USDC>ETH>PAXG")).findFirst().orElseThrow();
        RouteQuote btc = quote.routes().stream().filter(r -> r.routeKey().equals("USDC>BTC>PAXG")).findFirst().orElseThrow();
        assertFalse(eth.chosen());
        assertTrue(btc.chosen());
        assertTrue(eth.totalCostPct().compareTo(btc.totalCostPct()) < 0, "ETH moins chère mais non choisie");
        assertTrue(btc.warnings().stream().anyMatch(w -> w.contains("Intermédiaire exposé")));
        assertTrue(btc.warnings().stream().anyMatch(w -> w.contains("PAXG-BTC")));
        // sans displayOnly, ETH moins chère serait choisie
        assertEquals("USDC>ETH>PAXG", finder.find(krakenRequest(Map.of("USDC", d("1000")), "100", Set.of()), source).routeKey());
    }

    @Test
    @DisplayName("Fiat (PAXG-USD) et USDT jamais dans un chemin ; notionnel converti en unités de la cotation (BTC)")
    void fiatIgnoredAndNotionalInQuoteUnits() {
        List<BigDecimal> notionals = new java.util.ArrayList<>();
        CryptoSource source = new CryptoSource() {
            @Override
            public Optional<LegMarket> market(InstrumentInfo instrument, LegSide side, BigDecimal notionalAmount) {
                notionals.add(notionalAmount);
                return super.market(instrument, side, notionalAmount);
            }
        };
        source.with("BTC-USDC", market("0.08", "49999", "50001", "0")).with("PAXG-BTC", market("0.08", "0.07999", "0.08001", "0"));

        PathQuote quote = finder.find(krakenRequest(Map.of("USDC", d("1000"), "USDT", d("1000")), "100", Set.of("ETH")), source);

        assertEquals("USDC>BTC>PAXG", quote.routeKey());
        assertEquals(0, d("100").compareTo(notionals.get(0)), "jambe 1 en USDC");
        assertTrue(notionals.get(1).compareTo(d("0.01")) < 0, "jambe 2 en BTC (~0,002), pas en dollars : " + notionals.get(1));
    }

    @Test
    @DisplayName("Jambe 2 sous le minimum sur le reçu net => BELOW_MIN ; coût toujours chiffré")
    void secondLegBelowMinOnNet() {
        // 10 $ ≈ 0,00019998 BTC ≈ 0,0025 PAXG < minSz 0,001 ? non : 0,0025 > 0,001 ; 3 $ => 0,00075 PAXG < 0,001
        PathQuote quote = finder.find(krakenRequest(Map.of("USDC", d("1000")), "3", Set.of("ETH")), krakenSource());

        assertEquals(PathStatus.BELOW_MIN, quote.status());
        assertTrue(quote.legs().getLast().belowMin());
        assertNotNullPct(quote.totalCostPct());
    }

    private static void assertNotNullPct(BigDecimal value) {
        assertTrue(value != null && value.signum() > 0);
    }

    @Test
    @DisplayName("Cache de marché par (instrument, côté, notionnel) : un candidat identique n'est lu qu'une fois")
    void marketCacheKeyedByNotional() {
        CryptoSource source = krakenSource();

        finder.find(krakenRequest(Map.of("USDC", d("1000")), "100", Set.of("ETH")), source);

        // BTC-USDC, ETH-USDC, PAXG-BTC, PAXG-ETH : 4 arêtes, chacune lue une fois pour ce montant
        assertEquals(4, source.marketCalls);
    }
}
