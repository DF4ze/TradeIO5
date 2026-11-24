package fr.ses10doigts.tradeIO5.service.market.eligibility;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.AssetBlocklistRepository;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.InstrumentCatalog;
import fr.ses10doigts.tradeIO5.service.market.instrument.PathFinder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AssetEligibilityService : éligible ou raison de refus")
class AssetEligibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final FixedDomainClock clock = new FixedDomainClock(NOW);
    private final InstrumentCatalog catalog = mock(InstrumentCatalog.class);
    private final AssetBlocklistRepository blocklist = mock(AssetBlocklistRepository.class);
    private final AssetEligibilitySettingsService settings = mock(AssetEligibilitySettingsService.class);
    private final AtomicInteger probeCalls = new AtomicInteger();
    private OptionalInt probeAnswer = OptionalInt.of(AssetEligibilityDefaults.MIN_D1_DAYS);
    private AssetEligibilityService service;

    private final WebProvider kraken = WebProvider.builder().code(WebProviderCode.KRAKEN).build();
    private final List<ExchangeInstrument> rows = new ArrayList<>();

    private static ExchangeInstrument row(String base, String quote, String volume, Instant readAt) {
        return ExchangeInstrument.builder().provider(WebProviderCode.KRAKEN).instId(base + "-" + quote).base(base).quote(quote)
                .state(InstrumentCatalog.LIVE_STATE).minSz(ONE).lotSz(ONE).tickSz(ONE).fetchedAt(NOW)
                .volume24hQuote(volume == null ? null : new BigDecimal(volume)).volumeReadAt(readAt).build();
    }

    private static ExchangeInstrument row(String base, String quote, String volume) {
        return row(base, quote, volume, NOW.minus(Duration.ofHours(2)));
    }

    private void catalogOf(ExchangeInstrument... instruments) {
        rows.clear();
        rows.addAll(List.of(instruments));
        List<InstrumentInfo> live = rows.stream().map(i -> new InstrumentInfo(i.getInstId(), i.getBase(), i.getQuote(), ONE, ONE, ONE)).toList();
        when(catalog.liveInstruments(any())).thenReturn(live);
        when(catalog.allInstruments(any())).thenReturn(List.copyOf(rows));
    }

    @BeforeEach
    void setUp() {
        when(settings.minVolumeUsd()).thenReturn(AssetEligibilityDefaults.DEFAULT_MIN_VOLUME_USD);
        D1HistoryProbe probe = (provider, asset) -> {
            probeCalls.incrementAndGet();
            return probeAnswer;
        };
        service = new AssetEligibilityService(catalog, blocklist, settings, new PathFinder(null), probe, clock, "");
        catalogOf(row("BTC", "USDC", "50000000"), row("SOL", "USDC", "20000000"));
    }

    private AssetEligibilityService.Result check(String asset) {
        return service.check(asset, kraken);
    }

    @Test
    @DisplayName("Actif joignable en USDC, volume suffisant, 120 jours : éligible")
    void eligibleDirect() {
        AssetEligibilityService.Result r = check("SOL");

        assertTrue(r.eligible());
        assertNull(r.reason());
        assertTrue(r.volumeVerified());
        assertEquals(0, new BigDecimal("20000000").compareTo(r.volumeUsd()));
        assertEquals(AssetEligibilityDefaults.MIN_D1_DAYS, r.d1Days());
    }

    @Test
    @DisplayName("PAXG chez Kraken éligible via la passerelle BTC (USDC>BTC>PAXG), sans paire USDC directe")
    void paxgViaBtcGateway() {
        catalogOf(row("BTC", "USDC", "50000000"), row("PAXG", "BTC", null), row("PAXG", "USD", "3000000"));

        AssetEligibilityService.Result r = check("paxg");

        assertTrue(r.eligible());
        assertEquals("PAXG", r.asset());
        assertEquals(0, new BigDecimal("3000000").compareTo(r.volumeUsd()));
    }

    @Test
    @DisplayName("Fiat, stablecoin, levier, earn Kraken : refusés par nom, sans I/O (ni D1)")
    void refusedByName() {
        assertEquals(IneligibilityReason.FIAT, check("EUR").reason());
        assertEquals(IneligibilityReason.STABLECOIN, check("USDT").reason());
        assertEquals(IneligibilityReason.LEVERAGED_TOKEN, check("BTC3L").reason());
        assertEquals(IneligibilityReason.EARN_OR_WRAPPED, check("DOT.S").reason());
        assertEquals(IneligibilityReason.EARN_OR_WRAPPED, check("ETH2").reason());
        assertEquals(0, probeCalls.get());
    }

    @Test
    @DisplayName("Token earn Binance (LD…) exclu chez Binance, pas chez Kraken")
    void earnTokenExcludedPerProvider() {
        catalogOf(row("BTC", "USDC", "50000000"), row("LDBTC", "USDC", "20000000"));
        WebProvider binance = WebProvider.builder().code(WebProviderCode.BINANCE).build();

        assertEquals(IneligibilityReason.EARN_OR_WRAPPED, service.check("LDBTC", binance).reason());
        assertTrue(service.check("LDBTC", kraken).eligible());
    }

    @Test
    @DisplayName("Liste noire : refusé")
    void blocklisted() {
        when(blocklist.existsByAssetSymbol("SOL")).thenReturn(true);

        assertEquals(IneligibilityReason.BLOCKLISTED, check("SOL").reason());
        assertEquals(0, probeCalls.get());
    }

    @Test
    @DisplayName("Aucune route d'achat : cotation EUR seule, USDT seul, ou intermédiaire ETH (affichage seul)")
    void noBuyRoute() {
        catalogOf(row("BTC", "USDC", "50000000"), row("ADA", "EUR", "9000000"));
        assertEquals(IneligibilityReason.NO_BUY_ROUTE, check("ADA").reason());

        catalogOf(row("BTC", "USDC", "50000000"), row("ADA", "USDT", "9000000"));
        assertEquals(IneligibilityReason.NO_BUY_ROUTE, check("ADA").reason());

        catalogOf(row("ETH", "USDC", "50000000"), row("ADA", "ETH", "9000000"));
        assertEquals(IneligibilityReason.NO_BUY_ROUTE, check("ADA").reason());

        assertEquals(0, probeCalls.get());
    }

    @Test
    @DisplayName("Route à 3 jambes refusée (limite 2 jambes)")
    void tooManyLegs() {
        catalogOf(row("BTC", "USDC", "50000000"), row("XRP", "BTC", "9000000"), row("ADA", "XRP", "9000000"));

        assertEquals(IneligibilityReason.NO_BUY_ROUTE, check("ADA").reason());
    }

    @Test
    @DisplayName("Volume sous le seuil admin : refusé ; seuil relevé : un actif qui passait est refusé")
    void volumeBelowThreshold() {
        catalogOf(row("BTC", "USDC", "50000000"), row("DOGE", "USDC", "999999"));
        AssetEligibilityService.Result low = check("DOGE");
        assertEquals(IneligibilityReason.VOLUME_TOO_LOW, low.reason());
        assertTrue(low.volumeVerified());
        assertEquals(0, probeCalls.get());

        when(settings.minVolumeUsd()).thenReturn(new BigDecimal("30000000"));
        catalogOf(row("BTC", "USDC", "50000000"), row("SOL", "USDC", "20000000"));
        assertEquals(IneligibilityReason.VOLUME_TOO_LOW, check("SOL").reason());
    }

    @Test
    @DisplayName("Volume = somme des paires USD / USDC / USDT live ; une cotation BTC ou EUR n'est pas additionnée")
    void volumeSumsUsdQuotesOnly() {
        catalogOf(row("BTC", "USDC", "50000000"), row("DOT", "USDC", "600000"), row("DOT", "USDT", "600000"), row("DOT", "BTC", "99000000"));

        AssetEligibilityService.Result r = check("DOT");

        assertTrue(r.eligible());
        assertEquals(0, new BigDecimal("1200000").compareTo(r.volumeUsd()));
    }

    @Test
    @DisplayName("Volume inconnu (exchange sans volume) : éligible mais non vérifié")
    void unknownVolumeIsUnverifiedButEligible() {
        catalogOf(row("BTC", "USDC", null), row("SOL", "USDC", null, null));

        AssetEligibilityService.Result r = check("SOL");

        assertTrue(AssetEligibilityDefaults.UNKNOWN_VOLUME_ELIGIBLE);
        assertTrue(r.eligible());
        assertFalse(r.volumeVerified());
        assertNull(r.volumeUsd());
    }

    @Test
    @DisplayName("Volume lu depuis plus de 7 jours : traité comme inconnu (non vérifié), pas comme bon volume")
    void staleVolumeIsUnknown() {
        catalogOf(row("BTC", "USDC", "50000000"), row("SOL", "USDC", "5", NOW.minus(Duration.ofDays(8))));

        AssetEligibilityService.Result r = check("SOL");

        assertTrue(r.eligible());
        assertFalse(r.volumeVerified());
    }

    @Test
    @DisplayName("Moins de 120 jours de D1 chez le provider : refusé avec le nombre de jours")
    void historyTooShort() {
        probeAnswer = OptionalInt.of(119);

        AssetEligibilityService.Result r = check("SOL");

        assertEquals(IneligibilityReason.HISTORY_TOO_SHORT, r.reason());
        assertEquals(119, r.d1Days());
        assertEquals(1, probeCalls.get());
    }

    @Test
    @DisplayName("Lecture D1 impossible : HISTORY_UNAVAILABLE (à réessayer, ni éligible ni définitif)")
    void historyUnavailable() {
        probeAnswer = OptionalInt.empty();

        assertEquals(IneligibilityReason.HISTORY_UNAVAILABLE, check("SOL").reason());
    }

    @Test
    @DisplayName("Catalogue absent et exchange injoignable : CATALOG_UNAVAILABLE")
    void catalogUnavailable() {
        when(catalog.liveInstruments(any())).thenThrow(new InstrumentLookupException("injoignable"));

        assertEquals(IneligibilityReason.CATALOG_UNAVAILABLE, check("SOL").reason());
        assertEquals(0, probeCalls.get());
    }
}
