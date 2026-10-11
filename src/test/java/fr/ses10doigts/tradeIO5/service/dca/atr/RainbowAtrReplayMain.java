package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay.DayRow;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendAnalyzer;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendState;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Rejeu Java du Rainbow ATR sur une plage (Bull/Bear du pine v4, Trend → jeu) pour comparaison à l'œil avec le pine
 * sous TradingView. Entrée : {@code <dir>/d1_<ACTIF>.csv} ({@code t_ms,open,high,low,close}, D1 UTC, tout
 * l'historique : l'ATH et l'automate moon l'exigent). Sorties dans {@code <dir>} : un CSV jour par jour par
 * (actif, mode) et {@code replay.html} (page autonome : données et lightweight-charts embarqués).
 * Usage : {@code RainbowAtrReplayMain <dir> [début=2024-06-10] [fin=dernière bougie]}, à lancer depuis la racine
 * du repo (gabarit {@code src/test/resources/rainbow-replay/replay-template.html}).
 * <p>
 * Modes : preset fixe Bear / Bull (comparaison propre au pine) ; Trend avec RANGE = garder le jeu précédent /
 * → Bear / → Bull ; Trend Mix (équivalent Java de {@code tools/pine/rainbow_trend_dca_v1.pine}, RANGE : garder).
 */
public final class RainbowAtrReplayMain {

    private static final String[] ASSETS = {"BTC", "ETH", "PAXG"};
    private static final String[] MODE_IDS = {"FIXED_BEAR", "FIXED_BULL", "TREND_KEEP", "TREND_LARGE", "TREND_SLOW", "TREND_SMA", "TREND_MIX"};
    private static final String[] MODE_LABELS = {
            "Preset fixe Bear (pine)", "Preset fixe Bull (pine)",
            "Trend actuel 7/14/30 (RANGE : garder)",
            "Trend large 14/30/60, seuil 0.30, confirm 7j",
            "Trend lent 30/60/120",
            "Prix vs SMA100 ±6 %",
            "Trend Mix (régression 14/30/60 + SMA100±1,25×ATR5) = méga-pine"};

    /** Colonnes exportées (nom → extracteur), dans l'ordre du CSV. */
    private static final Map<String, Function<DayRow, Object>> COLS = new LinkedHashMap<>();

    static {
        COLS.put("o", DayRow::open);
        COLS.put("h", DayRow::high);
        COLS.put("l", DayRow::low);
        COLS.put("c", DayRow::close);
        COLS.put("set", DayRow::set);
        COLS.put("sma", r -> r.band().sma());
        COLS.put("atr", r -> r.band().atr());
        COLS.put("down2", r -> r.band().down2());
        COLS.put("down1", r -> r.band().down1());
        COLS.put("up1", r -> r.band().up1());
        COLS.put("up2", r -> r.band().up2());
        COLS.put("up3", r -> r.band().up3());
        COLS.put("zone", r -> r.band().zone());
        COLS.put("ath", DayRow::ath);
        COLS.put("athDist", DayRow::athDistance);
        COLS.put("buyFac", DayRow::buyAthFactor);
        COLS.put("sellFac", DayRow::sellAthFactor);
        COLS.put("moon", r -> r.moonMode() ? 1 : 0);
        COLS.put("moonEntry", r -> r.moonEntry() ? 1 : 0);
        COLS.put("buyKind", r -> r.buyKind().ordinal());
        COLS.put("multZone", DayRow::multZone);
        COLS.put("buyAmt", DayRow::buyAmount);
        COLS.put("buyQty", DayRow::buyQuantity);
        COLS.put("sellKind", r -> r.sellKind().ordinal());
        COLS.put("sellFrac", DayRow::sellFraction);
        COLS.put("sellQty", DayRow::sellQuantity);
        COLS.put("sellProc", DayRow::sellProceeds);
        COLS.put("buyArmNow", r -> r.buyArmedNow() ? 1 : 0);
        COLS.put("sellArmNow", r -> r.sellArmedNow() ? 1 : 0);
        COLS.put("buyArmed", r -> r.buyArmed() ? 1 : 0);
        COLS.put("sellArmed", r -> r.sellArmed() ? 1 : 0);
        COLS.put("locked", r -> r.buyLocked() ? 1 : 0);
        COLS.put("cooldown", DayRow::cooldown);
        COLS.put("reserve", DayRow::reserveQty);
        COLS.put("pos", DayRow::position);
        COLS.put("cost", DayRow::costBasis);
        COLS.put("invested", DayRow::invested);
        COLS.put("proceeds", DayRow::saleProceeds);
        COLS.put("realized", DayRow::realizedGain);
        COLS.put("potential", DayRow::potentialGain);
        COLS.put("total", DayRow::totalGain);
        COLS.put("fixedGain", DayRow::fixedGain);
    }

