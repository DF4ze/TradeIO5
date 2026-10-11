package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import fr.ses10doigts.tradeIO5.service.market.FixedDomainClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KrakenSignedRequests : signature (vecteur publié par Kraken) et nonce strictement croissant par clé")
class KrakenSignedRequestsTest {

    /** Vecteur de test de la documentation Kraken (clé d'exemple publique). */
    private static final String DOC_SECRET = "kQH5HW/8p1uGOVjbgWA7FunAmGO8lsSUXNsu3eow76sz84Q18fWxnyRzBHCd3pd5nE9qa99HAZtuZuj6F1huXg==";

    @Test
    @DisplayName("Signature identique au vecteur de test de la documentation Kraken")
    void publishedVector() {
        String sign = KrakenSignedRequests.sign("/0/private/AddOrder", 1616492376594L,
                "nonce=1616492376594&ordertype=limit&pair=XBTUSD&price=37500&type=buy&volume=1.25", DOC_SECRET);
        assertEquals("4/dpxb3iT4tp/ZCVEwSnEsLxx0bqyhLpdfOpc6fn7OR8+UClSV5n9E6aSS8MPtnRfp32bAb0nmbRn6H8ndwLUQ==", sign);
    }

    @Test
    @DisplayName("Nonce strictement croissant sous concurrence (horloge figée), sans doublon")
    void strictlyIncreasingUnderConcurrency() throws Exception {
        FixedDomainClock clock = new FixedDomainClock(Instant.parse("2026-10-11T00:00:00Z"));
        int threads = 16;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Callable<List<Long>> task = () -> {
                List<Long> out = new ArrayList<>();
                for (int i = 0; i < perThread; i++) {
                    out.add(KrakenSignedRequests.nextNonce(clock, "concurrent-key"));
                }
                return out;
            };
            futures.add(pool.submit(task));
        }
        List<Long> all = new ArrayList<>();
        for (Future<List<Long>> f : futures) {
            List<Long> mine = f.get();
            for (int i = 1; i < mine.size(); i++) {
                assertTrue(mine.get(i) > mine.get(i - 1), "croissant dans un même thread");
            }
            all.addAll(mine);
        }
        pool.shutdown();
        Set<Long> distinct = all.stream().collect(Collectors.toSet());
        assertEquals(threads * perThread, distinct.size(), "aucun nonce en double");
    }

    @Test
    @DisplayName("Deux clés => deux séquences indépendantes ; horloge en avance respectée")
    void perKeySequences() {
        FixedDomainClock clock = new FixedDomainClock(Instant.ofEpochMilli(1_000_000L));
        assertEquals(1_000_000L, KrakenSignedRequests.nextNonce(clock, "key-A"));
        assertEquals(1_000_001L, KrakenSignedRequests.nextNonce(clock, "key-A"));
        assertEquals(1_000_000L, KrakenSignedRequests.nextNonce(clock, "key-B"));
        assertEquals(1_000_002L, KrakenSignedRequests.nextNonce(clock, "key-A"));
        assertEquals(5_000_000L, KrakenSignedRequests.nextNonce(new FixedDomainClock(Instant.ofEpochMilli(5_000_000L)), "key-A"));
    }
}
