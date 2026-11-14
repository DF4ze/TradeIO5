package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Cœur de dimensionnement PARTAGÉ live / simulation, sans I/O, sans entité JPA, sans exchange :
 * {@code (LiveDemand, position tradable, cash du pool, modulation) -> décision (action, montant, quantité, raison de blocage,
 * journal de modulation)}. Chaîne : modulation (puissance, biais, bande) des quantités demandées, vente plafonnée à la position
 * tradable, achat tout-ou-rien contre le {@link CashLedger}. Les ledgers fournis sont mis à jour par une action acceptée
 * (cash réservé, exposition). Le live ({@link LiveSizing}) l'appelle puis écrit son bloc ; la simulation l'appelle directement.
 */
@Slf4j
public final class SizingCore {

    private static final double EPSILON = 1e-9;
    private static final com.fasterxml.jackson.databind.ObjectMapper JOURNAL_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private SizingCore() {
    }

    /**
     * Décision du jour pour un lien. {@code amountUsdc} / {@code quantity} sont {@code null} quand aucune quantité n'est retenue
     * (même contrat que le bloc live) ; {@code journal} est {@code null} sans modulation.
     */
    public record Decision(RainbowLiveAction action, LiveBlockReason blockReason, Double amountUsdc, Double quantity,
                           Map<String, Object> journal) {

        /** Journal sérialisé (JSON) ; {@code null} sans modulation. */
        public String journalJson() {
            if (journal == null) {
                return null;
            }
            try {
                return JOURNAL_MAPPER.writeValueAsString(journal);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                log.warn("Dimensionnement : journal de modulation non sérialisable : {}", e.getMessage());
                return null;
            }
        }
    }

    /**
     * @param assetSymbol actif (logs)
     * @param demand      demande du signal, déjà redimensionnée au montant de base du lien
     * @param tradable    position tradable (réelle ou simulée) du lien
     * @param ledger      pool de cash du wallet ; l'achat accepté y est réservé
     * @param modulation  modulation du lien ({@code null} ⇒ aucune)
     */
    public static Decision decide(String assetSymbol, LiveDemand demand, double tradable, CashLedger ledger,
                                  LiveModulation modulation) {
        double buyAmount = demand.buyAmount();
        double close = demand.close();
        RainbowLiveAction action = RainbowLiveAction.NONE;
        LiveBlockReason reason = LiveBlockReason.NONE;
        Double amount = null;
        Double quantity = null;

        double signalSell = demand.sellQty(tradable);
        double sellQty = signalSell;
        LiveModulation.Outcome modulated = null;
        if (modulation != null && (buyAmount > EPSILON || signalSell > 0)) {
            modulated = modulation.modulate(buyAmount, signalSell, close);
            buyAmount = modulated.buyAmount();
            sellQty = modulated.sellQty();
        }
        if (buyAmount > 0 && signalSell > 0) {
            log.warn("Bench Rainbow live : achat ET vente sur la même bougie pour {} : la vente l'emporte", assetSymbol);
        }
        if (signalSell > 0) {
            double qty = Math.min(sellQty, tradable);
            if (qty > EPSILON) {
                action = RainbowLiveAction.SELL;
                quantity = qty;
                amount = qty * close;
                applyToExposure(modulation, RainbowLiveAction.SELL, qty * close);
            } else if (modulated != null) {
                reason = LiveBlockReason.BAND_LIMIT;
                log.info("Bench Rainbow live : vente de {} ramenée à 0 par la modulation (bande ou plafond)", assetSymbol);
            }
        } else if (demand.buyAmount() > EPSILON) {
            if (buyAmount > EPSILON) {
                amount = buyAmount;
                quantity = buyAmount / close;
                if (ledger.tryReserve(buyAmount)) {
                    action = RainbowLiveAction.BUY;
                    applyToExposure(modulation, RainbowLiveAction.BUY, buyAmount);
                } else {
                    action = RainbowLiveAction.BLOCKED;
                    reason = LiveBlockReason.INSUFFICIENT_CASH;
                    log.info("Bench Rainbow live : achat {} USD de {} bloqué, cash restant {} USD", buyAmount, assetSymbol,
                            ledger.remaining());
                }
            } else if (modulated != null) {
                reason = LiveBlockReason.BAND_LIMIT;
                log.info("Bench Rainbow live : achat de {} ramené à 0 par la modulation (bande ou plafond)", assetSymbol);
            }
        }
        Map<String, Object> journal = null;
        if (modulated != null) {
            journal = modulated.journal();
            journal.put("action", action.name());
            journal.put("finalAmountUsd", amount);
            journal.put("finalQuantity", quantity);
            ExposureLedger exposure = modulation.exposure();
            if (exposure != null && exposure.isKnown()) {
                journal.put("exposureAfter", Math.round(exposure.exposurePct() * 1e6) / 1e6);
            }
        }
        return new Decision(action, reason, amount, quantity, journal);
    }

    /** Met à jour l'exposition du wallet pour le lien suivant (action acceptée uniquement). */
    private static void applyToExposure(LiveModulation modulation, RainbowLiveAction type, double amountUsd) {
        ExposureLedger exposure = modulation == null ? null : modulation.exposure();
        if (exposure == null) {
            return;
        }
        if (type == RainbowLiveAction.BUY) {
            exposure.applyBuy(amountUsd);
        } else {
            exposure.applySell(amountUsd);
        }
    }
}