    private RainbowAtrReplayMain() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "target/rainbow-replay");
        LocalDate start = LocalDate.parse(args.length > 1 ? args[1] : "2024-06-10");
        LocalDate end = args.length > 2 ? LocalDate.parse(args[2]) : null;

        StringBuilder json = new StringBuilder("{\"start\":\"" + start + "\",\"assets\":{");
        boolean firstAsset = true;
        for (String asset : ASSETS) {
            List<MarketData> candles = load(dir.resolve("d1_" + asset + ".csv"), asset);
            int n = candles.size();
            RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(candles);
            int startIdx = firstIndexOnOrAfter(candles, start);
            int endIdx = end == null ? n - 1 : lastIndexOnOrBefore(candles, end);

            TrendRegime[] regime = trendRegimes(candles);
            RainbowAtrParamSet[] sets = RainbowAtrPresets.bearBull(asset).toArray(new RainbowAtrParamSet[0]);

            json.append(firstAsset ? "" : ",").append('"').append(asset).append("\":{\"sets\":[");
            for (int s = 0; s < sets.length; s++) {
                json.append(s > 0 ? "," : "").append(setJson(sets[s]));
            }
            json.append("],\"modes\":[");
            double[] closes = candles.stream().mapToDouble(c -> c.getClose().doubleValue()).toArray();
            for (int m = 0; m < MODE_IDS.length; m++) {
                TrendRegime[] modeRegime = switch (m) {
                    case 3 -> RainbowAtrTrendBenchMain.regression(new int[]{14, 30, 60}, 400, 0.30, 0, 7).apply(closes);
                    case 4 -> RainbowAtrTrendBenchMain.regression(new int[]{30, 60, 120}, 400, 1.0 / 6, 0, 1).apply(closes);
                    case 5 -> RainbowAtrTrendBenchMain.priceMa(100, 0.06).apply(closes);
                    case 6 -> {
                        TrendMixCalculator.Params mp = TrendMixCalculator.Params.defaults();
                        yield TrendMixCalculator.compute(candles, ds.sma(mp.smaPeriod()), ds.atr(mp.atrPeriod()), mp).regime();
                    }
                    default -> regime;
                };
                int[] setOfBar = switch (m) {
                    case 0 -> RainbowSetSelector.fixed(n, RainbowAtrPresets.BEAR);
                    case 1 -> RainbowSetSelector.fixed(n, RainbowAtrPresets.BULL);
                    default -> RainbowSetSelector.select(modeRegime, RangeMapping.KEEP_PREVIOUS);
                };
                List<DayRow> rows = RainbowAtrReplay.run(ds, sets, setOfBar, startIdx, endIdx);
                writeCsv(dir.resolve("replay_" + asset + "_" + MODE_IDS[m] + ".csv"), rows, modeRegime);
                json.append(m > 0 ? "," : "").append(modeJson(MODE_IDS[m], MODE_LABELS[m], rows, modeRegime));
                DayRow last = rows.getLast();
                System.out.printf(Locale.ROOT, "%s %-11s jours=%d investi=%.2f réalisé=%.2f potentiel=%.2f total=%.2f DCAfixe=%.2f%n",
                        asset, MODE_IDS[m], rows.size(), last.invested(), last.realizedGain(), last.potentialGain(),
                        last.totalGain(), last.fixedGain());
            }
            json.append("]}");
            firstAsset = false;
        }
        json.append("}}");

        String template = Files.readString(Path.of("src/test/resources/rainbow-replay/replay-template.html"));
        if (!template.contains("/*__DATA__*/")) {
            throw new IllegalStateException("gabarit sans marqueur /*__DATA__*/");
        }
        // lightweight-charts (Apache-2.0) embarqué : la page n'a besoin d'aucun accès réseau
        String lib = Files.readString(Path.of("src/test/resources/rainbow-replay/lightweight-charts.standalone.production.js"));
        Files.writeString(dir.resolve("replay.html"),
                template.replace("/*__LWC__*/", lib).replace("/*__DATA__*/null", json.toString()));
        System.out.println("OK -> " + dir.resolve("replay.html"));
    }

    // ---------------------------------------------------------------- données

    private static List<MarketData> load(Path csv, String asset) throws IOException {
        List<MarketData> out = new ArrayList<>();
        for (String line : Files.readAllLines(csv)) {
            String[] p = line.split(",");
            out.add(MarketData.builder().timeFrame(TimeFrame.D1).timestamp(Instant.ofEpochMilli(Long.parseLong(p[0])))
                    .pair(asset + "USDT").open(new BigDecimal(p[1])).high(new BigDecimal(p[2])).low(new BigDecimal(p[3]))
                    .close(new BigDecimal(p[4])).volume(BigDecimal.ZERO).build());
        }
        return out;
    }

    private static LocalDate day(MarketData m) {
        return m.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static int firstIndexOnOrAfter(List<MarketData> c, LocalDate d) {
        for (int i = 0; i < c.size(); i++) {
            if (!day(c.get(i)).isBefore(d)) { return i; }
        }
        throw new IllegalArgumentException("aucune bougie à partir de " + d);
    }

    private static int lastIndexOnOrBefore(List<MarketData> c, LocalDate d) {
        for (int i = c.size() - 1; i >= 0; i--) {
            if (!day(c.get(i)).isAfter(d)) { return i; }
        }
        throw new IllegalArgumentException("aucune bougie avant " + d);
    }

    /** Trend de production (régression multi-fenêtres + hystérésis), causale, réglages par défaut ; null avant calcul. */
    private static TrendRegime[] trendRegimes(List<MarketData> candles) {
        List<TrendState> states = new TrendAnalyzer().analyzeTimeline(candles,
                RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR, RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM, RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG,
                RegressiveTrendStrategy.DEFAULT_ALIGNMENT_ENABLED);
        TrendRegime[] out = new TrendRegime[candles.size()];
        int offset = candles.size() - states.size();
        for (int i = 0; i < states.size(); i++) {
            out[offset + i] = states.get(i).regime();
        }
        return out;
    }

    // ---------------------------------------------------------------- sorties

    private static int trendCode(TrendRegime r) {
        return r == null ? 0 : switch (r) { case UP -> 1; case DOWN -> 2; case RANGE -> 3; };
    }

    private static String num(Object v) {
        if (v instanceof Boolean b) { return b ? "1" : "0"; }
        if (v instanceof Integer || v instanceof Long) { return v.toString(); }
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) { return "null"; }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) { return Long.toString((long) d); }
        return String.format(Locale.ROOT, "%.10g", d).replaceAll("0+(e|$)", "$1").replaceAll("\\.(e|$)", "$1");
    }

    private static void writeCsv(Path p, List<DayRow> rows, TrendRegime[] regime) throws IOException {
        StringBuilder sb = new StringBuilder("date,trend");
        COLS.keySet().forEach(k -> sb.append(',').append(k));
        sb.append('\n');
        for (DayRow r : rows) {
            sb.append(Instant.ofEpochMilli(r.timeMillis()).atZone(ZoneOffset.UTC).toLocalDate()).append(',')
                    .append(regime[r.index()] == null ? "" : regime[r.index()].name());
            for (Function<DayRow, Object> f : COLS.values()) {
                String v = num(f.apply(r));
                sb.append(',').append("null".equals(v) ? "" : v);
            }
            sb.append('\n');
        }
        Files.writeString(p, sb.toString());
    }

    private static String modeJson(String id, String label, List<DayRow> rows, TrendRegime[] regime) {
        StringBuilder sb = new StringBuilder("{\"id\":\"" + id + "\",\"label\":\"" + label + "\",\"cols\":{");
        sb.append("\"d\":[");
        for (int i = 0; i < rows.size(); i++) {
            sb.append(i > 0 ? "," : "").append('"')
                    .append(Instant.ofEpochMilli(rows.get(i).timeMillis()).atZone(ZoneOffset.UTC).toLocalDate()).append('"');
        }
        sb.append("],\"trend\":[");
        for (int i = 0; i < rows.size(); i++) {
            sb.append(i > 0 ? "," : "").append(trendCode(regime[rows.get(i).index()]));
        }
        sb.append(']');
        for (Map.Entry<String, Function<DayRow, Object>> e : COLS.entrySet()) {
            sb.append(",\"").append(e.getKey()).append("\":[");
            for (int i = 0; i < rows.size(); i++) {
                sb.append(i > 0 ? "," : "").append(num(e.getValue().apply(rows.get(i))));
            }
            sb.append(']');
        }
        return sb.append("}}").toString();
    }

    private static String setJson(RainbowAtrParamSet s) {
        return "{\"name\":\"" + s.name() + "\",\"tuning\":" + recordJson(s.tuning()) + ",\"globals\":" + recordJson(s.globals()) + "}";
    }

    private static String recordJson(Record r) {
        StringBuilder sb = new StringBuilder("{");
        try {
            RecordComponent[] cs = r.getClass().getRecordComponents();
            for (int i = 0; i < cs.length; i++) {
                Object v = cs[i].getAccessor().invoke(r);
                sb.append(i > 0 ? "," : "").append('"').append(cs[i].getName()).append("\":")
                        .append(v instanceof Number || v instanceof Boolean ? num(v) : "\"" + v + "\"");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return sb.append('}').toString();
    }
}
