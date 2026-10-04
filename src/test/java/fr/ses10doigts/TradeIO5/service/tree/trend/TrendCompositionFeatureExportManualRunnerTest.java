package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.entity.currency.AssetProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.CandleEntity;
import fr.ses10doigts.tradeIO5.model.enumerate.market.CompletenessLevel;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.repository.AssetProviderRepository;
import fr.ses10doigts.tradeIO5.repository.market.CandleRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.Bucket;
import fr.ses10doigts.tradeIO5.service.tree.helper.IndicatorParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.AdxIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.impl.RegressiveTrendStrategy;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runner manuel (DB dev réelle, LECTURE SEULE, aucun appel réseau) — lot d'exécution du walk-forward
 * de la composition Trend unifié (cf. {@code docs/etudes/spec-composition-trend-unifie.md}).
 * <p>
 * N'évalue rien lui-même : exporte, bougie D1 par bougie D1, les briques de production brutes et
 * causales (jamais de donnée future) dans {@code target/trend-composition-walkforward/features-btc-d1.csv}.
 * La composition des candidates, la grille de calibration et les métriques walk-forward sont faites
 * par {@code tools/calibration/trend_composition_walkforward.py} à partir de ce CSV — découpage
 * volontaire pour pouvoir itérer sur les candidates/grilles sans recompiler ni relire la base
 * (mode de travail itératif demandé par Clem).
 * <p>
 * Colonnes exportées :
 * <ul>
 *   <li>OHLC D1 (resampling H1→D1 via {@link Bucket}, même patron que les runners Étapes 5/6) ;</li>
 *   <li>{@code regime} : {@link SwingStructureCalculator#computeTimeline} de production, un seul passage ;</li>
 *   <li>{@code adx} : {@link AdxIndicator} de production, période 14, sur le <b>préfixe complet</b>
 *   croissant (écart corrigé vs Étape 6, qui bornait la fenêtre à 250 bougies) ;</li>
 *   <li>{@code slopeN}/{@code r2N} pour N = 7/14/30 : {@link LinearRegressionCalculator} de production
 *   (fenêtres de {@code StrategyParametersFactory.RegressiveTrendParam.defaults}) — le score
 *   {@code RegressiveTrendStrategy} est recomposé en Python pour chaque point de grille, contre-vérifié
 *   contre la colonne {@code regDefault} (formule de production aux constantes par défaut, même
 *   réplication que le runner Étape 6, elle-même revalidée contre le bean Strategy).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "logging.level.fr.ses10doigts.tradeIO5=INFO",
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:localhost}:3306/tradeio5?rewriteBatchedStatements=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=klm",
        "spring.datasource.password=klm31",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DisplayName("Walk-forward composition Trend unifié — export des features D1 BTC (DB dev réelle, lecture seule, décommenter pour lancer)")
