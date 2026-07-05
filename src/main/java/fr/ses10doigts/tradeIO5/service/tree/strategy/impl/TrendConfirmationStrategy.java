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
import fr.ses10doigts.tradeIO5.service.tree.indicator.impl.EmaIndicator;
import fr.ses10doigts.tradeIO5.service.tree.strategy.AbstractStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * "Tendance confirmée" (étude §3.1) : EMA cross (biais directionnel) + ADX (filtre de force
 * de tendance) + RSI (garde-fou anti-épuisement).
 *
 * Contrairement à {@link DoubleRsiStrategy}, dont les entrées {@code IndicatorKey} sont toutes
 * homogènes (des RSI interchangeables moyennés ensemble), cette Strategy mélange des catégories
 * d'indicateurs complémentaires qui jouent chacune un rôle différent dans le calcul du score.
 * Elle discrimine donc explicitement chaque entrée de {@code parameters.getIndicatorParameters()}
 * via son {@code IndicatorType}, et, pour les deux EMA (même type, même TimeFrame mais des
 * {@code IndicatorParameters}/périodes différentes, donc des {@code IndicatorKey} distinctes),
 * via la valeur du paramètre {@code period} pour savoir laquelle est la rapide et laquelle est
 * la lente.
 */
@Component
public class TrendConfirmationStrategy extends AbstractStrategy {
    private static final Logger logger = LoggerFactory.getLogger(TrendConfirmationStrategy.class);

    public static final String P_TIME_FRAME_NAME = "timeframe";

    // Seuils propres à la Strategy (StrategyParameters.numericParams), pas aux IndicatorParameters
    // de chaque indicateur individuel : première Strategy du projet à réellement utiliser ce champ.
    public static final String P_ADX_LOW_THRESHOLD = "adxLowThreshold";
    public static final String P_ADX_HIGH_THRESHOLD = "adxHighThreshold";
    public static final String P_RSI_OVERBOUGHT_THRESHOLD = "rsiOverboughtThreshold";
    public static final String P_RSI_OVERSOLD_THRESHOLD = "rsiOversoldThreshold";

    private static final double DEFAULT_ADX_LOW_THRESHOLD = 15.0;
    private static final double DEFAULT_ADX_HIGH_THRESHOLD = 25.0;
    private static final double DEFAULT_RSI_OVERBOUGHT_THRESHOLD = 80.0;
    private static final double DEFAULT_RSI_OVERSOLD_THRESHOLD = 20.0;

    private final IndicatorEngine indicatorEngine;

    public TrendConfirmationStrategy(IndicatorRegistry indicatorRegistry, IndicatorEngine indicatorEngine) {
        super(indicatorRegistry);
        this.indicatorEngine = indicatorEngine;
    }

