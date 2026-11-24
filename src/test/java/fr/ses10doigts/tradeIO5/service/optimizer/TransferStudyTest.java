package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrPresets;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.BlockDelta;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.PairResult;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.Source;
import fr.ses10doigts.tradeIO5.service.optimizer.TransferStudy.Target;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Étape D1a : étiquette source posée et propagée, bougies bench jamais lues par le live, paire dans l'échantillon exclue du verdict,
 * actif court = provisoire sans blocage, règle 3 sans donnée de test, reproductibilité (série contre pool).
 */
class TransferStudyTest {

    private static final Path BENCH = Path.of(BenchCandleStore.DEFAULT_DIR);
    /** Petite configuration : 2 blocs, 3 décalages (seuils de décision identiques à la configuration réelle). */
    private static final TransferConfig CFG = new TransferConfig(2484.0, 2, 0.6, 0.7, 0.6, 5, 14, 24, 2, 3, 60, 25.0, 10.0);

    private static BenchCandleStore.Bench bnb;
    private static Target bnbTarget;
    private static PairResult first;
    private static PairResult second;

    private static Source source(String asset) {
        return new Source(asset, RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]), 6, 0.0005);
    }

    @BeforeAll
    static void load() throws IOException {
        bnb = BenchCandleStore.load(BENCH, "BNB");
        List<MarketData> recent = bnb.candles().subList(bnb.candles().size() - 1_300, bnb.candles().size());
        bnbTarget = new Target("BNB", bnb.sourceLabel(), recent);
        first = TransferStudy.pair(bnbTarget, source("BTC"), CFG, PresetOptimizerTestSupport.LIMITS);
        second = TransferStudy.pair(bnbTarget, source("BTC"), CFG, PresetOptimizerTestSupport.LIMITS);
    }

    // ------------------------------------------------------------------ étiquette source

    @Test
    @DisplayName("L'étiquette source est posée au stockage (manifeste, 9 actifs) et propagée jusqu'à la paire et au rapport")
    void sourceLabelIsSetAndPropagated() throws IOException {
        List<BenchCandleStore.Entry> all = BenchCandleStore.manifest(BENCH);
        assertEquals(9, all.size());
        assertTrue(all.stream().allMatch(e -> BenchCandleStore.SOURCE_LABEL.equals(e.source()) && "USDT".equals(e.quote())));
        assertEquals(BenchCandleStore.SOURCE_LABEL, bnb.sourceLabel());
        assertNotNull(bnb.fingerprint().checksum());
        assertEquals(bnb.entry().rows(), bnb.fingerprint().count());
        assertEquals(BenchCandleStore.SOURCE_LABEL, first.sourceLabel());
        assertNotNull(first.fingerprint());
        TransferReport report = new TransferReport(TransferReport.DEFINITION, CFG,
                List.of(new TransferReport.AssetInfo("BNB", "NOUVEAU", bnb.sourceLabel(), first.fingerprint(), 3.0)), List.of(first),
                TransferStudy.verdict(List.of(first), Map.of("BNB", 3.0, "BTC", 2.0, "ETH", 3.0, "PAXG", 1.0), CFG), List.of(), List.of(), null);
        assertTrue(report.toJson().contains(BenchCandleStore.SOURCE_LABEL));
        assertTrue(report.toMarkdown().contains(BenchCandleStore.SOURCE_LABEL));
    }

    @Test
    @DisplayName("Un CSV bench modifié est refusé (empreinte du manifeste)")
    void tamperedCsvIsRejected() throws IOException {
        Path tmp = Files.createTempDirectory("bench");
        Files.copy(BENCH.resolve("manifest.json"), tmp.resolve("manifest.json"));
        Files.writeString(tmp.resolve("d1_BNB.csv"), Files.readString(BENCH.resolve("d1_BNB.csv")).replaceFirst("\\d", "9"));
        Files.writeString(tmp.resolve("d1_SOL.csv"), "");
        boolean refused = false;
        try {
            BenchCandleStore.load(tmp, "BNB");
        } catch (IllegalStateException e) {
            refused = true;
        }
        assertTrue(refused);
    }

    // ------------------------------------------------------------------ jamais lu par le live

    @Test
    @DisplayName("Les bougies bench ne sont jamais lues par le live : aucune classe hors service.optimizer ne référence le stockage bench")
    void benchIsNeverReadByTheLive() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String norm = f.toString().replace('\\', '/');
                if (norm.contains("/service/optimizer/")) {
                    continue;
                }
                String text = Files.readString(f);
                if (text.contains("BenchCandleStore") || text.contains("data-bench")) {
                    offenders.add(norm);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "références au stockage bench hors optimiseur : " + offenders);
    }

    // ------------------------------------------------------------------ paire dans l'échantillon exclue du verdict

    private static PairResult pr(String target, String preset, boolean inSample, String status, double med) {
        return new PairResult(target, preset, inSample, "X", null, status, 4, 3, med, 0.5, null, List.of(new BlockDelta(0, "b", true, med, 0, med, 0.5, true, 10.0)),
                null, 0, "");
    }

    @Test
    @DisplayName("Une paire dans l'échantillon (preset réglé pour cet actif) est rapportée mais exclue du verdict")
    void inSamplePairIsExcludedFromTheVerdict() {
        List<String> news = List.of("BNB", "SOL", "XRP", "ADA", "LINK", "LTC", "AVAX", "DOT", "DOGE");
        List<PairResult> pairs = new ArrayList<>();
        Map<String, Double> atr = new LinkedHashMap<>();
        atr.put("BTC", 3.0);
        atr.put("ETH", 4.0);
        atr.put("PAXG", 1.0);
        for (String a : news) {
            atr.put(a, 3.0);
        }
        for (int i = 0; i < news.size(); i++) {
            pairs.add(pr(news.get(i), "BTC", false, i < 6 ? TransferStudy.TRANSFERE : TransferStudy.NON_TRANSFERE, i < 6 ? 2.0 : -1.0));
            pairs.add(pr(news.get(i), "ETH", false, TransferStudy.NON_TRANSFERE, -1.0));
            pairs.add(pr(news.get(i), "PAXG", false, TransferStudy.NON_TRANSFERE, -1.0));
        }
        pairs.add(pr("BTC", "BTC", true, TransferStudy.TRANSFERE, 9.0));
        pairs.add(pr("BTC", "ETH", false, TransferStudy.NON_TRANSFERE, -1.0));
        pairs.add(pr("BTC", "PAXG", false, TransferStudy.NON_TRANSFERE, -1.0));
        TransferStudy.Verdict v = TransferStudy.verdict(pairs, atr, CFG);
        // hors échantillon : 6 TRANSFERE sur 9 pour BTC = 66,7 % < 70 % ; compter la paire BTC × BTC donnerait 7/10 = 70 % et renverserait le verdict
        assertEquals(6.0 / 9.0, v.genericShareByPreset().get("BTC"), 1e-9);
        assertTrue(v.genericPresets().isEmpty());
        assertNotEquals("PRESET_GENERIQUE", v.verdict());
        assertEquals(10, v.concludedAssets(), "9 nouveaux + BTC (paires ETH / PAXG hors échantillon) = 10 conclus");
    }

    // ------------------------------------------------------------------ règle TRANSFERE et actif court

    private static List<BlockDelta> blocks(double... deltas) {
        List<BlockDelta> out = new ArrayList<>();
        for (int i = 0; i < deltas.length; i++) {
            out.add(new BlockDelta(i, "b" + i, true, deltas[i], 0, deltas[i], 1.0, true, 10.0));
        }
        return out;
    }

    @Test
    @DisplayName("Règle TRANSFERE : ≥ 60 % des blocs, Δ médian > 0 et au-dessus de la bande de bruit")
    void transfereRule() {
        assertEquals(TransferStudy.TRANSFERE, TransferStudy.evaluate("X", "BTC", false, "L", null, blocks(4, 3, 2, -1), null, 0, CFG).status());
        assertEquals(TransferStudy.NON_TRANSFERE, TransferStudy.evaluate("X", "BTC", false, "L", null, blocks(4, -3, 2, -5), null, 0, CFG).status(), "50 % de blocs");
        assertEquals(TransferStudy.NON_TRANSFERE, TransferStudy.evaluate("X", "BTC", false, "L", null, blocks(0.9, 0.8, 0.7), null, 0, CFG).status(),
                "Δ médian 0,8 sous la bande de 1,0");
        assertEquals(TransferStudy.NON_TRANSFERE, TransferStudy.evaluate("X", "BTC", false, "L", null, blocks(3, -1), null, 0, CFG).status(), "1 bloc sur 2 = 50 %");
    }

    @Test
    @DisplayName("Actif avec 1 seul bloc ou moins de 24 mois d'historique : provisoire (signe rapporté), jamais exclu ni bloquant, hors verdict")
    void shortHistoryIsProvisional() {
        PairResult one = TransferStudy.evaluate("X", "BTC", false, "L", null, blocks(2.5), null, 0, CFG);
        assertEquals(TransferStudy.PROVISOIRE, one.status());
        assertEquals(1, one.provisionalSign());
        // 600 jours réels : aucun bloc de 24 mois, l'étude s'exécute sans exception
        List<MarketData> shortHistory = bnb.candles().subList(0, 600);
        PairResult p = TransferStudy.pair(new Target("BNB", bnb.sourceLabel(), shortHistory), source("BTC"), CFG, PresetOptimizerTestSupport.LIMITS);
        assertTrue(p.validBlocks() < CFG.minBlocks());
        assertTrue(TransferStudy.PROVISOIRE.equals(p.status()) || TransferStudy.NON_CONCLU.equals(p.status()));
        TransferStudy.Verdict v = TransferStudy.verdict(List.of(one, p), Map.of("BTC", 3.0, "ETH", 4.0, "PAXG", 1.0, "X", 3.0, "BNB", 3.0), CFG);
        assertEquals(0, v.concludedAssets());
        assertEquals("NON_CONCLUANT", v.verdict());
    }

    // ------------------------------------------------------------------ règle 3 sans donnée de test

    @Test
    @DisplayName("Règle 3 : le choix ne dépend que des volatilités, jamais des résultats de test")
    void volatilityChoiceUsesNoTestData() {
        Map<String, Double> src = Map.of("BTC", 3.0, "ETH", 4.5, "PAXG", 1.0);
        assertEquals("ETH", TransferStudy.chooseByVolatility(4.0, src));
        assertEquals("PAXG", TransferStudy.chooseByVolatility(1.3, src));
        assertEquals("BTC", TransferStudy.chooseByVolatility(3.2, src));
        Map<String, Double> atr = new LinkedHashMap<>(src);
        atr.put("A", 4.0);
        List<PairResult> good = new ArrayList<>();
        List<PairResult> bad = new ArrayList<>();
        for (String a : List.of("A", "B", "C", "D", "E")) {
            atr.put(a, 4.0);
            for (String p : TransferStudy.PRESET_ASSETS) {
                good.add(pr(a, p, false, TransferStudy.TRANSFERE, 5.0));
                bad.add(pr(a, p, false, TransferStudy.NON_TRANSFERE, -5.0));
            }
        }
        assertEquals(TransferStudy.verdict(good, atr, CFG).volumeChoice(), TransferStudy.verdict(bad, atr, CFG).volumeChoice());
        assertTrue(TransferStudy.verdict(good, atr, CFG).volumeChoice().values().stream().allMatch("ETH"::equals));
    }

    // ------------------------------------------------------------------ reproductibilité

    @Test
    @DisplayName("Reproductibilité : deux runs identiques, et la série redonne exactement le pool")
    void reproducible() {
        assertEquals(first, second);
        assertFalse(first.blocks().isEmpty());
        List<Source> sources = List.of(source("BTC"), source("ETH"));
        List<MarketData> small = bnbTarget.candles().subList(bnbTarget.candles().size() - 1_000, bnbTarget.candles().size());
        Target t = new Target("BNB", bnb.sourceLabel(), small);
        List<PairResult> serial = TransferStudy.runAll(List.of(t), sources, CFG, PresetOptimizerTestSupport.LIMITS, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            assertEquals(serial, TransferStudy.runAll(List.of(t), sources, CFG, PresetOptimizerTestSupport.LIMITS, pool));
        } finally {
            pool.shutdown();
        }
    }
}
