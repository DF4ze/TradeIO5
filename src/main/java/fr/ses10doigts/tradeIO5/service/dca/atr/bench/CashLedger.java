package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

/**
 * Pool de cash USDC d'un (utilisateur, wallet) pour une passe. Les actifs le parcourent par {@code priority} croissante :
 * un achat accepté réserve son montant (tout-ou-rien), le suivant voit le reste. Les ventes ne sont pas créditées le jour
 * même (prudence). Non thread-safe : une instance par passe et par pool.
 */
public final class CashLedger {

    private static final double EPSILON = 1e-9;

    private final double cash;
    private double reserved;

    public CashLedger(double cash) {
        this.cash = cash;
    }

    /** Réserve {@code amount} si le cash restant le couvre en entier ; sinon ne change rien. */
    public boolean tryReserve(double amount) {
        if (amount > remaining() + EPSILON) {
            return false;
        }
        reserved += amount;
        return true;
    }

    /** Réservation sans contrôle (rejeu 23:55 : l'action d'origine a déjà été acceptée). */
    public void forceReserve(double amount) {
        reserved += amount;
    }

    public double reserved() {
        return reserved;
    }

    public double remaining() {
        return Math.max(0.0, cash - reserved);
    }
}