    @Override
    public StrategySignal evaluate(MarketContext context, StrategyParameters parameters) {

        // Check validité : 4 entrées attendues (EMA rapide, EMA lente, ADX, RSI)
        if (parameters.getIndicatorParameters().size() != 4) {
            logger.error("Strategy {} needs 4 param (EMA fast, EMA slow, ADX, RSI)", getName());
            return StrategySignal.notValid(getName(), "Strategy needs 4 param");
        }

        boolean hasError = false;

        // Contrairement à DoubleRsiStrategy qui traite ses entrées de façon homogène (toutes
        // RSI, moyennées), ici chaque entrée joue un rôle distinct. On les répartit d'abord par
        // rôle en discriminant via IndicatorKey#getType(), puis on les interprète.
        List<double[]> emaCandidates = new ArrayList<>(); // {period, value}
        Double adxValue = null;
        Double rsiValue = null;

        for (Map.Entry<IndicatorKey, IndicatorParameters> entry : parameters.getIndicatorParameters().entrySet()) {
            IndicatorKey indicatorKey = entry.getKey();
            IndicatorParameters indicatorParams = entry.getValue();

            // Choix du TF depuis les paramètres de l'indicateur, comme dans DoubleRsiStrategy
            TimeFrame tf = TimeFrame.valueOf(indicatorParams.getStrings().getOrDefault(P_TIME_FRAME_NAME, "H1"));

            IndicatorContext indicatorContext = new IndicatorContext(
                    context.symbol(),
                    tf,
                    context.series().get(tf),
                    null,
                    context.clock()
            );

            IndicatorSnapshot snapshot = indicatorEngine.execute(indicatorContext, indicatorParams);

            if (!snapshot.getResult().isValid()) {
                logger.error("!---- {} snapshot indicator value considered as INVALID --- Skipping!", indicatorKey.getType());
                hasError = true;
                continue;
            }

            // Stocker dans le MarketContext, comme DoubleRsiStrategy
            context.addIndicatorValue(indicatorKey, snapshot.getResult());

            double value = snapshot.getResult().getValue();
            IndicatorType type = indicatorKey.getType();

            switch (type) {
                case EMA -> {
                    double period = indicatorParams.getNumerics().getOrDefault(EmaIndicator.P_PERIOD_NAME, 0.0);
                    emaCandidates.add(new double[]{period, value});
                }
                case ADX -> adxValue = value;
                case RSI -> rsiValue = value;
                default -> logger.warn("{} : unexpected indicator type {} in indicatorParameters", getName(), type);
            }
        }

        // 1. Biais directionnel EMA -----------------------------------------------------------
        // Note : IndicatorResult n'expose qu'une valeur ponctuelle (pas d'historique), on ne peut
        // donc PAS détecter "le croisement vient d'avoir lieu à cette bougie précise" — seulement
        // la position relative actuelle des deux EMA (laquelle est au-dessus de l'autre en ce
        // moment). Un vrai détecteur de croisement nécessiterait de comparer au moins 2 bougies
        // consécutives, ce que le modèle actuel d'IndicatorResult ne permet pas.
        double emaBias = 0.0;
        if (emaCandidates.size() == 2) {
            double[] first = emaCandidates.get(0);
            double[] second = emaCandidates.get(1);
            double[] fast = first[0] <= second[0] ? first : second;
            double[] slow = first[0] <= second[0] ? second : first;

            double fastValue = fast[1];
            double slowValue = slow[1];

            if (fastValue > slowValue) {
                emaBias = 1.0;
            } else if (fastValue < slowValue) {
                emaBias = -1.0;
            } else {
                emaBias = 0.0;
            }
        } else {
            // EMA rapide et/ou lente manquante/invalide : impossible de statuer sur un biais,
            // on reste neutre plutôt que de deviner (hasError a déjà été positionné plus haut).
            logger.error("{} : expected 2 valid EMA results (fast/slow), got {}", getName(), emaCandidates.size());
        }

        // 2. Filtre ADX : facteur d'atténuation [0,1] interpolé linéairement entre les 2 seuils --
        double adxLowThreshold = parameters.getNumericParams().getOrDefault(P_ADX_LOW_THRESHOLD, DEFAULT_ADX_LOW_THRESHOLD);
        double adxHighThreshold = parameters.getNumericParams().getOrDefault(P_ADX_HIGH_THRESHOLD, DEFAULT_ADX_HIGH_THRESHOLD);

        double adxFactor;
        if (adxValue == null) {
            // ADX invalide/absent : posture conservatrice, on n'autorise aucune confirmation
            // de tendance plutôt que d'en supposer une.
            adxFactor = 0.0;
        } else if (adxHighThreshold <= adxLowThreshold) {
            // Paramétrage dégénéré : repli sur un seuil binaire simple (accepté par l'étude
            // comme version plus simple).
            adxFactor = adxValue >= adxHighThreshold ? 1.0 : 0.0;
        } else {
            adxFactor = clamp01((adxValue - adxLowThreshold) / (adxHighThreshold - adxLowThreshold));
        }

        // 3. Garde-fou RSI : réduit/neutralise le score si le mouvement semble épuisé -----------
        double rsiOverboughtThreshold = parameters.getNumericParams().getOrDefault(P_RSI_OVERBOUGHT_THRESHOLD, DEFAULT_RSI_OVERBOUGHT_THRESHOLD);
        double rsiOversoldThreshold = parameters.getNumericParams().getOrDefault(P_RSI_OVERSOLD_THRESHOLD, DEFAULT_RSI_OVERSOLD_THRESHOLD);

        double rsiGuardFactor = 1.0;
        if (rsiValue == null) {
            // RSI invalide/absent : on ne peut pas juger l'épuisement du mouvement, on ne
            // pénalise donc pas un signal EMA+ADX par ailleurs valide (facteur neutre = 1).
            rsiGuardFactor = 1.0;
        } else if (emaBias > 0 && rsiValue > rsiOverboughtThreshold) {
            // Biais haussier mais RSI en zone de surachat extrême : mouvement probablement
            // épuisé -> on réduit d'autant plus fort qu'on est loin au-delà du seuil (0 au
            // maximum théorique de 100 = neutralisation complète).
            rsiGuardFactor = clamp01((100.0 - rsiValue) / (100.0 - rsiOverboughtThreshold));
        } else if (emaBias < 0 && rsiValue < rsiOversoldThreshold) {
            // Symétrique côté survente / biais baissier.
            rsiGuardFactor = clamp01(rsiValue / rsiOversoldThreshold);
        }

        // 4. Score final = biais x atténuation ADX x garde-fou RSI, clampé à [-1,1] ------------
        double rawScore = emaBias * adxFactor * rsiGuardFactor;
        double score = Math.max(-1.0, Math.min(1.0, rawScore));

        logger.debug("{} : emaBias={}, adxFactor={}, rsiGuardFactor={} => score={}",
                getName(), emaBias, adxFactor, rsiGuardFactor, score);

        MarketOpinionHelper.ConfidenceSignal confidenceSignal = MarketOpinionHelper.scoreToConfidenceAndSignalType(score);

        logger.debug("{} : signal {} at confidence {}", getName(), confidenceSignal.signal, confidenceSignal.confidence);

        return StrategySignal.builder()
                .strategyName(getName())
                .valid(!hasError)
                .type(confidenceSignal.signal)
                .confidence(confidenceSignal.confidence)
                .score(score)
                .build();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public Set<StrategyType> getType() {
        return Set.of(StrategyType.ENTRY);
    }
}
