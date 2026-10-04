package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * Paramètres globaux (non pilotés par le régime de Trend) du Rainbow DCA ATR : modulation par la
 * distance à l'ATH (pine v2), mode To the moon (pine v3) et constantes de multiplicateur/montant.
 * Cf. {@code tools/pine/rainbow_dca_v4_atr_moon.pine} (source de vérité).
 * <p>
 * ATH : {@code d = 1 - close/ATH ; t = clamp(d/réf, 0, 1)} ; achat = base × (min + (max-min)×t) ;
 * vente = sellFraction × (sellMax + (sellMin-sellMax)×t), fraction finale plafonnée à 1.0. Neutralisée
 * (facteur 1.0) tant que le mode To the moon est actif.
 */
public record RainbowAtrGlobals(
        boolean athOn, double athRefDdBuyPct, double athRefDdSellPct,
        double athBuyMin, double athBuyMax, double athSellMax, double athSellMin,
        boolean moonOn, double moonReservePct, boolean moonReserveRatchet,
        double moonTrailingStopPct, double moonStopSellPct,
        double multX2, double multX1, double multX0_5, double multTriggered, double baseAmount
) {

    /** Valeurs par défaut du pine. */
    public static RainbowAtrGlobals pineDefault() {
        return new RainbowAtrGlobals(true, 60.0, 30.0, 0.5, 2.0, 2.0, 0.5,
                true, 50.0, false, 15.0, 100.0,
                2.0, 1.0, 0.5, 3.0, 1.0);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static final class Builder {
        private boolean athOn, moonOn, ratchet;
        private double refBuy, refSell, buyMin, buyMax, sellMax, sellMin, reserve, moonTrail, moonStop;
        private final double x2, x1, x05, trig, base;

        private Builder(RainbowAtrGlobals g) {
            athOn = g.athOn; refBuy = g.athRefDdBuyPct; refSell = g.athRefDdSellPct;
            buyMin = g.athBuyMin; buyMax = g.athBuyMax; sellMax = g.athSellMax; sellMin = g.athSellMin;
            moonOn = g.moonOn; reserve = g.moonReservePct; ratchet = g.moonReserveRatchet;
            moonTrail = g.moonTrailingStopPct; moonStop = g.moonStopSellPct;
            x2 = g.multX2; x1 = g.multX1; x05 = g.multX0_5; trig = g.multTriggered; base = g.baseAmount;
        }

        public Builder set(String name, Object v) {
            switch (name) {
                case "athOn" -> athOn = (Boolean) v;
                case "athRefDdBuyPct" -> refBuy = (Double) v;
                case "athRefDdSellPct" -> refSell = (Double) v;
                case "athBuyMin" -> buyMin = (Double) v;
                case "athBuyMax" -> buyMax = (Double) v;
                case "athSellMax" -> sellMax = (Double) v;
                case "athSellMin" -> sellMin = (Double) v;
                case "moonOn" -> moonOn = (Boolean) v;
                case "moonReservePct" -> reserve = (Double) v;
                case "moonReserveRatchet" -> ratchet = (Boolean) v;
                case "moonTrailingStopPct" -> moonTrail = (Double) v;
                case "moonStopSellPct" -> moonStop = (Double) v;
                default -> throw new IllegalArgumentException("Paramètre global inconnu : " + name);
            }
            return this;
        }

        public RainbowAtrGlobals build() {
            return new RainbowAtrGlobals(athOn, refBuy, refSell, buyMin, buyMax, sellMax, sellMin,
                    moonOn, reserve, ratchet, moonTrail, moonStop, x2, x1, x05, trig, base);
        }
    }
}
