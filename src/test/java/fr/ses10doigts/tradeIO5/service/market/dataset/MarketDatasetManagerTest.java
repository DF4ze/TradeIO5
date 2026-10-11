package fr.ses10doigts.tradeIO5.service.market.dataset;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Market Dataset - Manager.merge / fillGap")
class MarketDatasetManagerTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private final MarketDatasetManager manager = new MarketDatasetManager();
    private final MarketDatasetState state = new MarketDatasetState("BTCUSDT", 10_000);

    @Test
    @DisplayName("Un refetch qui chevauche l'existant n'enregistre aucun faux trou")
    void overlappingRefetchRegistersNoGap() {
        manager.merge(state, hours(0, 99), NOW);
        manager.merge(state, hours(50, 120), NOW.plusSeconds(3600));

        assertTrue(state.getHasDataGap().isEmpty());
        assertEquals(121, state.getBucket().size());
        assertEquals(NOW.plusSeconds(3600), state.getLastUpdate());
    }

    @Test
    @DisplayName("Un vrai saut vers l'avant est enregistré (clé = première bougie après le trou, valeur = bougies manquantes)")
    void forwardJumpRegistersRealGap() {
        manager.merge(state, hours(0, 9), NOW);
        manager.merge(state, hours(15, 20), NOW);

        assertEquals(1, state.getHasDataGap().size());
        assertEquals(5, state.getHasDataGap().get(ts(15)));
    }

    @Test
    @DisplayName("Une requête plus profonde complète l'historique plus ancien du Bucket")
    void deeperFetchExtendsHistory() {
        manager.merge(state, hours(50, 99), NOW);
        manager.merge(state, hours(0, 99), NOW);

        assertEquals(100, state.getBucket().size());
        assertEquals(ts(0), state.getBucket().peekFirst().getTimestamp());
        assertTrue(state.getHasDataGap().isEmpty());
    }

    @Test
    @DisplayName("fillGap comble le trou sans repousser lastUpdate")
    void fillGapKeepsLastUpdate() {
        manager.merge(state, hours(0, 9), NOW);
        manager.merge(state, hours(15, 20), NOW);

        manager.fillGap(state, hours(10, 15));

        assertEquals(21, state.getBucket().size());
        assertEquals(NOW, state.getLastUpdate());
    }

    private static List<MarketData> hours(long fromHour, long toHour) {
        List<MarketData> list = new ArrayList<>();
        for (long h = fromHour; h <= toHour; h++) {
            list.add(MarketData.builder()
                    .pair("BTCUSDT").timeFrame(TimeFrame.H1).timestamp(ts(h))
                    .open(BigDecimal.ONE).high(BigDecimal.ONE).low(BigDecimal.ONE)
                    .close(BigDecimal.ONE).volume(BigDecimal.ONE)
                    .build());
        }
        return list;
    }

    private static Instant ts(long hoursFromEpoch) {
        return Instant.ofEpochSecond(hoursFromEpoch * 3600);
    }
}
