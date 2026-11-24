package fr.ses10doigts.tradeIO5.service.market.eligibility;

/**
 * Raison de refus d'un actif chez un provider (première règle violée, dans l'ordre de déclaration). {@code CATALOG_UNAVAILABLE} : catalogue absent
 * et exchange injoignable (ni éligible ni inéligible : à réessayer). {@code VOLUME_UNKNOWN} : jamais renvoyé tant que
 * {@link AssetEligibilityDefaults#UNKNOWN_VOLUME_ELIGIBLE} est vrai. {@code HISTORY_UNAVAILABLE} : lecture D1 impossible (réseau), à réessayer.
 */
public enum IneligibilityReason {
    FIAT("monnaie fiat"),
    STABLECOIN("stablecoin"),
    LEVERAGED_TOKEN("token à levier ou dérivé"),
    EARN_OR_WRAPPED("token earn / staking / wrapped"),
    BLOCKLISTED("actif en liste noire"),
    CATALOG_UNAVAILABLE("catalogue de l'exchange indisponible, réessayez plus tard"),
    NO_BUY_ROUTE("aucune route d'achat depuis USDC chez cet exchange"),
    VOLUME_TOO_LOW("volume 24 h inférieur au minimum"),
    VOLUME_UNKNOWN("volume 24 h inconnu"),
    HISTORY_TOO_SHORT("historique D1 inférieur à " + AssetEligibilityDefaults.MIN_D1_DAYS + " jours"),
    HISTORY_UNAVAILABLE("historique D1 illisible, réessayez plus tard");

    private final String label;

    IneligibilityReason(String label) {
        this.label = label;
    }

    /** Libellé lisible (français) de la raison. */
    public String label() {
        return label;
    }
}
