package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Bougies D1 de test : série synthétique (cycles haussiers/baissiers) ou CSV du dossier tools/rainbow-replay/data. */
final class SimulationFixtures {

    private SimulationFixtures() {
    }

    /** {@code count} bougies D1 UTC se terminant la veille de {@code today} (minuit UTC), prix oscillant en cycles de ~200 jours. */
    static List<MarketData> synthetic(int count, Instant today) {
        List<MarketData> out = new ArrayList<>();
        Instant first = today.truncatedTo(ChronoUnit.DAYS).minus(count, ChronoUnit.DAYS);
        for (int i = 0; i < count; i++) {
            double close = 100 + 40 * Math.sin(i / 32.0) + i * 0.05;
            out.add(candle(first.plus(i, ChronoUnit.DAYS), close - 1, close + 2, close - 2, close));
        }
        return out;
    }

    static List<MarketData> csv(String asset) throws IOException {
        List<MarketData> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("tools/rainbow-replay/data/d1_" + asset + ".csv"))) {
            String[] p = line.split(",");
            out.add(candle(Instant.ofEpochMilli(Long.parseLong(p[0])), Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]), Double.parseDouble(p[4])));
        }
        return out;
    }

    private static MarketData candle(Instant t, double o, double h, double l, double c) {
        return MarketData.builder().timeFrame(TimeFrame.D1).timestamp(t).pair("TESTUSDT").open(BigDecimal.valueOf(o))
                .high(BigDecimal.valueOf(h)).low(BigDecimal.valueOf(l)).close(BigDecimal.valueOf(c)).volume(BigDecimal.ZERO).build();
    }
}
