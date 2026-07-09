package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketDataset;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorDependency;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorDependencyKey;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorSnapshot;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.DependentIndicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zones de rejet (étude "indicateurs-macro-externes", §14, item J) : détecte les bougies qui
 * "testent" un niveau de prix (mèche) puis sont repoussées, et regroupe les rejets proches en prix
 * sur une fenêtre de lookback en "zones" plus significatives qu'un pivot isolé.
 * <p>
 * Ne dépend d'aucune source réseau ({@code impl/}, pas {@code external/}) : calcul entièrement
 * interne sur {@link MarketDataset}, comme {@link AtrIndicator}/{@link BollingerIndicator} dont il
 * réutilise le motif "fenêtre glissante", et réutilise directement le calcul {@link AtrIndicator}
 * (plutôt que de recalculer une volatilité en interne) via {@link DependentIndicator}, sur le même
 * principe que {@link MacdIndicator}/{@link RainbowSmaIndicator}.
 * <p>
 * <b>Définition d'un rejet haussier</b> (une bougie teste un plus bas puis est repoussée vers le
 * haut) : {@code lowerWick > k1 * body} (mèche nettement plus grande que le corps) ET
 * {@code lowerWick > k2 * ATR} (mèche significative par rapport à la volatilité récente, pas juste
 * grande dans l'absolu) ET {@code close > low + p * range} (clôture repoussée loin du plus bas).
 * Symétrique pour un rejet baissier (mèche haute, clôture repoussée vers le bas). Ces 3 conditions
 * évitent le symptôme observé sur d'autres indicateurs de ce type ("font n'importe quoi") : une
 * mèche longue en absolu ne veut rien dire dans un marché déjà volatil (d'où la comparaison à
 * l'ATR), une mèche longue par rapport au corps peut apparaître sur une quasi-doji sans rejet
 * directionnel réel (d'où la condition sur la position de clôture).
 * <p>
 * <b>Important</b> — cette formule est un point de départ à calibrer, pas une formule figée : voir
 * {@code docs/calibration-rejection-zone.md} pour le protocole de validation empirique
 * (calibration visuelle, taux de réaction statistique, sensibilité aux paramètres) et les valeurs
 * de {@code k1}/{@code k2}/{@code p}/{@code clusterDistance} retenues avant tout branchement dans
 * une {@code Strategy}/{@code Opinion}.
 */
@Component
public class RejectionZoneIndicator implements Indicator, DependentIndicator {

    private final Logger logger = LoggerFactory.getLogger(RejectionZoneIndicator.class);

    public static final String P_K1 = "k1";
    public static final String P_K2 = "k2";
    public static final String P_P = "p";
    public static final String P_LOOKBACK = "lookback";
    public static final String P_CLUSTER_DISTANCE = "clusterDistance";
    public static final String P_ATR_PERIOD = "atrPeriod";

    // Point de départ à calibrer (cf. javadoc de classe) : valeurs indicatives, à valider par le
    // protocole de docs/calibration-rejection-zone.md avant tout branchement en aval.
    public static final double DEFAULT_K1 = 1.5;
    public static final double DEFAULT_K2 = 0.5;
    public static final double DEFAULT_P = 0.66;
    public static final int DEFAULT_LOOKBACK = 200;
    public static final double DEFAULT_CLUSTER_DISTANCE = 0.5;
    public static final int DEFAULT_ATR_PERIOD = 14;

    public static final String V_NEAREST_RESISTANCE_PRICE = "nearestResistancePrice";
    public static final String V_NEAREST_RESISTANCE_STRENGTH = "nearestResistanceStrength";
    public static final String V_NEAREST_RESISTANCE_TOUCHES = "nearestResistanceTouches";
    public static final String V_NEAREST_SUPPORT_PRICE = "nearestSupportPrice";
    public static final String V_NEAREST_SUPPORT_STRENGTH = "nearestSupportStrength";
    public static final String V_NEAREST_SUPPORT_TOUCHES = "nearestSupportTouches";

    /** Role de la dépendance ATR, public pour rester testable sans passer par IndicatorEngine
     *  (cf. RejectionZoneIndicatorTest, qui construit l'IndicatorContext.dependencies() à la main
     *  plutôt que d'exécuter l'ATR réel — patron déjà utilisé par AdxIndicatorTest/BollingerIndicatorTest
     *  pour tester un indicateur pur sur bougies synthétiques). */
    public static final String ATR_DEPENDENCY_ROLE = "VOLATILITY";
    private static final IndicatorDependencyKey K_ATR =
            new IndicatorDependencyKey(IndicatorType.ATR, ATR_DEPENDENCY_ROLE);

    // Pondération du score de force de zone (cf. javadoc de classe, "à calibrer" comme le reste
    // de la formule). touches et récence dominent volontairement le volume (qui vient plutôt
    // confirmer qu'infirmer un rejet).
    private static final double W_TOUCHES = 1.0;
    private static final double W_VOLUME = 0.5;
    private static final double W_RECENCY = 1.0;

    // Demi-vie (en nombre de bougies) de la pondération de récence : un rejet vieux de
    // RECENCY_HALF_LIFE bougies pèse moitié moins qu'un rejet tout juste survenu.
    private static final double RECENCY_HALF_LIFE = 50.0;

    // Constante de saturation de la normalisation strength = raw / (raw + K) : garde le score
    // dans [0,1) sans borne supérieure arbitraire sur le score brut.
    private static final double STRENGTH_SATURATION = 5.0;

    @Override
    public IndicatorType getType() {
        return IndicatorType.REJECTION_ZONE;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        int lookback = intParam(parameters, P_LOOKBACK, 0);
        int atrPeriod = intParam(parameters, P_ATR_PERIOD, 0);
        // Fenêtre de scan (lookback) + marge de warmup pour la convergence du lissage de Wilder
        // de l'ATR sous-jacent (cf. AtrIndicator), comme demandé explicitement par l'item J.
        return lookback + atrPeriod;
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(P_K1, P_K2, P_P, P_LOOKBACK, P_CLUSTER_DISTANCE, P_ATR_PERIOD);
    }

    @Override
    public List<IndicatorDependency> getDependencies(IndicatorParameters parameters) {
        return List.of(
                new IndicatorDependency(
                        K_ATR,
                        new IndicatorParameters(
                                IndicatorType.ATR,
                                Map.of(AtrIndicator.P_PERIOD_NAME, parameters.getNumeric(P_ATR_PERIOD)),
                                Map.of(),
                                Map.of(),
                                null
                        )
                )
        );
    }

    @Override
    public IndicatorResult compute(
            IndicatorContext context,
            IndicatorParameters parameters
    ) {
        IndicatorSnapshot atrSnapshot = context.dependencies().get(K_ATR);
        if (atrSnapshot == null || atrSnapshot.getResult() == null || !atrSnapshot.getResult().isValid()
                || atrSnapshot.getResult().getValue() == null) {
            logger.error("Invalid dependency : ATR");
            return IndicatorResult.invalid();
        }
        double atr = atrSnapshot.getResult().getValue();
        if (atr <= 0) {
            logger.error("Invalid dependency : ATR is zero or negative, cannot detect rejections");
            return IndicatorResult.invalid();
        }

        double k1 = parameters.getNumeric(P_K1);
        double k2 = parameters.getNumeric(P_K2);
        double p = parameters.getNumeric(P_P);
        int lookback = intParam(parameters, P_LOOKBACK, 0);
        double clusterDistance = parameters.getNumeric(P_CLUSTER_DISTANCE) * atr;

        MarketDataset series = context.marketDataset();
        List<MarketData> data = series.getMarketDatas();

        if (lookback <= 0 || data.size() < lookback) {
            logger.error("Invalid context : MarketData size too short for RejectionZone lookback={}", lookback);
            return IndicatorResult.invalid();
        }

        List<MarketData> window = data.subList(data.size() - lookback, data.size());

        List<Rejection> bullish = new ArrayList<>();
        List<Rejection> bearish = new ArrayList<>();

        int n = window.size();
        for (int i = 0; i < n; i++) {
            MarketData candle = window.get(i);
            if (candle.getOpen() == null || candle.getHigh() == null
                    || candle.getLow() == null || candle.getClose() == null) {
                logger.error("Invalid context : open/high/low/close required for RejectionZone");
                return IndicatorResult.invalid();
            }

            double open = candle.getOpen().doubleValue();
            double high = candle.getHigh().doubleValue();
            double low = candle.getLow().doubleValue();
            double close = candle.getClose().doubleValue();
            double volume = candle.getVolume() != null ? candle.getVolume().doubleValue() : 0.0;

            double range = high - low;
            if (range <= 0) {
                continue; // bougie plate, ni rejet haussier ni baissier possible
            }

            double body = Math.abs(close - open);
            double lowerWick = Math.min(open, close) - low;
            double upperWick = high - Math.max(open, close);

            // age = 0 pour la bougie la plus récente de la fenêtre, croissant avec l'ancienneté.
            int age = n - 1 - i;

            if (lowerWick > k1 * body && lowerWick > k2 * atr && close > low + p * range) {
                bullish.add(new Rejection(low, age, volume));
            }
            if (upperWick > k1 * body && upperWick > k2 * atr && close < high - p * range) {
                bearish.add(new Rejection(high, age, volume));
            }
        }

        double windowAvgVolume = window.stream()
                .filter(c -> c.getVolume() != null)
                .mapToDouble(c -> c.getVolume().doubleValue())
                .average()
                .orElse(0.0);

        List<Zone> supportZones = clusterZones(bullish, clusterDistance, windowAvgVolume);
        List<Zone> resistanceZones = clusterZones(bearish, clusterDistance, windowAvgVolume);

        double currentPrice = window.get(n - 1).getClose().doubleValue();
        Zone nearestSupport = nearest(supportZones, currentPrice);
        Zone nearestResistance = nearest(resistanceZones, currentPrice);

        Map<String, Double> values = new HashMap<>();
        if (nearestSupport != null) {
            values.put(V_NEAREST_SUPPORT_PRICE, nearestSupport.level);
            values.put(V_NEAREST_SUPPORT_STRENGTH, nearestSupport.strength);
            values.put(V_NEAREST_SUPPORT_TOUCHES, (double) nearestSupport.touches);
        }
        if (nearestResistance != null) {
            values.put(V_NEAREST_RESISTANCE_PRICE, nearestResistance.level);
            values.put(V_NEAREST_RESISTANCE_STRENGTH, nearestResistance.strength);
            values.put(V_NEAREST_RESISTANCE_TOUCHES, (double) nearestResistance.touches);
        }

        logger.debug("{} indicator on TF {} returns support={}, resistance={}",
                getType(), series.getTimeFrame(), nearestSupport, nearestResistance);

        return IndicatorResult.builder()
                .valid(true)
                .values(values)
                .build();
    }

    /**
     * Regroupe des rejets candidats en zones : trie par niveau de prix testé puis fusionne les
     * points consécutifs dont l'écart est <= {@code clusterDistance} (chaînage). Isolé de
     * {@link #compute} pour être testable indépendamment sur des jeux de points synthétiques.
     */
    private static List<Zone> clusterZones(List<Rejection> rejections, double clusterDistance, double windowAvgVolume) {
        if (rejections.isEmpty()) {
            return List.of();
        }

        List<Rejection> sorted = new ArrayList<>(rejections);
        sorted.sort(Comparator.comparingDouble(Rejection::level));

        List<Zone> zones = new ArrayList<>();
        List<Rejection> current = new ArrayList<>();
        current.add(sorted.getFirst());

        for (int i = 1; i < sorted.size(); i++) {
            Rejection r = sorted.get(i);
            Rejection last = current.getLast();
            if (r.level() - last.level() <= clusterDistance) {
                current.add(r);
            } else {
                zones.add(buildZone(current, windowAvgVolume));
                current = new ArrayList<>();
                current.add(r);
            }
        }
        zones.add(buildZone(current, windowAvgVolume));
        return zones;
    }

    private static Zone buildZone(List<Rejection> members, double windowAvgVolume) {
        int touches = members.size();

        double levelSum = 0;
        double volumeSum = 0;
        double recencySum = 0;
        for (Rejection r : members) {
            levelSum += r.level();
            volumeSum += r.volume();
            recencySum += Math.pow(0.5, r.age() / RECENCY_HALF_LIFE);
        }
        double level = levelSum / touches;

        // Volume relatif de la zone par rapport au volume moyen de toute la fenêtre de lookback :
        // ~1.0 = activité normale, > 1.0 = rejet confirmé par un volume élevé, < 1.0 = volume
        // anémique (pèse alors moins dans le score). Garde-fou epsilon pour éviter une division
        // par zéro quand aucun volume n'est disponible dans le contexte.
        double avgZoneVolume = volumeSum / touches;
        double volumeScore = windowAvgVolume > 1e-9 ? avgZoneVolume / windowAvgVolume : 0.0;

        double raw = W_TOUCHES * touches + W_VOLUME * volumeScore + W_RECENCY * recencySum;
        double strength = raw / (raw + STRENGTH_SATURATION);

        return new Zone(level, touches, strength);
    }

    private static Zone nearest(List<Zone> zones, double price) {
        Zone best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Zone zone : zones) {
            double distance = Math.abs(zone.level - price);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = zone;
            }
        }
        return best;
    }

    private static int intParam(IndicatorParameters parameters, String key, int fallback) {
        Double value = parameters.getNumeric(key);
        return value != null ? value.intValue() : fallback;
    }

    private record Rejection(double level, int age, double volume) {}

    private static final class Zone {
        final double level;
        final int touches;
        final double strength;

        Zone(double level, int touches, double strength) {
            this.level = level;
            this.touches = touches;
            this.strength = strength;
        }

        @Override
        public String toString() {
            return "Zone{level=" + level + ", touches=" + touches + ", strength=" + strength + '}';
        }
    }
}
