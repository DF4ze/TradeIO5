package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import java.util.Map;

/**
 * Exposition d'un (utilisateur, wallet) pour une passe, calquée sur {@link CashLedger} : {@code P} = valeur des actifs du
 * périmètre (quantité x clôture D1), {@code V} = {@code P} + cash USDC, constant pendant la passe (un achat déplace du cash vers
 * les actifs, une vente l'inverse). Chaque action acceptée met à jour {@code P} pour le lien suivant. Exposition <b>inconnue</b>
 * (prix manquant pour un actif détenu, {@code V = 0}) : aucune modulation de bande, jamais un 0 silencieux. Non thread-safe.
 */
final class ExposureLedger {

    private static final ExposureLedger UNKNOWN = new ExposureLedger(0.0, 0.0, false);

    private double assetsValue;
    private final double totalValue;
    private final boolean known;

    private ExposureLedger(double assetsValue, double totalValue, boolean known) {
        this.assetsValue = assetsValue;
        this.totalValue = totalValue;
        this.known = known;
    }

    static ExposureLedger unknown() {
        return UNKNOWN;
    }

    /** Valorise la lecture avec les clôtures de la passe ; inconnue si un actif détenu n'a pas de prix ou si {@code V = 0}. */
    static ExposureLedger of(PortfolioReading reading, Map<String, Double> closes) {
        double assets = 0.0;
        for (Map.Entry<String, Double> position : reading.positions().entrySet()) {
            if (position.getValue() <= 0.0) {
                continue;
            }
            Double close = closes.get(position.getKey());
            if (close == null || !(close > 0.0)) {
                return UNKNOWN;
            }
            assets += position.getValue() * close;
        }
        double total = assets + reading.cash();
        return total > 0.0 ? new ExposureLedger(assets, total, true) : UNKNOWN;
    }

    boolean isKnown() {
        return known;
    }

    double assetsValue() {
        return assetsValue;
    }

    double totalValue() {
        return totalValue;
    }

    /** Exposition en % de la valeur du portefeuille. */
    double exposurePct() {
        return assetsValue / totalValue * 100.0;
    }

    void applyBuy(double usd) {
        if (known) {
            assetsValue += usd;
        }
    }

    void applySell(double usd) {
        if (known) {
            assetsValue = Math.max(0.0, assetsValue - usd);
        }
    }
}
