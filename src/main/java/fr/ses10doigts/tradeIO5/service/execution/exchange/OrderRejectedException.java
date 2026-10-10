package fr.ses10doigts.tradeIO5.service.execution.exchange;

/** Rejet métier certain : l'ordre n'existe pas côté exchange (solde, taille, prix, paire...). {@code code} = code OKX. */
public class OrderRejectedException extends SpotOrderException {

    /** Code OKX « Client order ID duplicate » : l'ordre existe déjà, à relire par {@code clOrdId} (à confirmer sur réponse réelle, étape d). */
    public static final String DUPLICATE_CL_ORD_ID = "51016";

    private final String code;

    public OrderRejectedException(String code, String message) {
        super("Ordre rejeté code=" + code + " : " + message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public boolean duplicateClientOrderId() {
        return DUPLICATE_CL_ORD_ID.equals(code);
    }
}
