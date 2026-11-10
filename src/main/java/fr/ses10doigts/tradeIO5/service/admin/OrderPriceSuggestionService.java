package fr.ses10doigts.tradeIO5.service.admin;

import fr.ses10doigts.tradeIO5.model.dto.admin.PriceSuggestionDto;
import fr.ses10doigts.tradeIO5.model.dto.execution.BookResult;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.ProviderRepository;
import fr.ses10doigts.tradeIO5.service.connector.orderbook.OrderBookClient;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Prix limite automatique du testeur d'ordre (lecture seule du carnet public) : BUY = meilleur ask majoré de la tolérance,
 * SELL = meilleur bid minoré. Le tick est appliqué ensuite par la normalisation de {@code OrderTestService}.
 */
@Service
public class OrderPriceSuggestionService {

    /** Tolérances proposées dans l'interface ; la première sert de défaut. */
    public static final List<BigDecimal> TOLERANCES_PCT = List.of(new BigDecimal("0.5"), new BigDecimal("0.25"));

    private final ProviderRepository providers;
    private final Map<WebProviderCode, OrderBookClient> bookClients;

    public OrderPriceSuggestionService(ProviderRepository providers, List<OrderBookClient> bookClients) {
        this.providers = providers;
        this.bookClients = bookClients.stream().collect(Collectors.toMap(OrderBookClient::getProviderCode, Function.identity()));
    }

    /** @throws IllegalArgumentException exchange inconnu / sans carnet, tolérance non proposée, ou carnet indisponible */
    public PriceSuggestionDto suggest(WebProviderCode provider, String instId, StepSide side, BigDecimal tolerancePct) {
        BigDecimal tol = tolerancePct == null ? TOLERANCES_PCT.getFirst() : tolerancePct;
        if (TOLERANCES_PCT.stream().noneMatch(t -> t.compareTo(tol) == 0)) {
            throw new IllegalArgumentException("Tolérance non proposée : " + tol + " % (valeurs : " + TOLERANCES_PCT + ")");
        }
        if (provider == null || instId == null || instId.isBlank() || side == null) {
            throw new IllegalArgumentException("Prix auto : exchange, paire et sens requis");
        }
        WebProvider webProvider = providers.findByCode(provider)
                .orElseThrow(() -> new IllegalArgumentException("Exchange inconnu : " + provider));
        OrderBookClient client = bookClients.get(provider);
        if (client == null) {
            throw new IllegalArgumentException("Prix auto impossible : pas de carnet public pour " + provider);
        }
        BookResult result;
        try {
            result = client.fetchBook(webProvider, instId.trim().toUpperCase(), ExecutionDefaults.BOOK_DEPTH);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Prix auto impossible : carnet indisponible pour " + instId + " (" + e.getMessage() + ")");
        }
        var levels = side == StepSide.BUY ? result.book().asks() : result.book().bids();
        if (levels == null || levels.isEmpty()) {
            throw new IllegalArgumentException("Prix auto impossible : carnet vide pour " + instId);
        }
        BigDecimal bookPx = levels.getFirst().price();
        BigDecimal factor = BigDecimal.ONE.add(tol.movePointLeft(2).multiply(side == StepSide.BUY ? BigDecimal.ONE : BigDecimal.ONE.negate()));
        BigDecimal px = bookPx.multiply(factor).setScale(bookPx.scale() + 2, RoundingMode.HALF_UP).stripTrailingZeros();
        return new PriceSuggestionDto(px, bookPx, tol, result.estimation().name());
    }
}
