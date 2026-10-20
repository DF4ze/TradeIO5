package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link ExposureLedger} des wallets d'un utilisateur pour une passe (créé à la première lecture {@code OK} du wallet). Un
 * rejeu du même jour reprend l'action du snapshot d'origine ; si le ledger n'existe pas encore, elle est mémorisée et appliquée à
 * sa création, pour que le lien suivant voie la même exposition qu'au premier passage.
 */
final class ExposureBook {

    private final Map<Long, ExposureLedger> ledgers = new HashMap<>();
    private final Map<Long, Double> pendingDelta = new HashMap<>();

    /** Ledger du wallet, créé depuis {@code reading} s'il est {@code OK} ; {@code null} si absent et lecture inexploitable. */
    ExposureLedger ledgerFor(Long walletId, PortfolioReading reading, Map<String, Double> closes) {
        ExposureLedger existing = ledgers.get(walletId);
        if (existing != null || !reading.isOk()) {
            return existing;
        }
        ExposureLedger created = ExposureLedger.of(reading, closes);
        double delta = pendingDelta.getOrDefault(walletId, 0.0);
        if (delta > 0) {
            created.applyBuy(delta);
        } else if (delta < 0) {
            created.applySell(-delta);
        }
        ledgers.put(walletId, created);
        return created;
    }

    /** Action d'un snapshot rejoué (montant en USD) : met à jour le ledger, ou la mémorise s'il n'existe pas encore. */
    void replay(Long walletId, RainbowLiveAction type, double amountUsd) {
        double delta = switch (type) {
            case BUY -> amountUsd;
            case SELL -> -amountUsd;
            default -> 0.0;
        };
        if (delta == 0.0) {
            return;
        }
        ExposureLedger ledger = ledgers.get(walletId);
        if (ledger == null) {
            pendingDelta.merge(walletId, delta, Double::sum);
        } else if (delta > 0) {
            ledger.applyBuy(delta);
        } else {
            ledger.applySell(-delta);
        }
    }
}
