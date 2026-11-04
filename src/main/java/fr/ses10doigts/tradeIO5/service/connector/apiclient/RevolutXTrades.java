package fr.ses10doigts.tradeIO5.service.connector.apiclient;

import com.fasterxml.jackson.databind.JsonNode;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture d'un remplissage Revolut X ({@code ClientTrade} de {@code GET /1.0/orders/fills/{venue_order_id}}) : {@code tid}
 * (identifiant de trade, repris tel quel par l'import et par le client d'ordres pour qu'un même trade ne soit jamais enregistré
 * deux fois), {@code oid}, {@code p}, {@code q}, {@code tdt} (ms), {@code s}, {@code aid} (base), {@code pc} (devise de prix).
 * Un {@code ClientTrade} ne porte <b>aucun frais</b> : le frais total d'un ordre ({@code total_fee} / {@code fee_currency} du
 * détail) est attribué au premier remplissage, les suivants à 0.
 */
public final class RevolutXTrades {

    /** Un remplissage lu ; {@code side} peut être vide (repli sur le sens de l'ordre). */
    public record Trade(String tradeId, String orderId, String base, String quote, String side, BigDecimal price,
                        BigDecimal quantity, Instant time) {
    }

    private RevolutXTrades() {
    }

    /** @throws BalanceUnavailableException remplissage illisible */
    public static List<Trade> parse(JsonNode data) {
        List<Trade> out = new ArrayList<>();
        if (!data.isArray()) {
            return out;
        }
        for (JsonNode row : data) {
            String tid = row.path("tid").asText("");
            if (tid.isBlank()) {
                throw new BalanceUnavailableException("Revolut X : remplissage sans identifiant");
            }
            try {
                out.add(new Trade(tid, row.path("oid").asText(""), row.path("aid").asText("").toUpperCase(),
                        row.path("pc").asText("").toUpperCase(), row.path("s").asText(""), new BigDecimal(row.path("p").asText()),
                        new BigDecimal(row.path("q").asText()), Instant.ofEpochMilli(row.path("tdt").asLong())));
            } catch (NumberFormatException e) {
                throw new BalanceUnavailableException("Revolut X : remplissage illisible (" + tid + ")", e);
            }
        }
        return out;
    }

    /** Frais total de l'ordre (positif), nul si absent. */
    public static BigDecimal totalFee(JsonNode order) {
        String text = order.path("total_fee").asText("");
        if (text.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text).abs();
        } catch (NumberFormatException e) {
            throw new BalanceUnavailableException("Revolut X : total_fee illisible", e);
        }
    }
}
