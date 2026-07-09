package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.LiquidationHistoryResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze.CoinalyzeClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze.CoinalyzeSymbolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Historique de liquidations (Coinalyze), étude "indicateurs-macro-externes" §6b : il n'existe pas
 * d'endpoint "liquidation courante" côté Coinalyze (contrairement à OI/funding), seulement un
 * historique — cet indicateur "maintenant" interroge donc {@code /liquidation-history} sur une
 * fenêtre glissante récente ({@link #P_WINDOW_HOURS}, défaut {@link #DEFAULT_WINDOW_HOURS}h) et
 * somme les volumes long/short sur cette fenêtre.
 */
@Component
public class LiquidationsIndicator implements Indicator {

    public static final String P_WINDOW_HOURS = "windowHours";
    public static final double DEFAULT_WINDOW_HOURS = 24.0;

    public static final String V_LONG = "long";
    public static final String V_SHORT = "short";
    public static final String V_TOTAL = "total";

    private final Logger logger = LoggerFactory.getLogger(LiquidationsIndicator.class);

    private final CoinalyzeClient client;
    private final CoinalyzeSymbolResolver symbolResolver;

    public LiquidationsIndicator(CoinalyzeClient client, CoinalyzeSymbolResolver symbolResolver) {
        this.client = client;
        this.symbolResolver = symbolResolver;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.LIQUIDATIONS;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        return 0;
    }

    @Override
    public List<String> getParametersNames() {
        // windowHours reste optionnel (retombe sur DEFAULT_WINDOW_HOURS si absent) : seul le
        // credential est un prérequis strict, sur le même principe que les autres indicateurs
        // externes de ce lot.
        return List.of(AbstractExternalIndicator.P_CREDENTIAL);
    }

    @Override
    public IndicatorResult compute(IndicatorContext context, IndicatorParameters parameters) {
        if (context.symbol() == null) {
            logger.warn("{} : aucun symbole dans le contexte, indicateur invalid", getType());
            return IndicatorResult.invalid();
        }

        ApiCredentialDTO credential = parameters.getCredential();

        String coinalyzeSymbol = symbolResolver.resolve(credential, context.symbol());
        if (coinalyzeSymbol == null) {
            return IndicatorResult.invalid();
        }

        Double windowHoursParam = parameters.getNumeric(P_WINDOW_HOURS);
        double windowHours = windowHoursParam != null ? windowHoursParam : DEFAULT_WINDOW_HOURS;
        if (windowHours <= 0) {
            logger.error("{} : windowHours invalide ({})", getType(), windowHours);
            return IndicatorResult.invalid();
        }

        Instant to = context.clock().now();
        Instant from = to.minus(Duration.ofMinutes(Math.round(windowHours * 60)));

        LiquidationHistoryResponse response = client.fetchLiquidations(credential, coinalyzeSymbol, from, to, "1hour");

        if (!response.isValid() || response.getEntries() == null || response.getEntries().isEmpty()) {
            return IndicatorResult.invalid();
        }

        LiquidationHistoryResponse.Entry entry = response.getEntries().getFirst();
        Map<String, Double> totals = sumHistory(entry.getHistory());
        if (totals == null) {
            return IndicatorResult.invalid();
        }

        logger.debug("{} : fenêtre {}h sur {} => long={}, short={}, total={}",
                getType(), windowHours, coinalyzeSymbol,
                totals.get(V_LONG), totals.get(V_SHORT), totals.get(V_TOTAL));

        return IndicatorResult.builder()
                .valid(true)
                .values(totals)
                .build();
    }

    /**
     * Somme {@code l}/{@code s} sur tous les points d'historique fournis. Isolée pour être
     * testable en unitaire sans appel réseau.
     */
    static Map<String, Double> sumHistory(List<LiquidationHistoryResponse.HistoryPoint> history) {
        if (history == null) {
            return null;
        }

        double longTotal = 0;
        double shortTotal = 0;
        for (LiquidationHistoryResponse.HistoryPoint point : history) {
            if (point == null) {
                continue;
            }
            longTotal += point.getL();
            shortTotal += point.getS();
        }

        return Map.of(
                V_LONG, longTotal,
                V_SHORT, shortTotal,
                V_TOTAL, longTotal + shortTotal
        );
    }
}
