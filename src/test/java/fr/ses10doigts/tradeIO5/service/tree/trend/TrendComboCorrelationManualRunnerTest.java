package fr.ses10doigts.tradeIO5.service.tree.trend;

import fr.ses10doigts.tradeIO5.model.dto.market.BucketView;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
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
import fr.ses10doigts.tradeIO5.service.tree.helper.StrategyParametersFactory;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.AdxIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.Strategy;
import fr.ses10doigts.tradeIO5.service.tree.strategy.StrategyRegistry;
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
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runner manuel (DB dev réelle, LECTURE SEULE — aucun appel réseau) — Étape 6 de la roadmap "Trend
 * unifié" : corrélation empirique ADX vs r² (RegressiveTrendStrategy) et taux de divergence entre
 * le régime SWING_STRUCTURE et le signe de RegressiveTrendStrategy.
 * <p>
 * Cf. docs/prompts/prompt-implementation-trend-unifie-etape6-correlation-adx-regression.md (prompt
 * d'origine) et docs/etudes/etude-indicateur-trend-unifie.md §8 (contexte). Ce lot ne calibre rien
 * (pas l'Étape 2) et ne fige aucune décision de composition : il mesure, exporte 2 CSV et rend une
 * lecture factuelle, soumise à Clem (cf. prompt §4 : "ne pas mettre à jour l'étude/roadmap avec une
 * décision finale dans ce lot").
 * <p>
 * <b>Section 0 — écarts de conception assumés, à lire avant toute exécution :</b>
 * <ul>
 *   <li><b>Lecture seule, aucun fetch réseau.</b> Contrairement à {@code HistoricalDataDepthManualRunnerTest}
 *   (Étape 5), ce runner ne fait AUCUN appel à {@code CachingMarketDataApiClient} : l'Étape 5
 *   (verdict GO, 2026-09-21) a déjà backfillé la vraie base de dev MySQL avec l'historique H1
 *   BTCUSDT complet (2017-08-17 → aujourd'hui) — cf. prompt §1 ("ne pas re-fetcher depuis zéro si un
 *   run précédent a déjà persisté la fenêtre visée"). Ce runner lit directement
 *   {@code CandleRepository} et dérive le D1 par resampling H1→D1 via {@link Bucket}, même patron
 *   que {@code RainbowDcaBacktestService}/{@code HistoricalDataDepthManualRunnerTest} section 6.
 *   Cible donc la même vraie base MySQL de dev (pas le H2 de test), en lecture seule uniquement
 *   ({@code spring.jpa.hibernate.ddl-auto=none}, aucune écriture dans ce test).</li>
 *   <li><b>ADX en fenêtre glissante bornée, pas en historique complet croissant.</b> {@link AdxIndicator}
 *   est réexécuté (production, pas réimplémenté) à chaque bougie D1 sur les {@value #ADX_LOOKBACK_WINDOW}
 *   bougies précédentes (fenêtre causale, jamais de donnée future) plutôt que sur tout l'historique
 *   depuis le début : le lissage de Wilder converge en quelques x{@code period} bougies, une fenêtre
 *   de {@value #ADX_LOOKBACK_WINDOW} (~18x la période 14) est largement suffisante pour un ADX
 *   convergé, et borner la fenêtre évite un coût O(n²) sur ~3300 bougies. Écart assumé, signalé ici
 *   comme demandé par le prompt.</li>
 *   <li><b>r² court/moyen/long recalculés directement via {@link LinearRegressionCalculator}</b>
 *   (service pur, appelé ici exactement comme le fait {@code LinearRegressionIndicator} — délégation
 *   sans transformation, vérifié sur son code source) plutôt que via {@code IndicatorEngine}, pour
 *   éviter ~3300×3 exécutions de cache d'indicateur pour un calcul qui n'a besoin d'aucune
 *   résolution de dépendance. Le score combiné {@code regressiveTrendScore}, lui, est recalculé
 *   directement via {@link #computeRegressiveTrendScore} — même formule et mêmes constantes
 *   publiques que {@link RegressiveTrendStrategy#evaluate}, pas une formule différente (consigne du
 *   prompt : "réutiliser sa sortie plutôt que recalculer un score maison différent"). Un
 *   contre-échantillon (une bougie sur {@value #STRATEGY_CROSSCHECK_STRIDE}) revalide en plus ce
 *   recalcul contre une VRAIE exécution du bean {@link RegressiveTrendStrategy} (via
 *   {@link StrategyRegistry}) à la même date, pour garantir qu'aucune divergence ne s'est glissée
 *   dans la réplication (cf. section 2 du rapport généré pour le résultat de ce contre-échantillon).</li>
 *   <li><b>{@code atrMultiplier} : la mise en garde du prompt d'origine ne s'applique plus.</b> Le
 *   prompt (rédigé le 2026-09-21) demandait d'utiliser un placeholder {@code atrMultiplier=2.0} non
 *   calibré pour la partie (b) si l'Étape 2 n'était pas close, et de le signaler. Or
 *   {@code SwingStructureCalculator} a été réécrit intégralement le 2026-09-19 (avant même la
 *   rédaction du prompt) pour ne PLUS dépendre d'aucun ATR/seuil/{@code atrMultiplier} — vérifié ici
 *   sur le code actuel de {@link SwingStructureCalculator} et sur
 *   {@code IndicatorParametersFactory#buildSwingStructureParams}/{@code StrategyParametersFactory}
 *   (plus aucun champ {@code atrMultiplier} nulle part). Ce point n'a pas été répercuté dans
 *   l'étude/la roadmap (toujours au 2026-09-18 sur ce point précis) : signalé ici tel quel, à
 *   corriger dans ces documents une fois ce rapport discuté avec Clem (cf. section 0 du rapport
 *   généré). Conséquence pratique : la partie (b) de ce lot n'a aucun paramètre placeholder, elle
 *   tourne sur le {@code SwingStructureCalculator} de production tel quel.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "logging.level.fr.ses10doigts.tradeIO5=INFO",
        // Cible délibérément la vraie base de dev (cf. javadoc section 0), pas le H2 de test —
        // en LECTURE SEULE uniquement (aucune écriture dans ce runner).
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:localhost}:3306/tradeio5?rewriteBatchedStatements=true",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=klm",
        "spring.datasource.password=klm31",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.hibernate.ddl-auto=none"
})
@DisplayName("Étape 6 Trend unifié — corrélation ADX vs r² + divergence régime/régression (DB dev réelle, lecture seule, décommenter pour lancer)")
@Disabled("Runner manuel DB dev réelle (lecture seule, aucun réseau) — commenter cette annotation pour lancer l'analyse Étape 6.")
class TrendComboCorrelationManualRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(TrendComboCorrelationManualRunnerTest.class);

    private static final String SYMBOL = "BTC";
    private static final MarketDataSource SOURCE = MarketDataSource.BINANCE;
    private static final TimeFrame TF = TimeFrame.D1;
    /** Départ de l'historique H1 déjà persisté par l'Étape 5 (cf. son rapport, section 1). */
    private static final Instant H1_HISTORY_START = Instant.parse("2017-08-17T00:00:00Z");

    /** Période ADX de production (TrendConfirmationStrategy / DefaultLocalOpinionParamsProvider, D1). */
    private static final int ADX_PERIOD = 14;
    /** Fenêtre causale bornée pour l'exécution répétée d'ADX (cf. section 0 de la javadoc de classe). */
    private static final int ADX_LOOKBACK_WINDOW = 250;

    /** Fenêtres LINEAR_REGRESSION de production (StrategyParametersFactory.RegressiveTrendParam.defaults). */
    private static final int LR_SHORT_PERIOD = 7;
    private static final int LR_MEDIUM_PERIOD = 14;
    private static final int LR_LONG_PERIOD = 30;

    /** Une bougie sur N revalidée par une vraie exécution du bean Strategy (cf. section 0). */
    private static final int STRATEGY_CROSSCHECK_STRIDE = 200;
    private static final double CROSSCHECK_EPSILON = 1e-9;

    @Autowired
    private CandleRepository candleRepository;

    @Autowired
    private AssetProviderRepository assetProviderRepository;

    @Autowired
    private DomainClock clock;

    @Autowired
    private StrategyRegistry strategyRegistry;

    private final LinearRegressionCalculator linearRegressionCalculator = new LinearRegressionCalculator();
    private final SwingStructureCalculator swingStructureCalculator = new SwingStructureCalculator();
    private final AdxIndicator adxIndicator = new AdxIndicator();

    @Test
    @DisplayName("Corrélation ADX/r² (question a) + divergence régime/régression (question b) sur l'historique D1 BTC persisté")
    void analyseCorrelationEtDivergence_HistoriqueD1BTC() throws IOException {
        StringBuilder report = new StringBuilder();
        List<String> deviations = new ArrayList<>();
        Instant runStarted = clock.now();

        String providerSymbol = resolveProviderSymbol();
        report.append("# Étape 6 — Corrélation ADX vs r² & divergence régime/régression (BTC D1)\n\n");
        report.append("Exécution : ").append(runStarted).append("\n");
        report.append("Datasource : `jdbc:mysql://localhost:3306/tradeio5` (base de dev réelle, LECTURE SEULE — aucune écriture, aucun appel réseau)\n");
        report.append("Symbole nu : `").append(SYMBOL).append("` -> providerSymbol Binance : `").append(providerSymbol).append("`\n\n");

        report.append("## 0. Écart signalé : `atrMultiplier` — mise en garde du prompt devenue sans objet\n\n");
        report.append("Le prompt d'origine (rédigé 2026-09-21) demandait d'utiliser un placeholder `atrMultiplier=2.0` "
                + "non calibré pour la partie (b) tant que l'Étape 2 n'est pas close, et de le signaler dans ce rapport. "
                + "Or `SwingStructureCalculator` a été réécrit intégralement le **2026-09-19** (donc AVANT la rédaction "
                + "du prompt) pour ne plus dépendre d'aucun ATR/seuil/`atrMultiplier` du tout (cf. sa javadoc actuelle, "
                + "et `IndicatorParametersFactory#buildSwingStructureParams`/`StrategyParametersFactory` : plus aucun "
                + "champ `atrMultiplier` nulle part dans le code). L'étude (`etude-indicateur-trend-unifie.md`) et la "
                + "roadmap n'ont pas été mises à jour sur ce point précis (encore au 2026-09-18 sur `atrMultiplier`). "
                + "**Conséquence pour ce lot** : la partie (b) ci-dessous tourne sur le `SwingStructureCalculator` de "
                + "production tel quel, sans aucun paramètre placeholder — la mise en garde du prompt ne s'applique "
                + "plus. Signalé ici comme écart factuel, pas corrigé silencieusement dans l'étude/la roadmap (à faire "
                + "une fois ce rapport discuté avec Clem, cf. section 5).\n\n");
        deviations.add("`atrMultiplier` : la mise en garde du prompt (placeholder 2.0 si Étape 2 non close) est sans "
                + "objet — `SwingStructureCalculator` ne prend plus aucun paramètre de calibration depuis sa réécriture "
                + "du 2026-09-19, antérieure à la rédaction du prompt. Étude/roadmap à corriger sur ce point (non fait "
                + "dans ce lot, cf. prompt : pas de mise à jour de décision finale avant discussion avec Clem).");

        // ---- §1 : préparation des séries (lecture seule, pas de fetch réseau — cf. section 0 javadoc) ----
        report.append("## 1. Préparation des séries (lecture seule, aucun appel réseau)\n\n");
        List<CandleEntity> h1Entities = candleRepository
                .findBySourceAndPairAndTimeFrameAndTimestampBetweenOrderByTimestampAsc(SOURCE, providerSymbol, TimeFrame.H1, H1_HISTORY_START, runStarted);
        if (h1Entities.isEmpty()) {
            throw new IllegalStateException("Aucune bougie H1 persistée pour " + providerSymbol
                    + " — l'Étape 5 (backfill) doit être exécutée avant ce lot.");
        }
        Bucket bucket = new Bucket(TimeFrame.H1, h1Entities.size() + 100);
        for (CandleEntity entity : h1Entities) {
            bucket.append(toMarketData(entity));
        }
        BucketView d1View = bucket.view(TF, runStarted);
        List<MarketData> d1Candles = new ArrayList<>(d1View.data());
        if (d1View.completeness() == CompletenessLevel.PARTIAL_LAST && !d1Candles.isEmpty()) {
            d1Candles.removeLast();
        }
        report.append("- Bougies H1 lues (base persistée par l'Étape 5) : ").append(h1Entities.size())
                .append(" [").append(H1_HISTORY_START).append(" .. ").append(runStarted).append("]\n");
        report.append("- Bougies D1 dérivées (resampling H1→D1 via `Bucket`, dernière bougie partielle exclue) : ")
                .append(d1Candles.size()).append("\n");
        if (!d1Candles.isEmpty()) {
            report.append("- Première : ").append(d1Candles.getFirst().getTimestamp())
                    .append(" — Dernière : ").append(d1Candles.getLast().getTimestamp()).append("\n");
        }
        report.append("\n");
        log.info("D1 candles derived: {} [{} .. {}]", d1Candles.size(),
                d1Candles.isEmpty() ? null : d1Candles.getFirst().getTimestamp(),
                d1Candles.isEmpty() ? null : d1Candles.getLast().getTimestamp());

        if (d1Candles.size() <= LR_LONG_PERIOD) {
            throw new IllegalStateException("Historique D1 insuffisant (" + d1Candles.size()
                    + " bougies) pour la plus longue fenêtre LINEAR_REGRESSION (" + LR_LONG_PERIOD + ").");
        }

        // ---- Régime SWING_STRUCTURE sur toute la timeline (un seul passage, cf. section 0) ----
        List<SwingStructureSnapshot> swingTimeline = swingStructureCalculator.computeTimeline(d1Candles);

        // ---- Boucle principale : ADX, r² court/moyen/long, score RegressiveTrend, régime ----
        int startIndex = Math.max(LR_LONG_PERIOD - 1, 2 * ADX_PERIOD - 1); // 1ère bougie où ADX ET les 3 LR sont valides
        report.append("## 2. Corrélation ADX vs r² (question a)\n\n");
        report.append("- Fenêtre ADX bornée à ").append(ADX_LOOKBACK_WINDOW).append(" bougies (cf. section 0 javadoc, écart assumé).\n");
        report.append("- Bougies exploitables (ADX + 3 fenêtres LINEAR_REGRESSION valides) : à partir de l'index ")
                .append(startIndex).append(" (bougie ").append(d1Candles.get(startIndex).getTimestamp()).append(").\n\n");

        List<Double> adxValues = new ArrayList<>();
        List<Double> r2ShortValues = new ArrayList<>();
        List<Double> r2MediumValues = new ArrayList<>();
        List<Double> r2LongValues = new ArrayList<>();
        List<Double> regressiveScores = new ArrayList<>();

        StringBuilder adxCsv = new StringBuilder("date,adxValue,r2Short,r2Medium,r2Long,regressiveTrendScore\n");
        StringBuilder divergenceCsv = new StringBuilder("date,swingRegime,regressiveTrendScore,agreement,regressiveTrendScoreAbs\n");

        int crosscheckCount = 0;
        int crosscheckMismatches = 0;

        Map<SwingStructureRegime, int[]> regimeCounts = new EnumMap<>(SwingStructureRegime.class); // [total, disagreements]
        Map<SwingStructureRegime, List<Double>> confirmedDisagreementAbsScores = new EnumMap<>(SwingStructureRegime.class);
        int totalDivergenceRows = 0;
        int totalDisagreements = 0;

        StrategyParameters regressiveParams = StrategyParametersFactory.buildRegressiveTrendStrategyParam(
                StrategyParametersFactory.RegressiveTrendParam.defaults(TF));
        Strategy regressiveTrendStrategy = strategyRegistry.get(RegressiveTrendStrategy.class.getSimpleName());
        IndicatorParameters adxParams = IndicatorParametersFactory.buildAdxParams(TF, ADX_PERIOD);

        for (int i = startIndex; i < d1Candles.size(); i++) {
            MarketData candle = d1Candles.get(i);
            List<MarketData> adxWindow = d1Candles.subList(Math.max(0, i + 1 - ADX_LOOKBACK_WINDOW), i + 1);
            List<MarketData> fullPrefix = d1Candles.subList(0, i + 1);

            IndicatorContext adxContext = new IndicatorContext(providerSymbol, TF,
                    MarketDataset.builder().marketDatas(adxWindow).timeFrame(TF).build(), Map.of(), clock);
            IndicatorResult adxResult = adxIndicator.compute(adxContext, adxParams);

            LinearRegressionResult lrShort = linearRegressionCalculator.compute(fullPrefix, LR_SHORT_PERIOD);
            LinearRegressionResult lrMedium = linearRegressionCalculator.compute(fullPrefix, LR_MEDIUM_PERIOD);
            LinearRegressionResult lrLong = linearRegressionCalculator.compute(fullPrefix, LR_LONG_PERIOD);

            double regressiveScore = computeRegressiveTrendScore(lrShort, lrMedium, lrLong);

            if (i % STRATEGY_CROSSCHECK_STRIDE == 0) {
                MarketContext strategyContext = new MarketContext(
                        providerSymbol, candle.getClose(), clock,
                        Map.of(TF, MarketDataset.builder().marketDatas(fullPrefix).timeFrame(TF).build()),
                        new HashMap<>());
                StrategySignal signal = regressiveTrendStrategy.evaluate(strategyContext, regressiveParams);
                crosscheckCount++;
                if (!signal.isValid() || Math.abs(signal.getScore() - regressiveScore) > CROSSCHECK_EPSILON) {
                    crosscheckMismatches++;
                    log.warn("Cross-check mismatch at {} : direct={} strategyBean={} valid={}",
                            candle.getTimestamp(), regressiveScore, signal.getScore(), signal.isValid());
                }
            }

            if (adxResult.isValid()) {
                double adxValue = adxResult.getValue();
                adxValues.add(adxValue);
                r2ShortValues.add(lrShort.r2());
                r2MediumValues.add(lrMedium.r2());
                r2LongValues.add(lrLong.r2());
                regressiveScores.add(regressiveScore);
                adxCsv.append(candle.getTimestamp()).append(',')
                        .append(adxValue).append(',')
                        .append(lrShort.r2()).append(',')
                        .append(lrMedium.r2()).append(',')
                        .append(lrLong.r2()).append(',')
                        .append(regressiveScore).append('\n');
            }

            SwingStructureRegime regime = swingTimeline.get(i).regime();
            int regimeSign = regimeSign(regime);
            double scoreSign = Math.signum(regressiveScore);
            boolean agreement = regimeSign == scoreSign;
            double scoreAbs = Math.abs(regressiveScore);

            divergenceCsv.append(candle.getTimestamp()).append(',')
                    .append(regime).append(',')
                    .append(regressiveScore).append(',')
                    .append(agreement).append(',')
                    .append(scoreAbs).append('\n');

            totalDivergenceRows++;
            int[] counts = regimeCounts.computeIfAbsent(regime, r -> new int[2]);
            counts[0]++;
            if (!agreement) {
                totalDisagreements++;
                counts[1]++;
                if (regime == SwingStructureRegime.BULL_CONFIRMED || regime == SwingStructureRegime.BEAR_CONFIRMED) {
                    confirmedDisagreementAbsScores.computeIfAbsent(regime, r -> new ArrayList<>()).add(scoreAbs);
                }
            }
        }

        double[] adxArr = toArray(adxValues);
        double[] r2ShortArr = toArray(r2ShortValues);
        double[] r2MediumArr = toArray(r2MediumValues);
        double[] r2LongArr = toArray(r2LongValues);
        double[] scoreArr = toArray(regressiveScores);

        report.append("- Bougies avec ADX valide (fenêtre ").append(ADX_LOOKBACK_WINDOW).append(") : ").append(adxArr.length).append("\n");
        report.append("- Contre-échantillon Strategy (1 bougie / ").append(STRATEGY_CROSSCHECK_STRIDE).append(") : ")
                .append(crosscheckCount).append(" vérifiées, ").append(crosscheckMismatches).append(" désaccord(s) > ")
                .append(CROSSCHECK_EPSILON).append("\n\n");

        report.append("| Paire | Pearson | Spearman |\n|---|---|---|\n");
        report.append("| ADX vs r²(7) | ").append(fmt(pearson(adxArr, r2ShortArr))).append(" | ").append(fmt(spearman(adxArr, r2ShortArr))).append(" |\n");
        report.append("| ADX vs r²(14) | ").append(fmt(pearson(adxArr, r2MediumArr))).append(" | ").append(fmt(spearman(adxArr, r2MediumArr))).append(" |\n");
        report.append("| ADX vs r²(30) | ").append(fmt(pearson(adxArr, r2LongArr))).append(" | ").append(fmt(spearman(adxArr, r2LongArr))).append(" |\n");
        report.append("| ADX vs regressiveTrendScore | ").append(fmt(pearson(adxArr, scoreArr))).append(" | ").append(fmt(spearman(adxArr, scoreArr))).append(" |\n\n");
        report.append("Lecture indicative (pas de seuil de décision codé en dur) : |ρ| > 0.6-0.7 appuierait "
                + "l'hypothèse de double comptage soulevée le 21/09 ; une corrélation faible l'infirmerait — à lire "
                + "par Clem, cf. section 4.\n\n");

        // ---- §3 : divergence régime vs régression (question b) ----
        report.append("## 3. Divergence régime SWING_STRUCTURE vs signe RegressiveTrendStrategy (question b)\n\n");
        report.append("Convention : `regimeSign` = +1 (BULL_CONFIRMED/WARNING_BULL_BREAK), -1 (BEAR_CONFIRMED/WARNING_BEAR_BREAK), "
                + "0 (UNDEFINED) ; `scoreSign` = signe de `regressiveTrendScore` (0 si nul) ; `agreement` = mêmes signes "
                + "(un régime UNDEFINED n'est donc \"en accord\" que si le score de régression est lui-même exactement nul, "
                + "cas très rare — la plupart des lignes UNDEFINED comptent comme désaccord, par construction).\n\n");
        report.append("- Lignes analysées : ").append(totalDivergenceRows).append("\n");
        report.append("- Désaccords : ").append(totalDisagreements).append(" (")
                .append(fmt(100.0 * totalDisagreements / totalDivergenceRows)).append("%)\n\n");

        report.append("| Régime | Total | Désaccords | Taux désaccord |\n|---|---|---|---|\n");
        for (SwingStructureRegime regime : SwingStructureRegime.values()) {
            int[] counts = regimeCounts.getOrDefault(regime, new int[2]);
            double rate = counts[0] == 0 ? 0.0 : 100.0 * counts[1] / counts[0];
            report.append("| ").append(regime).append(" | ").append(counts[0]).append(" | ").append(counts[1])
                    .append(" | ").append(fmt(rate)).append("% |\n");
        }
        report.append("\n");

        report.append("### Force du signal de régression lors des désaccords en régime CONFIRMÉ (BULL/BEAR)\n\n");
        for (SwingStructureRegime regime : List.of(SwingStructureRegime.BULL_CONFIRMED, SwingStructureRegime.BEAR_CONFIRMED)) {
            List<Double> absScores = confirmedDisagreementAbsScores.getOrDefault(regime, List.of());
            if (absScores.isEmpty()) {
                report.append("- ").append(regime).append(" : aucun désaccord observé.\n");
                continue;
            }
            double mean = absScores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            long strongCount = absScores.stream().filter(v -> v > 0.5).count();
            report.append("- ").append(regime).append(" : ").append(absScores.size())
                    .append(" désaccord(s), |regressiveTrendScoreAbs| moyen=").append(fmt(mean))
                    .append(", dont ").append(strongCount).append(" (").append(fmt(100.0 * strongCount / absScores.size()))
                    .append("%) > 0.5 (indicatif, pas un seuil de décision).\n");
        }
        report.append("\n");

        // ---- Écrit les CSV ----
        Path outputDir = Path.of("target", "trend-combo-correlation");
        Files.createDirectories(outputDir);
        Path adxCsvPath = outputDir.resolve("adx-vs-r2.csv");
        Path divergenceCsvPath = outputDir.resolve("regime-vs-regression-divergence.csv");
        Files.writeString(adxCsvPath, adxCsv.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(divergenceCsvPath, divergenceCsv.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        report.append("## 4. CSV générés\n\n");
        report.append("- `").append(adxCsvPath.toAbsolutePath()).append("` (").append(adxArr.length).append(" lignes)\n");
        report.append("- `").append(divergenceCsvPath.toAbsolutePath()).append("` (").append(totalDivergenceRows).append(" lignes)\n\n");

        // ---- Écarts ----
        report.append("## 5. Écarts pris par rapport au prompt d'origine\n\n");
        for (String deviation : deviations) {
            report.append("- ").append(deviation).append("\n");
        }
        report.append("- ADX recalculé sur une fenêtre glissante bornée (").append(ADX_LOOKBACK_WINDOW)
                .append(" bougies), pas sur l'historique complet croissant — cf. section 0 de la javadoc du runner.\n");
        report.append("- `regressiveTrendScore` recalculé directement (formule/constantes publiques de `RegressiveTrendStrategy`, "
                + "r² via `LinearRegressionCalculator` exécuté directement) plutôt que via une exécution `IndicatorEngine` "
                + "systématique, avec contre-échantillon (1/").append(STRATEGY_CROSSCHECK_STRIDE)
                .append(") revalidé contre une vraie exécution de la Strategy — cf. section 2 ci-dessus pour le résultat du contre-échantillon.\n\n");

        String reportText = report.toString();
        log.info("\n{}", reportText);

        Path reportPath = outputDir.resolve("rapport-etape6-correlation-adx-regression-" + runStarted.toString().replace(":", "-") + ".md");
        Files.writeString(reportPath, reportText, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Rapport écrit dans {}", reportPath.toAbsolutePath());
    }

    /**
     * Réplique exactement la formule de combinaison de {@link RegressiveTrendStrategy#evaluate} —
     * mêmes constantes publiques ({@code DEFAULT_SLOPE_SCALE_FACTOR}/{@code DEFAULT_WEIGHT_*}),
     * même ordre court/moyen/long, même facteur d'alignement — pas une formule différente (cf.
     * section 0 de la javadoc de classe : revalidé par contre-échantillon contre une vraie
     * exécution de la Strategy).
     */
    private static double computeRegressiveTrendScore(LinearRegressionResult lrShort, LinearRegressionResult lrMedium, LinearRegressionResult lrLong) {
        double[] normalizedSlopes = {lrShort.normalizedSlope(), lrMedium.normalizedSlope(), lrLong.normalizedSlope()};
        double[] r2s = {lrShort.r2(), lrMedium.r2(), lrLong.r2()};
        double[] weights = {
                RegressiveTrendStrategy.DEFAULT_WEIGHT_SHORT,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_MEDIUM,
                RegressiveTrendStrategy.DEFAULT_WEIGHT_LONG
        };

        double[] signals = new double[3];
        double weightedSum = 0.0;
        double weightTotal = 0.0;
        for (int i = 0; i < 3; i++) {
            double signal = Math.tanh(normalizedSlopes[i] * RegressiveTrendStrategy.DEFAULT_SLOPE_SCALE_FACTOR);
            signals[i] = signal;
            weightedSum += weights[i] * signal * r2s[i];
            weightTotal += weights[i];
        }
        double weightedSignal = weightTotal == 0 ? 0.0 : weightedSum / weightTotal;

        int agreements = 0;
        int pairs = 0;
        for (int i = 0; i < signals.length; i++) {
            for (int j = i + 1; j < signals.length; j++) {
                pairs++;
                if (Math.signum(signals[i]) == Math.signum(signals[j])) {
                    agreements++;
                }
            }
        }
        double alignmentFactor = 0.4 + 0.6 * (pairs == 0 ? 1.0 : (double) agreements / pairs);

        return Math.clamp(weightedSignal * alignmentFactor, -1.0, 1.0);
    }

    private static int regimeSign(SwingStructureRegime regime) {
        return switch (regime) {
            case BULL_CONFIRMED, WARNING_BULL_BREAK -> 1;
            case BEAR_CONFIRMED, WARNING_BEAR_BREAK -> -1;
            case UNDEFINED -> 0;
        };
    }

    private static double[] toArray(List<Double> values) {
        double[] arr = new double[values.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = values.get(i);
        }
        return arr;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        if (n == 0) {
            return 0.0;
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double num = 0, denX = 0, denY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX, dy = y[i] - meanY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        double den = Math.sqrt(denX * denY);
        return den == 0 ? 0.0 : num / den;
    }

    /** Rangs moyens (gestion des ex-aequo) — cf. {@link #spearman}. */
    private static double[] rank(double[] values) {
        int n = values.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, Comparator.comparingDouble(i -> values[i]));
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && values[idx[j + 1]] == values[idx[i]]) {
                j++;
            }
            double avgRank = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                ranks[idx[k]] = avgRank;
            }
            i = j + 1;
        }
        return ranks;
    }

    /** Corrélation de Spearman = Pearson sur les rangs (gestion des ex-aequo par rang moyen). */
    private static double spearman(double[] x, double[] y) {
        return pearson(rank(x), rank(y));
    }

    /** Même patron que {@code HistoricalDataDepthManualRunnerTest#resolveProviderSymbol}. */
    private String resolveProviderSymbol() {
        return assetProviderRepository.findByAsset_SymbolAndSource(SYMBOL, SOURCE)
                .map(AssetProvider::getProviderSymbol)
                .orElse(SYMBOL);
    }

    /** Même patron que {@code HistoricalDataDepthManualRunnerTest#toMarketData}. */
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
