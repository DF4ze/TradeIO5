import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.market.dataset.Bucket;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Outil de calibration (hors code de production, cf. tools/calibration/) : reconstitue de vraies
 * bougies D1/W1 à partir d'un export H1 réel (fetch_real_klines.py) en réutilisant {@link Bucket}
 * (la même classe de production qui fait ce resampling pour l'app elle-même) plutôt que de
 * réimplémenter l'agrégation OHLCV en Python — demande explicite de Clem le 2026-07-09, cf.
 * docs/calibration-rejection-zone.md.
 * <p>
 * Lancement (single-file source launch, Java 21+) :
 * {@code java --class-path target/classes tools/calibration/BucketResample.java <h1.csv> <outDir>}
 */
public class BucketResample {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: BucketResample <h1.csv> <outDir>");
            System.exit(1);
        }
        String h1CsvPath = args[0];
        String outDir = args[1];

        List<MarketData> h1 = readCsv(h1CsvPath);
        System.out.println("Lu " + h1.size() + " bougies H1 depuis " + h1CsvPath);
        if (h1.isEmpty()) {
            System.err.println("Aucune bougie lue, abandon.");
            System.exit(1);
        }

        // maxSize confortable au-dessus du nombre réel de bougies : on veut TOUT garder, pas une
        // fenêtre glissante tronquée (contrairement à l'usage "live" normal de Bucket).
        int maxSize = h1.size() + 100;
        Bucket bucket = new Bucket(TimeFrame.H1, maxSize);
        for (MarketData candle : h1) {
            bucket.append(candle);
        }

        Instant now = h1.get(h1.size() - 1).getTimestamp().plusSeconds(3600);

        BucketView h4View = bucket.view(TimeFrame.H4, now);
        BucketView d1View = bucket.view(TimeFrame.D1, now);
        BucketView w1View = bucket.view(TimeFrame.W1, now);

        System.out.println("Vue H4 : " + h4View.size() + " bougies, complétude=" + h4View.completeness());
        System.out.println("Vue D1 : " + d1View.size() + " bougies, complétude=" + d1View.completeness());
        System.out.println("Vue W1 : " + w1View.size() + " bougies, complétude=" + w1View.completeness());

        writeCsv(outDir + "/btcusdt_h1.csv", h1);
        writeCsv(outDir + "/btcusdt_h4.csv", h4View.data());
        writeCsv(outDir + "/btcusdt_d1.csv", d1View.data());
        writeCsv(outDir + "/btcusdt_w1.csv", w1View.data());

        System.out.println("Ecrit dans " + outDir);
    }

    private static List<MarketData> readCsv(String path) throws Exception {
        List<MarketData> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String header = reader.readLine(); // timestamp,open,high,low,close,volume
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                Instant ts = OffsetDateTime.parse(parts[0]).toInstant();
                result.add(MarketData.builder()
                        .timeFrame(TimeFrame.H1)
                        .timestamp(ts)
                        .pair("BTCUSDT")
                        .open(new BigDecimal(parts[1]))
                        .high(new BigDecimal(parts[2]))
                        .low(new BigDecimal(parts[3]))
                        .close(new BigDecimal(parts[4]))
                        .volume(new BigDecimal(parts[5]))
                        .build());
            }
        }
        result.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
        return result;
    }

    private static void writeCsv(String path, List<MarketData> candles) throws Exception {
        try (PrintWriter writer = new PrintWriter(new FileWriter(path))) {
            writer.println("timestamp,open,high,low,close,volume");
            for (MarketData c : candles) {
                writer.println(c.getTimestamp() + "," + c.getOpen() + "," + c.getHigh() + ","
                        + c.getLow() + "," + c.getClose() + "," + c.getVolume());
            }
        }
    }
}
