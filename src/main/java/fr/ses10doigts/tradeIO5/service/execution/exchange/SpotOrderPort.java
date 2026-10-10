package fr.ses10doigts.tradeIO5.service.execution.exchange;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;

import java.util.List;

/**
 * Port d'ordres spot : <b>le seul endroit du code où des opérations d'ordre existent</b>. Aucun bean n'implémente ce port
 * tant que {@code tradeio.execution.mode=LIVE} et {@code tradeio.execution.live-unlocked=true} ne sont pas tous deux posés.
 * Seul {@code service.execution.run} l'appelle (garde d'architecture). La credential passée est la copie déchiffrée de la
 * clé TRADE de l'utilisateur.
 */
public interface SpotOrderPort {

    /**
     * @throws OrderRejectedException rejet métier certain
     * @throws TradeCredentialRejectedException clé Trade refusée
     * @throws ExchangeUnavailableException issue inconnue (l'ordre a pu partir)
     */
    OrderAck submit(ApiCredential credential, OrderRequest request);

    /** @throws OrderNotFoundException aucun ordre avec ce {@code clOrdId} */
    OrderState query(ApiCredential credential, String instId, String clOrdId);

    void cancel(ApiCredential credential, String instId, String clOrdId);

    /** Remplissages d'un ordre ({@code ordId} issu de {@link OrderState}). */
    List<Fill> fills(ApiCredential credential, String instId, String ordId);
}
