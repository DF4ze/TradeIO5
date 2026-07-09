package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.OrderBookSnapshot;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.BinanceOrderBookApiClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;

/**
 * Carnet d'ordres Binance ("market", sans levier) — étude "indicateurs-macro-externes" §6a.
 * <p>
 * Rangé dans {@code indicator/impl/} plutôt que {@code indicator/external/} : le patron
 * {@code external/} du projet désigne les indicateurs qui passent par
 * {@code AbstractExternalIndicator}/une {@code ApiCredentialDTO} résolue (Fear&amp;Greed, DefiLlama,
 * Coinalyze) — ici, l'appel Binance public ne nécessite aucune credential, exactement comme les
 * klines déjà servies par {@code BinanceMarketDataApiClient} (qui vit lui aussi hors de
 * {@code external/}). Pas de warmup sur bougies ({@code getRequiredData() == 0}) : lecture externe
 * ponctuelle, comme tous les indicateurs de ce lot.
 * <p>
 * Scope volontairement limité (étude §6a) : une lecture directe du carnet (volumes bid/ask sur une
 * bande de prix autour du prix courant + déséquilibre), pas un algorithme de détection de "zones de
 * liquidité" (traité plus tard, Lot 3).
 */
@Component
public class OrderBookIndicator implements Indicator {

    public static final String P_PRICE_BAND_PERCENT = "priceBandPercent";
    public static final double DEFAULT_PRICE_BAND_PERCENT = 1.0;

    public static final String P_LIMIT = "limit";
    public static final int DEFAULT_LIMIT = 100;

    public static final String V_BID_VOLUME = "bidVolume";
    public static final String V_ASK_VOLUME = "askVolume";
    public static final String V_IMBALANCE = "imbalance";

    private final Logger logger = LoggerFactory.getLogger(OrderBookIndicator.class);

    private final BinanceOrderBookApiClient client;

    public OrderBookIndicator(BinanceOrderBookApiClient client) {
        this.client = client;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.ORDER_BOOK;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        return 0;
    }

    @Override
    public List<String> getParametersNames() {
        // priceBandPercent/limit restent optionnels (défauts DEFAULT_PRICE_BAND_PERCENT/DEFAULT_LIMIT) ;
        // aucune credential nécessaire (endpoint public Binance) : aucun paramètre strictement requis.
        return List.of();
    }

    @Override
    public IndicatorResult compute(IndicatorContext context, IndicatorParameters parameters) {
        String symbol = context.symbol();
        if (symbol == null) {
            logger.warn("{} : aucun symbole dans le contexte, indicateur invalid", getType());
            return IndicatorResult.invalid();
        }

        Double bandParam = parameters != null ? parameters.getNumeric(P_PRICE_BAND_PERCENT) : null;
        double bandPercent = bandParam != null ? bandParam : DEFAULT_PRICE_BAND_PERCENT;

        Double limitParam = parameters != null ? parameters.getNumeric(P_LIMIT) : null;
        int limit = limitParam != null ? limitParam.intValue() : DEFAULT_LIMIT;

        OrderBookSnapshot snapshot = client.getOrderBook(symbol, limit);
        Map<String, Double> volumes = computeVolumes(snapshot, bandPercent);

        if (volumes == null) {
            return IndicatorResult.invalid();
        }

        logger.debug("{} : {} (band={}%) => bidVolume={}, askVolume={}, imbalance={}",
                getType(), symbol, bandPercent,
                volumes.get(V_BID_VOLUME), volumes.get(V_ASK_VOLUME), volumes.get(V_IMBALANCE));

        return IndicatorResult.builder()
                .valid(true)
                .value(volumes.get(V_IMBALANCE))
                .values(volumes)
                .build();
    }

    /**
     * Calcule {@code bidVolume}/{@code askVolume}/{@code imbalance} à partir du carnet brut.
     * Prix courant approximé par le mid-price ({@code (bestBid + bestAsk) / 2}) : le carnet
     * lui-même ne porte pas de notion de "dernier prix tradé". Isolée de l'appel réseau pour être
     * testable en unitaire.
     *
     * @return {@code null} si le carnet est vide/absent (pas d'exception, résultat traité comme
     * invalid par l'appelant).
     */
    static Map<String, Double> computeVolumes(OrderBookSnapshot snapshot, double bandPercent) {
        if (snapshot == null || snapshot.bids() == null || snapshot.asks() == null
                || snapshot.bids().isEmpty() || snapshot.asks().isEmpty()) {
            return null;
        }

        BigDecimal bestBid = snapshot.bids().getFirst().price();
        BigDecimal bestAsk = snapshot.asks().getFirst().price();
        BigDecimal mid = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
        BigDecimal band = mid.multiply(BigDecimal.valueOf(bandPercent / 100.0));
        BigDecimal lowerBound = mid.subtract(band);
        BigDecimal upperBound = mid.add(band);

        double bidVolume = snapshot.bids().stream()
                .filter(level -> level.price().compareTo(lowerBound) >= 0)
                .map(OrderBookSnapshot.OrderBookLevel::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue();

        double askVolume = snapshot.asks().stream()
                .filter(level -> level.price().compareTo(upperBound) <= 0)
                .map(OrderBookSnapshot.OrderBookLevel::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .doubleValue();

        double totalVolume = bidVolume + askVolume;
        double imbalance = totalVolume == 0 ? 0.0 : (bidVolume - askVolume) / totalVolume;

        return Map.of(
                V_BID_VOLUME, bidVolume,
                V_ASK_VOLUME, askVolume,
                V_IMBALANCE, imbalance
        );
    }
}
