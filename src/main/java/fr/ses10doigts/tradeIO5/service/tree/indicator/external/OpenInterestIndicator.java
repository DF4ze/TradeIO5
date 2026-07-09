package fr.ses10doigts.tradeIO5.service.tree.indicator.external;

import fr.ses10doigts.tradeIO5.model.dto.provider.web.ApiCredentialDTO;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.external.OpenInterestHistoryResponse;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze.CoinalyzeClient;
import fr.ses10doigts.tradeIO5.service.tree.indicator.external.coinalyze.CoinalyzeSymbolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Open Interest (Coinalyze, agrégé sur ~25 exchanges), étude "indicateurs-macro-externes" §9.
 * Contrairement à Fear&amp;Greed/Stablecoin Market Cap, cette valeur est <b>par symbole</b> :
 * utilise {@code context.symbol()} pour résoudre le code de marché Coinalyze avant l'appel.
 * <p>
 * <b>Écart introduit au Lot 2 (item H)</b> : le critère "chute brutale d'OI" (signature d'une
 * cascade de liquidations, utilisé par {@code MovementQualificationStrategy}) ne peut pas se lire
 * sur une seule valeur instantanée. Cet indicateur interroge donc désormais
 * {@code /open-interest-history} (2 points : période courante + période précédente) plutôt que
 * {@code /open-interest} (valeur unique livrée au Lot 1), et expose
 * {@code IndicatorResult.values = Map.of("current", ..., "previous", ...)} — <b>contrat changé par
 * rapport au Lot 1</b> ({@code IndicatorResult.value} simple), documenté ici comme demandé par le
 * prompt d'implémentation Lot 2 item H. Sans consommateur du contrat précédent au moment de ce
 * changement (Lot 1 ne branchait cet indicateur dans aucune Strategy), ce n'est pas un breaking
 * change pratique.
 */
@Component
public class OpenInterestIndicator implements Indicator {

    public static final String P_INTERVAL_HOURS = "intervalHours";
    public static final double DEFAULT_INTERVAL_HOURS = 1.0;

    public static final String V_CURRENT = "current";
    public static final String V_PREVIOUS = "previous";

    private final Logger logger = LoggerFactory.getLogger(OpenInterestIndicator.class);

    private final CoinalyzeClient client;
    private final CoinalyzeSymbolResolver symbolResolver;

    public OpenInterestIndicator(CoinalyzeClient client, CoinalyzeSymbolResolver symbolResolver) {
        this.client = client;
        this.symbolResolver = symbolResolver;
    }

    @Override
    public IndicatorType getType() {
        return IndicatorType.OPEN_INTEREST;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        return 0;
    }

    @Override
    public List<String> getParametersNames() {
        // intervalHours reste optionnel (retombe sur DEFAULT_INTERVAL_HOURS si absent), même
        // principe que LiquidationsIndicator.P_WINDOW_HOURS.
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

        Double intervalHoursParam = parameters.getNumeric(P_INTERVAL_HOURS);
        double intervalHours = intervalHoursParam != null ? intervalHoursParam : DEFAULT_INTERVAL_HOURS;
        if (intervalHours <= 0) {
            logger.error("{} : intervalHours invalide ({})", getType(), intervalHours);
            return IndicatorResult.invalid();
        }

        // 2 points minimum (courant + précédent) : fenêtre de 2 intervalles, alignée sur
        // l'intervalle demandé (défaut 1h -> interval Coinalyze "1hour").
        Instant to = context.clock().now();
        Instant from = to.minus(Duration.ofMinutes(Math.round(intervalHours * 2 * 60)));
        String coinalyzeInterval = toCoinalyzeInterval(intervalHours);

        OpenInterestHistoryResponse response =
                client.fetchOpenInterestHistory(credential, coinalyzeSymbol, from, to, coinalyzeInterval);

        if (!response.isValid() || response.getEntries() == null || response.getEntries().isEmpty()) {
            return IndicatorResult.invalid();
        }

        Map<String, Double> currentPrevious = extractCurrentPrevious(response.getEntries().getFirst().getHistory());
        if (currentPrevious == null) {
            logger.warn("{} : historique insuffisant pour {} (2 points minimum requis)", getType(), coinalyzeSymbol);
            return IndicatorResult.invalid();
        }

        return IndicatorResult.builder()
                .valid(true)
                .values(currentPrevious)
                .build();
    }

    /**
     * Isolé de l'appel réseau pour être testable en unitaire sans réseau (patron
     * {@code LiquidationsIndicator.sumHistory}). Prend les 2 derniers points de l'historique
     * (le plus récent = "current", le précédent = "previous") ; retourne {@code null} si moins de
     * 2 points valides sont disponibles.
     */
    static Map<String, Double> extractCurrentPrevious(List<OpenInterestHistoryResponse.HistoryPoint> history) {
        if (history == null || history.size() < 2) {
            return null;
        }

        List<OpenInterestHistoryResponse.HistoryPoint> sorted = history.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(OpenInterestHistoryResponse.HistoryPoint::getT))
                .toList();

        if (sorted.size() < 2) {
            return null;
        }

        OpenInterestHistoryResponse.HistoryPoint previous = sorted.get(sorted.size() - 2);
        OpenInterestHistoryResponse.HistoryPoint current = sorted.getLast();

        return Map.of(
                V_CURRENT, current.getC(),
                V_PREVIOUS, previous.getC()
        );
    }

    private static String toCoinalyzeInterval(double intervalHours) {
        // Seul "1hour" est nécessaire pour ce lot (défaut) ; les autres granularités Coinalyze
        // ("5min", "1day", etc.) ne sont pas exposées ici faute de besoin identifié — à étendre si
        // un appelant a besoin d'un intervalle différent.
        if (intervalHours == 1.0) {
            return "1hour";
        }
        if (intervalHours < 1.0) {
            return "5min";
        }
        return "1day";
    }
}
