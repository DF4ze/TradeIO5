package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Exchange simulé et scriptable pour les tests d'exécution (aucun réseau). Comportement par défaut : IOC entièrement rempli
 * au plafond, frais de 0,1 % prélevés dans l'actif reçu (base à l'achat, cotation à la vente). Chaque appel est journalisé.
 */
public class FakeSpotExchange implements SpotOrderPort {

    public enum Mode {
        FILL_ALL, PARTIAL, NO_FILL, REJECT, CREDENTIAL_REJECTED,
        /** L'exchange accepte et remplit, mais la réponse au POST est perdue (timeout). */
        TIMEOUT_AFTER_ACCEPT,
        /** La requête n'est jamais arrivée (timeout). */
        TIMEOUT_NOT_ACCEPTED,
        /** Ordre accepté mais jamais terminal (reste LIVE). */
        STAY_LIVE
    }

    private record Order(OrderRequest request, String ordId, Mode mode, BigDecimal filledSz) {
    }

    public final List<String> calls = new ArrayList<>();
    public final List<OrderRequest> submitted = new ArrayList<>();
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private Mode mode = Mode.FILL_ALL;
    private BigDecimal partialRatio = new BigDecimal("0.5");
    private BigDecimal feeRate = new BigDecimal("0.001");
    private BigDecimal fillPxFactor = BigDecimal.ONE;
    private String rejectCode = "51008";
    private int hiddenQueries;
    private Consumer<OrderRequest> onSubmit = r -> { };
    private long seq;

    public FakeSpotExchange mode(Mode m) { this.mode = m; return this; }
    public FakeSpotExchange partialRatio(String r) { this.partialRatio = new BigDecimal(r); return this; }
    public FakeSpotExchange feeRate(String r) { this.feeRate = new BigDecimal(r); return this; }
    /** Prix de remplissage = plafond × facteur (ex. 0,999 pour simuler une amélioration). */
    public FakeSpotExchange fillPxFactor(String f) { this.fillPxFactor = new BigDecimal(f); return this; }
    public FakeSpotExchange rejectCode(String c) { this.rejectCode = c; return this; }
    /** Les {@code n} premières lectures d'état répondent « introuvable ». */
    public FakeSpotExchange hideQueries(int n) { this.hiddenQueries = n; return this; }
    public FakeSpotExchange onSubmit(Consumer<OrderRequest> hook) { this.onSubmit = hook; return this; }

    public int submitCount() {
        return submitted.size();
    }

    public int totalCalls() {
        return calls.size();
    }

    @Override
    public OrderAck submit(ApiCredential credential, OrderRequest request) {
        calls.add("submit:" + request.clOrdId());
        onSubmit.accept(request);
        if (orders.containsKey(request.clOrdId())) {
            throw new OrderRejectedException(OrderRejectedException.DUPLICATE_CL_ORD_ID, "duplicate clOrdId");
        }
        submitted.add(request);
        switch (mode) {
            case REJECT -> throw new OrderRejectedException(rejectCode, "rejected");
            case CREDENTIAL_REJECTED -> throw new TradeCredentialRejectedException("clé refusée");
            case TIMEOUT_NOT_ACCEPTED -> throw new ExchangeUnavailableException("timeout");
            default -> { }
        }
        String ordId = "ORD" + (++seq);
        BigDecimal filled = switch (mode) {
            case FILL_ALL, TIMEOUT_AFTER_ACCEPT -> request.sz();
            case PARTIAL -> request.sz().multiply(partialRatio);
            default -> BigDecimal.ZERO;
        };
        orders.put(request.clOrdId(), new Order(request, ordId, mode, filled));
        if (mode == Mode.TIMEOUT_AFTER_ACCEPT) {
            throw new ExchangeUnavailableException("timeout après envoi");
        }
        return new OrderAck(request.clOrdId(), ordId);
    }

    @Override
    public OrderState query(ApiCredential credential, String instId, String clOrdId) {
        calls.add("query:" + clOrdId);
        if (hiddenQueries > 0) {
            hiddenQueries--;
            throw new OrderNotFoundException("pas encore visible");
        }
        Order o = orders.get(clOrdId);
        if (o == null) {
            throw new OrderNotFoundException("inconnu");
        }
        if (o.mode() == Mode.STAY_LIVE) {
            return new OrderState(clOrdId, o.ordId(), OrderPhase.LIVE, BigDecimal.ZERO, null);
        }
        OrderPhase phase = o.filledSz().compareTo(o.request().sz()) >= 0 ? OrderPhase.FILLED : OrderPhase.CANCELED;
        return new OrderState(clOrdId, o.ordId(), phase, o.filledSz(), o.filledSz().signum() > 0 ? px(o) : null);
    }

    @Override
    public void cancel(ApiCredential credential, String instId, String clOrdId) {
        calls.add("cancel:" + clOrdId);
    }

    @Override
    public List<Fill> fills(ApiCredential credential, String instId, String ordId) {
        calls.add("fills:" + ordId);
        return orders.values().stream().filter(o -> o.ordId().equals(ordId) && o.filledSz().signum() > 0).map(o -> {
            boolean buy = o.request().side() == StepSide.BUY;
            BigDecimal px = px(o);
            String[] pair = o.request().instId().split("-");
            BigDecimal fee = buy ? o.filledSz().multiply(feeRate) : o.filledSz().multiply(px).multiply(feeRate);
            return new Fill("T" + o.ordId(), o.ordId(), o.request().clOrdId(), o.request().instId(), o.request().side(), px,
                    o.filledSz(), fee, buy ? pair[0] : pair[1], Instant.parse("2026-10-10T00:00:00Z"));
        }).toList();
    }

    private BigDecimal px(Order o) {
        return o.request().px().multiply(fillPxFactor);
    }
}
