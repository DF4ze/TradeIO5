package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Rejeu chronologique des trades d'une position (un actif sur un wallet) en <b>prix moyen pondéré</b> (PRU) : un achat augmente
 * quantité et coût ; une vente réalise {@code quantité x (prix − PRU)} et laisse le PRU inchangé. Frais inclus : ajoutés au coût
 * à l'achat, déduits du produit à la vente, ou retirés de la quantité reçue quand ils sont prélevés dans l'actif acheté.
 * Une vente supérieure à la quantité connue est plafonnée et marque le résultat {@code incomplete} (dépôt ou historique manquant).
 */
final class PnlLedger {

    /** Stablecoins valorisés au nominal (1 USD). */
    static final Set<String> STABLES = Set.of("USDC", "USDT", "DAI", "FDUSD", "TUSD", "PYUSD", "USDE", "USD", "ZUSD");

    private static final double EPSILON = 1e-12;

    private PnlLedger() {
    }

    /** Un trade en dollars : {@code feeBase} = frais prélevés dans l'actif acheté (quantité) ; {@code feeUsd} = autres frais en dollars. */
    record Fill(LocalDateTime at, boolean isBuy, double qty, double priceUsd, double feeBase, double feeUsd) {
    }

    /** Gain réalisé par une vente : {@code amount} = produit net − coût sorti ; {@code cost} = coût sorti. */
    record Realization(LocalDateTime at, double amount, double cost) {
    }

    /** {@code volume} = achats + ventes en dollars ; {@code bought} = achats cumulés en dollars (capital investi), frais exclus. */
    record Result(double quantity, double cost, double realized, double soldCost, double fees, double volume, double bought, int sells,
                  boolean incomplete, List<Realization> realizations) {

        /** PRU de la quantité restante ; nul sans position. */
        Double avgPrice() {
            return quantity > EPSILON ? cost / quantity : null;
        }
    }

    /**
     * Fill d'un trade. {@code rate} = valeur en dollars d'une unité de la devise de cotation ; {@code feeRateUsd} = valeur en dollars
     * d'une unité de la devise des frais (nul si inconnue ⇒ frais ignorés, sauf prélevés dans l'actif lui-même).
     */
    static Fill fill(Transaction t, String asset, double rate, Double feeRateUsd) {
        boolean buy = t.getSide() == TradeSide.BUY;
        double priceUsd = t.getPrice().doubleValue() * rate;
        double fee = t.getFee() == null ? 0 : t.getFee().doubleValue();
        double feeBase = 0, feeUsd = 0;
        if (fee > 0 && t.getFeeCurrency() != null) {
            if (t.getFeeCurrency().equalsIgnoreCase(asset)) {
                if (buy) {
                    feeBase = fee;
                } else {
                    feeUsd = fee * priceUsd;
                }
            } else if (feeRateUsd != null) {
                feeUsd = fee * feeRateUsd;
            }
        }
        return new Fill(t.getTimestamp(), buy, t.getQuantity().doubleValue(), priceUsd, feeBase, feeUsd);
    }

    static Result replay(List<Fill> fills) {
        List<Fill> sorted = new ArrayList<>(fills);
        sorted.sort(Comparator.comparing(Fill::at));
        double held = 0, cost = 0, realized = 0, soldCost = 0, fees = 0, volume = 0, bought = 0;
        int sells = 0;
        boolean incomplete = false;
        List<Realization> realizations = new ArrayList<>();
        for (Fill f : sorted) {
            fees += f.feeUsd() + f.feeBase() * f.priceUsd();
            volume += f.qty() * f.priceUsd();
            if (f.isBuy()) {
                bought += f.qty() * f.priceUsd();
                cost += f.qty() * f.priceUsd() + f.feeUsd();
                held += f.qty() - f.feeBase();
                continue;
            }
            double sold = Math.min(f.qty(), held);
            if (f.qty() > held + EPSILON) {
                incomplete = true;
            }
            if (sold <= EPSILON) {
                continue;
            }
            double avg = cost / held;
            double share = sold / f.qty();
            double costOut = sold * avg;
            double gain = sold * f.priceUsd() - f.feeUsd() * share - costOut;
            realized += gain;
            soldCost += costOut;
            sells++;
            realizations.add(new Realization(f.at(), gain, costOut));
            held -= sold;
            cost -= costOut;
            if (held <= EPSILON) {
                held = 0;
                cost = 0;
            }
        }
        return new Result(held, cost, realized, soldCost, fees, volume, bought, sells, incomplete, realizations);
    }
}
