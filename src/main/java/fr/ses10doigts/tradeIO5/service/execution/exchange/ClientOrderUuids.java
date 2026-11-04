package fr.ses10doigts.tradeIO5.service.execution.exchange;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Identifiant client au format UUID exigé par certains exchanges (One Trading {@code client_id}, Bitvavo {@code clientOrderId}),
 * dérivé de façon déterministe (type 3) du {@code clOrdId} interne : même entrée => même UUID, ce qui permet de relire et
 * d'annuler un ordre sans rien stocker.
 */
final class ClientOrderUuids {

    private static final String SEED = "t5:";

    private ClientOrderUuids() {
    }

    static String of(String clOrdId) {
        return UUID.nameUUIDFromBytes((SEED + clOrdId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
