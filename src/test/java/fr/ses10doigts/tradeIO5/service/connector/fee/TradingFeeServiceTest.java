package fr.ses10doigts.tradeIO5.service.connector.fee;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.TradingFeeCache;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.TradingFeeCacheRepository;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TradingFeeService : cache 24 h persisté, strict / estimation, single-flight, clés par compte")
class TradingFeeServiceTest {

    private final TradingFeeProvider fetcher = mock(TradingFeeProvider.class);
    private final TradingFeeCacheRepository repo = mock(TradingFeeCacheRepository.class);
    private final List<TradingFeeCache> saved = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private Instant now = Instant.parse("2026-11-15T00:00:00Z");
    private final DomainClock clock = () -> now;
    private TradingFeeService service;

    @BeforeEach
    void setUp() {
        when(fetcher.getProviderCode()).thenReturn(WebProviderCode.KRAKEN);
        when(fetcher.getFeeRate(any(), anyString())).thenAnswer(i -> {
            calls.incrementAndGet();
            return new FeeRate(new BigDecimal("0.25"), new BigDecimal("0.4"));
        });
        when(repo.save(any())).thenAnswer(i -> {
            saved.add(i.getArgument(0));
            return i.getArgument(0);
        });
        service = new TradingFeeService(List.of(fetcher), repo, clock);
    }

    private static ApiCredential cred(Long id) {
        return ApiCredential.builder().id(id).apiKey("SECRET-KEY").secretKey("SECRET").build();
    }

    @Test
    @DisplayName("Fournit, réutilise < 24 h, rafraîchit après 24 h")
    void ttl() {
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        assertEquals(1, calls.get());
        now = now.plus(Duration.ofHours(23));
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        assertEquals(1, calls.get());
        now = now.plus(Duration.ofHours(2));
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("Clés distinctes par compte et par instrument ; palier de compte : instrument ignoré")
    void keys() {
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        service.getFeeRate(WebProviderCode.KRAKEN, cred(2L), "BTC-USDC");
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "PAXG-USDC");
        assertEquals(3, calls.get());

        TradingFeeProvider wide = mock(TradingFeeProvider.class);
        when(wide.getProviderCode()).thenReturn(WebProviderCode.REVOLUT_X);
        when(wide.accountWide()).thenReturn(true);
        when(wide.getFeeRate(any(), anyString())).thenReturn(new FeeRate(BigDecimal.ZERO, new BigDecimal("0.09")));
        TradingFeeService s = new TradingFeeService(List.of(wide), repo, clock);
        s.getFeeRate(WebProviderCode.REVOLUT_X, cred(5L), "BTC-USDC");
        s.getFeeRate(WebProviderCode.REVOLUT_X, cred(5L), "ETH-USDC");
        verify(wide, times(1)).getFeeRate(any(), anyString());
        s.getFeeRate(WebProviderCode.REVOLUT_X, cred(6L), "BTC-USDC");
        verify(wide, times(2)).getFeeRate(any(), anyString());
    }

    @Test
    @DisplayName("Persisté en base sans secret ; credential sans id : non persisté")
    void persistence() {
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        service.getFeeRate(WebProviderCode.KRAKEN, cred(null), "BTC-USDC");
        assertEquals(1, saved.size());
        TradingFeeCache row = saved.getFirst();
        assertEquals(1L, row.getCredentialId());
        assertEquals("BTC-USDC", row.getInstKey());
        assertFalse(row.toString().contains("SECRET"));
    }

    @Test
    @DisplayName("Survit au redémarrage : lu en base, aucun appel réseau")
    void readsFromDb() {
        TradingFeeCache row = TradingFeeCache.builder().credentialId(1L).instKey("BTC-USDC").makerPct(new BigDecimal("0.1"))
                .takerPct(new BigDecimal("0.2")).fetchedAt(now.minus(Duration.ofHours(3))).build();
        when(repo.findByCredentialIdAndInstKey(1L, "BTC-USDC")).thenReturn(Optional.of(row));
        FeeRate r = service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        assertEquals(0, new BigDecimal("0.2").compareTo(r.takerPct()));
        assertEquals(0, calls.get());
    }

    @Test
    @DisplayName("Échec non caché ; strict : valeur périmée + échec => exception ; estimation : valeur périmée signalée")
    void staleAndFailure() {
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        now = now.plus(Duration.ofHours(30));
        org.mockito.Mockito.doThrow(new BalanceUnavailableException("down")).when(fetcher).getFeeRate(any(), anyString());
        assertThrows(BalanceUnavailableException.class, () -> service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC"));
        TradingFeeService.Lookup l = service.getFeeRateLenient(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        assertTrue(l.stale());
        assertEquals(0, new BigDecimal("0.4").compareTo(l.rate().takerPct()));
        // reprise : l'échec n'a rien caché
        org.mockito.Mockito.doReturn(new FeeRate(BigDecimal.ONE, BigDecimal.ONE)).when(fetcher).getFeeRate(any(), anyString());
        assertEquals(0, BigDecimal.ONE.compareTo(service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC").takerPct()));
    }

    @Test
    @DisplayName("Sans valeur en cache : l'échec remonte aussi en estimation ; clé rejetée jamais remplacée par du périmé")
    void noStaleFallback() {
        org.mockito.Mockito.doThrow(new BalanceUnavailableException("down")).when(fetcher).getFeeRate(any(), anyString());
        assertThrows(BalanceUnavailableException.class, () -> service.getFeeRateLenient(WebProviderCode.KRAKEN, cred(1L), "X-USDC"));
        org.mockito.Mockito.doReturn(new FeeRate(BigDecimal.ONE, BigDecimal.ONE)).when(fetcher).getFeeRate(any(), anyString());
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        now = now.plus(Duration.ofHours(30));
        org.mockito.Mockito.doThrow(new CredentialRejectedException("rejetée")).when(fetcher).getFeeRate(any(), anyString());
        assertThrows(CredentialRejectedException.class, () -> service.getFeeRateLenient(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC"));
    }

    @Test
    @DisplayName("Single-flight : N demandes simultanées identiques => un seul appel réseau")
    void singleFlight() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        calls.set(0);
        org.mockito.Mockito.doAnswer(i -> {
            calls.incrementAndGet();
            release.await();
            return new FeeRate(BigDecimal.ONE, BigDecimal.ONE);
        }).when(fetcher).getFeeRate(any(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<FeeRate>> fs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            fs.add(pool.submit(() -> service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC")));
        }
        Thread.sleep(200);
        release.countDown();
        for (var f : fs) {
            f.get();
        }
        pool.shutdown();
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("warm : seulement les entrées manquantes ; exchange sans récupération de frais => vue nulle")
    void warmAndUnsupported() {
        service.getFeeRate(WebProviderCode.KRAKEN, cred(1L), "BTC-USDC");
        service.warm(WebProviderCode.KRAKEN, cred(1L), List.of("BTC-USDC", "PAXG-USDC"));
        assertEquals(2, calls.get());
        assertEquals(null, service.forProvider(WebProviderCode.OKX));
        verify(repo, never()).findByCredentialIdAndInstKey(anyLong(), org.mockito.ArgumentMatchers.eq("*"));
    }
}
