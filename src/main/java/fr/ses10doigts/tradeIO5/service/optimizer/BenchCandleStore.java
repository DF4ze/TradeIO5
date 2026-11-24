package fr.ses10doigts.tradeIO5.service.optimizer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Stockage DÉDIÉ au bench / à l'étude (étape D1a) des bougies D1 Binance en USDT : fichiers CSV + {@code manifest.json} sous
 * {@value #DEFAULT_DIR}, jamais la base, jamais les tables de bougies lues par le live ni le rejeu live. Chaque jeu porte son étiquette
 * source ({@value #SOURCE_LABEL}), vérifiée au chargement, et une empreinte (sha256 du CSV du manifeste + {@link CandleFingerprint}).
 * Classe réservée à l'optimiseur : aucune classe hors {@code service.optimizer} ne doit la référencer (testé).
 */
public final class BenchCandleStore {

    public static final String DEFAULT_DIR = "tools/rainbow-replay/data-bench";
    public static final String SOURCE_LABEL = "BINANCE/USDT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BenchCandleStore() {
    }

    /** Ligne du manifeste. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String asset, String symbol, String quote, String source, int rows, String firstDay, String lastDay, String csvSha256) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(String usage, List<Entry> assets) {
    }

    /** Bougies chargées + empreinte ({@link CandleFingerprint}) ; {@link #sourceLabel()} est l'étiquette posée à la récupération. */
    public record Bench(Entry entry, List<MarketData> candles, CandleFingerprint fingerprint) {
        public String sourceLabel() {
            return entry.source();
        }
    }

    public static List<Entry> manifest(Path dir) throws IOException {
        return MAPPER.readValue(Files.readString(dir.resolve("manifest.json")), Manifest.class).assets();
    }

    /** Charge un actif : refuse un CSV modifié (sha256), un nombre de lignes différent du manifeste ou une étiquette source absente / inattendue. */
    public static Bench load(Path dir, String asset) throws IOException {
        Entry e = manifest(dir).stream().filter(x -> x.asset().equalsIgnoreCase(asset)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Actif absent du manifeste bench : " + asset));
        if (!SOURCE_LABEL.equals(e.source())) {
            throw new IllegalStateException("Étiquette source bench inattendue pour " + asset + " : " + e.source());
        }
        String text = Files.readString(dir.resolve("d1_" + e.asset() + ".csv"), StandardCharsets.UTF_8);
        if (!sha256(text).equals(e.csvSha256())) {
            throw new IllegalStateException("Empreinte du CSV bench différente du manifeste : " + asset);
        }
        List<MarketData> candles = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split(",");
            candles.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochMilli(Long.parseLong(p[0]))).pair(e.symbol())
                    .open(new BigDecimal(p[1])).high(new BigDecimal(p[2])).low(new BigDecimal(p[3])).close(new BigDecimal(p[4]))
                    .volume(new BigDecimal(p[5])).build());
        }
        if (candles.size() != e.rows()) {
            throw new IllegalStateException("Nombre de bougies bench différent du manifeste : " + asset);
        }
        LocalDate last = LocalDate.parse(e.lastDay());
        return new Bench(e, List.copyOf(candles), CandleFingerprint.of(e.asset(), candles, last));
    }

    /**
     * Enregistre (ou remplace) les bougies D1 d'un actif : CSV {@code d1_<ACTIF>.csv} + ligne du manifeste (étiquette {@value #SOURCE_LABEL},
     * lignes, premier / dernier jour, sha256). Même format que {@code tools/bench-data/fetch_binance_d1.py}. Le dossier est créé au besoin.
     */
    public static synchronized Entry save(Path dir, String asset, String symbol, String quote, List<MarketData> candles) throws IOException {
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("Aucune bougie à enregistrer : " + asset);
        }
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (MarketData c : candles) {
            sb.append(c.getTimestamp().toEpochMilli()).append(',').append(c.getOpen().toPlainString()).append(',').append(c.getHigh().toPlainString())
                    .append(',').append(c.getLow().toPlainString()).append(',').append(c.getClose().toPlainString()).append(',')
                    .append(c.getVolume().toPlainString()).append('\n');
        }
        String text = sb.toString();
        Files.writeString(dir.resolve("d1_" + asset + ".csv"), text, StandardCharsets.UTF_8);
        LocalDate first = candles.getFirst().getTimestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        LocalDate last = candles.getLast().getTimestamp().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        Entry entry = new Entry(asset, symbol, quote, SOURCE_LABEL, candles.size(), first.toString(), last.toString(), sha256(text));
        List<Entry> all = new ArrayList<>();
        if (Files.exists(dir.resolve("manifest.json"))) {
            manifest(dir).stream().filter(e -> !e.asset().equalsIgnoreCase(asset)).forEach(all::add);
        }
        all.add(entry);
        all.sort(java.util.Comparator.comparing(Entry::asset));
        Files.writeString(dir.resolve("manifest.json"), MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(new Manifest("bench / etude uniquement : jamais l'execution ni le live", all)), StandardCharsets.UTF_8);
        return entry;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
