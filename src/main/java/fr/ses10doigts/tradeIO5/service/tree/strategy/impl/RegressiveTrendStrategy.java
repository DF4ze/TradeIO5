package fr.ses10doigts.tradeIO5.service.tree.strategy.impl;

import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.IndicatorKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.MarketContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategyParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.strategy.StrategySignal;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.strategy.StrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.model.enumerate.market.TimeFrame;
import fr.ses10doigts.tradeIO5.service.tree.helper.MarketOpinionHelper;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorEngine;
import fr.ses10doigts.tradeIO5.service.tree.indicator.IndicatorRegistry;
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.LinearRegressionIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import fr.ses10doigts.tradeIO5.service.tree.trend.RegressiveTrendScoreCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Tendances régressives" — piste proposée par Clem (2026-09-19) en complément de
 * {@link TrendConfirmationStrategy}/SWING_STRUCTURE, pas en remplacement : régression linéaire
 * (moindres carrés) sur 3 fenêtres D1 de tailles différentes (typiquement 7/14/30 jours),
 * combinées en un score de tendance continu — recalculé à chaque bougie, pas d'attente de
 * confirmation de pivot comme SWING_STRUCTURE (cf. javadoc de
 * {@link fr.ses10doigts.tradeIO5.service.tree.trend.LinearRegressionCalculator}).
 * <p>
 * Contrat d'entrée : exactement 3 indicateurs {@code LINEAR_REGRESSION}, avec des paramètres
 * {@code period} distincts (pas de contrainte sur les valeurs elles-mêmes — 7/14/30 est la
 * configuration par défaut de {@code StrategyParametersFactory}, pas un invariant vérifié ici).
 * <p>
 * <b>Combinaison</b> (cf. {@link RegressiveTrendScoreCalculator}) : les 3 fenêtres sont triées par
 * {@code period} croissant (rôle court-terme/moyen-terme/long-terme, pas les valeurs 7/14/30 en
 * dur) puis pondérées à parts égales par défaut (cf. "Défauts calibrés" ci-dessous — un poids
 * croissant avec la fenêtre était l'hypothèse de départ, battue par le walk-forward). Chaque fenêtre
 * contribue {@code tanh(normalizedSlope * slopeScaleFactor) * r2} : le R² pondère la contribution
 * par la qualité de l'ajustement. Un facteur d'alignement optionnel (1.0 si les 3 signaux ont le
 * même signe, dégradé sinon, désactivé par défaut) peut pénaliser les configurations où les fenêtres
 * se contredisent.
 * <p>
 * <b>Formule mutualisée</b> depuis l'Étape 8 de la roadmap Trend unifié (2026-09-24, cf.
 * {@code docs/prompts/prompt-implementation-trend-unifie-etape8-regression-hysteresis.md} §2.1) :
 * le calcul du score lui-même vit désormais dans {@link RegressiveTrendScoreCalculator} (service
 * pur, partagé avec {@code TrendAnalyzer}, qui porte désormais l'axe Trend/Régime de production).
 * Les constantes {@code P_*}/{@code DEFAULT_*} de cette classe restent le <b>seul et unique
 * endroit</b> où elles sont définies (mutualisées avec {@code TrendConfirmationStrategy}, qui les
 * référence directement plutôt que de les recopier).
 * <p>
 * <b>Défauts calibrés par walk-forward</b> (BTC D1 2017-2026, cf.
 * {@code docs/etudes/spec-composition-trend-unifie.md} §10.1/10.3, appliqués à cette Strategy par
 * l'Étape 8 même si elle n'est branchée dans aucune Opinion par défaut — pas d'impact prod) :
 * {@code slopeScaleFactor=400} (plateau de sensibilité mesuré entre 200 et l'infini), poids égaux
 * entre les 3 fenêtres (les poids croissants 0.2/0.3/0.5 étaient systématiquement battus),
 * alignement désactivé par défaut (dégradait l'accord avec la tendance réelle dans toutes les
 * variantes testées). Anciens défauts (100 / 0.2-0.3-0.5 / toujours actif) abandonnés, pas
 * recalibrés séparément dans ce lot pour cette Strategy (elle suit simplement les nouveaux défauts
 * de {@link RegressiveTrendScoreCalculator}).
 */
@Component
public class RegressiveTrendStrategy extends AbstractStrategy {
    private static final Logger logger = LoggerFactory.getLogger(RegressiveTrendStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";

    public static final String P_SLOPE_SCALE_FACTOR = "slopeScaleFactor";
    public static final String P_WEIGHT_SHORT = "weightShort";
    public static final String P_WEIGHT_MEDIUM = "weightMedium";
    public static final String P_WEIGHT_LONG = "weightLong";
    public static final String P_ALIGNMENT_ENABLED = "alignmentEnabled";

    public static final double DEFAULT_SLOPE_SCALE_FACTOR = 400.0;
    public static final double DEFAULT_WEIGHT_SHORT = 1.0 / 3.0;
    public static final double DEFAULT_WEIGHT_MEDIUM = 1.0 / 3.0;
    public static final double DEFAULT_WEIGHT_LONG = 1.0 / 3.0;
    public static final boolean DEFAULT_ALIGNMENT_ENABLED = false;

    private final IndicatorEngine indicatorEngine;

    public RegressiveTrendStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    private record WindowSignal(double period, double normalizedSlope, double r2) {
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {

        if (parameters.getIndicatorParameters().size() != 3) {
            logger.error("Strategy {} needs 3 LINEAR_REGRESSION indicators (short/medium/long)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 3 LINEAR_REGRESSION indicators");
        }

        boolean hasError = false;
        List<WindowSignal> windows = new ArrayList<>(3);

        for (Map.Entry<IndicatorKey, IndicatorParameters> entry : parameters.getIndicatorParameters().entrySet()) {
            IndicatorKey indicatorKey = entry.getKey();
            IndicatorParameters indicatorParams = entry.getValue();

            if (indicatorKey.getType() != IndicatorType.LINEAR_REGRESSION) {
                logger.warn("{} : unexpected indicator type {} in indicatorParameters", getName(), indicatorKey.getType());
                hasError = true;
                continue;
            }

            TimeFrame tf = indicatorKey.getTimeFrame();

            IndicatorContext indicatorContext = new IndicatorContext(
                    context.symbol(),
                    tf,
                    context.series().get(tf),
                    Map.of(),
                    context.clock()
            );

            IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, indicatorParams);

            if (!snapshot.getResult().isValid()) {
                logger.error("!---- LINEAR_REGRESSION (period={}) snapshot considered as INVALID --- Skipping!",
                        indicatorParams.getNumeric(LinearRegressionIndicator.P_PERIOD_NAME));
                hasError = true;
                continue;
            }

            context.addIndicatorValue(indicatorKey, snapshot.getResult());

            double period = indicatorParams.getNumeric(LinearRegressionIndicator.P_PERIOD_NAME);
            double normalizedSlope = snapshot.getResult().getValue();
            double r2 = snapshot.getResult().getValues().getOrDefault(LinearRegressionIndicator.V_R2, 0.0);

            windows.add(new WindowSignal(period, normalizedSlope, r2));
        }

        if (windows.size() < 3) {
            logger.error("{} : fewer than 3 valid LINEAR_REGRESSION windows ({}), cannot compute score", getName(), windows.size());
            return StrategySignal.notValid(getName(), "fewer than 3 valid LINEAR_REGRESSION windows");
        }

        windows.sort((a, b) -> Double.compare(a.period(), b.period()));

        double slopeScaleFactor = parameters.getNumericParams().getOrDefault(P_SLOPE_SCALE_FACTOR, DEFAULT_SLOPE_SCALE_FACTOR);
        double weightShort = parameters.getNumericParams().getOrDefault(P_WEIGHT_SHORT, DEFAULT_WEIGHT_SHORT);
        double weightMedium = parameters.getNumericParams().getOrDefault(P_WEIGHT_MEDIUM, DEFAULT_WEIGHT_MEDIUM);
        double weightLong = parameters.getNumericParams().getOrDefault(P_WEIGHT_LONG, DEFAULT_WEIGHT_LONG);
        boolean alignmentEnabled = parameters.getBooleanParams().getOrDefault(P_ALIGNMENT_ENABLED, DEFAULT_ALIGNMENT_ENABLED);

        double score = RegressiveTrendScoreCalculator.computeScore(
                new RegressiveTrendScoreCalculator.WindowInput(windows.get(0).normalizedSlope(), windows.get(0).r2()),
                new RegressiveTrendScoreCalculator.WindowInput(windows.get(1).normalizedSlope(), windows.get(1).r2()),
                new RegressiveTrendScoreCalculator.WindowInput(windows.get(2).normalizedSlope(), windows.get(2).r2()),
                slopeScaleFactor, weightShort, weightMedium, weightLong, alignmentEnabled
        );

        logger.debug("{} : windows(period,normSlope,r2)={} alignmentEnabled={} => score={}",
                getName(), windows, alignmentEnabled, score);

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(!hasError)
                .type(confidenceSignal.signal)
                .confidence(confidenceSignal.confidence)
                .score(score)
                .build();
    }

    @Override
    public Set<StrategyType> getType() {
        return Set.of(StrategyType.DIRECTIONAL);
    }

    @Override
    public boolean accepts(StrategyParameters parameters) {
        Map<IndicatorKey, IndicatorParameters> indicatorParameters = parameters.getIndicatorParameters();
        if (indicatorParameters == null || indicatorParameters.size() != 3) {
            return false;
        }

        long linearRegressionCount = indicatorParameters.values().stream()
                .filter(p -> p.getIndicatorType() == IndicatorType.LINEAR_REGRESSION)
                .count();

        return linearRegressionCount == 3;
    }
}