@Disabled("Runner manuel DB dev réelle (lecture seule, aucun réseau) — commenter cette annotation pour exporter les features.")
class TrendCompositionFeatureExportManualRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(TrendCompositionFeatureExportManualRunnerTest.class);

    private static final String SYMBOL = "BTC";
    private static final MarketDataSource SOURCE = MarketDataSource.BINANCE;
    private static final TimeFrame TF = TimeFrame.D1;
    private static final Instant H1_HISTORY_START = Instant.parse("2017-08-17T00:00:00Z");
    private static final int ADX_PERIOD = 14;
    private static final int[] LR_PERIODS = {7, 14, 30};

    @Autowired
    private CandleRepository candleRepository;
    @Autowired
    private AssetProviderRepository assetProviderRepository;
    @Autowired
    private DomainClock clock;

    private final LinearRegressionCalculator linearRegressionCalculator = new LinearRegressionCalculator();
    private final SwingStructureCalculator swingStructureCalculator = new SwingStructureCalculator();
    private final AdxIndicator adxIndicator = new AdxIndicator();

    @Test
    @DisplayName("Export features D1 BTC (régime, ADX, régressions 7/14/30)")
    void exportFeatures() throws IOException {
        Instant now = clock.now();
        String providerSymbol = assetProviderRepository.findByAsset_SymbolAndSource(SYMBOL, SOURCE)
                .map(AssetProvider::getProviderSymbol).orElse(SYMBOL);

        List<CandleEntity> h1 = candleRepository.findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(
                SOURCE, providerSymbol, TimeFrame.H1, H1_HISTORY_START, now);
        if (h1.isEmpty()) {
            throw new IllegalStateException("Aucune bougie H1 persistée pour " + providerSymbol);
        }
        Bucket bucket = new Bucket(TimeFrame.H1, h1.size() + 100);
        h1.forEach(e -> bucket.append(toMarketData(e)));
        BucketView view = bucket.view(TF, now);
        List<MarketData> d1 = new ArrayList<>(view.data());
        if (view.completeness() == CompletenessLevel.PARTIAL_LAST && !d1.isEmpty()) {
            d1.removeLast();
        }
        log.info("D1 candles: {} [{} .. {}]", d1.size(), d1.getFirst().getTimestamp(), d1.getLast().getTimestamp());

        List<SwingStructureSnapshot> swing = swingStructureCalculator.computeTimeline(d1);
        List<List<LinearRegressionSnapshot>> lr = new ArrayList<>();
        for (int p : LR_PERIODS) {
            lr.add(linearRegressionCalculator.computeTimeline(d1, p));
        }
        IndicatorParameters adxParams = IndicatorParametersFactory.buildAdxParams(TF, ADX_PERIOD);

        StringBuilder csv = new StringBuilder("date,open,high,low,close,regime,adx");
        for (int p : LR_PERIODS) {
            csv.append(",slope").append(p).append(",r2").append(p);
        }
        csv.append(",regDefault\n");

        for (int i = 0; i < d1.size(); i++) {
            MarketData c = d1.get(i);
            IndicatorResult adx = null;
            if (i + 1 >= 2 * ADX_PERIOD) {
                IndicatorContext ctx = new IndicatorContext(providerSymbol, TF,
                        MarketDataset.builder().marketDatas(d1.subList(0, i + 1)).timeFrame(TF).build(), Map.of(), clock);
                adx = adxIndicator.compute(ctx, adxParams);
            }

            csv.append(c.getTimestamp()).append(',').append(c.getOpen()).append(',').append(c.getHigh()).append(',')
                    .append(c.getLow()).append(',').append(c.getClose()).append(',')
                    .append(swing.get(i).regime()).append(',')
                    .append(adx != null && adx.isValid() ? fmt(adx.getValue()) : "");
            boolean allLr = true;
            double[] slopes = new double[LR_PERIODS.length];
            double[] r2s = new double[LR_PERIODS.length];
            for (int k = 0; k < LR_PERIODS.length; k++) {
                LinearRegressionSnapshot s = lr.get(k).get(i);
                if (s == null) {
                    allLr = false;
                    csv.append(",,");
                } else {
                    slopes[k] = s.normalizedSlope();
                    r2s[k] = s.r2();
                    csv.append(',').append(fmt(s.normalizedSlope())).append(',').append(fmt(s.r2()));
                }
            }
            csv.append(',').append(allLr ? fmt(RegressiveScoreReplica.score(slopes, r2s)) : "").append('\n');
        }

        Path out = Path.of("target", "trend-composition-walkforward");
        Files.createDirectories(out);
        Path file = out.resolve("features-btc-d1.csv");
        Files.writeString(file, csv.toString());
        log.info("Features écrites : {} ({} lignes)", file.toAbsolutePath(), d1.size());
    }

    /**
     * Réplique de la formule de {@code RegressiveTrendStrategy#evaluate} aux constantes par défaut
     * (identique à {@code TrendComboCorrelationManualRunnerTest#computeRegressiveTrendScore}) —
     * sert uniquement de colonne de contre-vérification pour la recomposition Python.
     */
    private static final class RegressiveScoreReplica {
        static double score(double[] slopes, double[] r2s) {
            double[] w = {
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                    RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG};
            double k = RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR;
            double[] sig = new double[3];
            double sum = 0;
            double wt = 0;
            for (int i = 0; i < 3; i++) {
                sig[i] = Math.tanh(slopes[i] * k);
                sum += w[i] * sig[i] * r2s[i];
                wt += w[i];
            }
            int agree = 0;
            int pairs = 0;
            for (int i = 0; i < 3; i++) {
                for (int j = i + 1; j < 3; j++) {
                    pairs++;
                    if (Math.signum(sig[i]) == Math.signum(sig[j])) {
                        agree++;
                    }
                }
            }
            return Math.clamp((sum / wt) * (0.4 + 0.6 * agree / (double) pairs), -1.0, 1.0);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.10g", v);
    }

    private static MarketData toMarketData(CandleEntity entity) {
        return MarketData.builder()
                .pair(entity.getPair())
                .timeFrame(entity.getTimeFrame())
                .timestamp(entity.getTimestamp())
                .open(entity.getOpen())
                .high(entity.getHigh())
                .low(entity.getLow())
                .close(entity.getClose())
                .volume(entity.getVolume())
                .build();
    }
}
