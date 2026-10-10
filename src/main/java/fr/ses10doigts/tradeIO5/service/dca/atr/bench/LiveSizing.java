package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import lombok.extern.slf4j.Slf4j;

/**
 * Dimensionnement d'un preset live sur le portefeuille réel (lecture seule, rien n'est exécuté) pour une passe :
 * position tradable = {@code bagPercent} x position réelle, achat tout-ou-rien contre le {@link CashLedger} du pool,
 * vente plafonnée à la position tradable. Écrit le snapshot {@code live*} et l'action live du bloc ; l'action du wallet
 * mock ({@code actionType}) n'est jamais touchée. {@code UNAVAILABLE}/{@code STALE} ⇒ aucune action.
 */
@Slf4j
final class LiveSizing {

    private static final double EPSILON = 1e-9;
    private static final double PERCENT = 100.0;

    private final String assetSymbol;
    private final PortfolioReading reading;
    private final CashLedger ledger;
    private final double bagPercent;
    private final boolean replay;

    private LiveSizing(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent, boolean replay) {
        this.assetSymbol = assetSymbol;
        this.reading = reading;
        this.ledger = ledger;
        this.bagPercent = bagPercent;
        this.replay = replay;
    }

    /** Passe normale : {@code ledger} nul si la lecture n'est pas {@code OK}. */
    static LiveSizing of(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent) {
        return new LiveSizing(assetSymbol, reading, ledger, bagPercent, false);
    }

    /** Rejeu 23:55 : snapshot et action d'origine conservés (recopiés par {@code upsertPass}). */
    static LiveSizing replay() {
        return new LiveSizing(null, null, null, 0.0, true);
    }

    /** Position réelle offerte à la stratégie ; 0 si la lecture n'est pas exploitable. */
    double tradablePosition() {
        return reading != null && reading.isOk() ? reading.quantity(assetSymbol) * bagPercent / PERCENT : 0.0;
    }

    void apply(RainbowLivePassBlock block, double buyAmount, double sellQty, double close) {
        if (replay) {
            return;
        }
        block.setLiveStatus(reading.status());
        block.setLiveWalletId(reading.walletId());
        block.setLiveFetchedAt(reading.fetchedAt());
        block.setLiveActionType(RainbowLiveAction.NONE);
        block.setLiveBlockReason(LiveBlockReason.NONE);
        if (!reading.isOk()) {
            block.setLiveBlockReason(LiveBlockReason.UNAVAILABLE);
            log.warn("Bench Rainbow live : portefeuille {} pour {} (wallet {}) : aucune action", reading.status(), assetSymbol,
                    reading.walletId());
            return;
        }
        double tradable = tradablePosition();
        block.setLiveCashUsdc(reading.cash());
        block.setLivePositionQty(reading.quantity(assetSymbol));
        block.setLiveTradableQty(tradable);
        block.setLiveCashReserved(ledger.reserved());

        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow live : achat ET vente sur la même bougie pour {} : la vente l'emporte", assetSymbol);
        }
        if (sellQty > 0) {
            double qty = Math.min(sellQty, tradable);
            if (qty > EPSILON) {
                block.setLiveActionType(RainbowLiveAction.SELL);
                block.setLiveActionQuantity(qty);
                block.setLiveActionAmountUsdc(qty * close);
            }
        } else if (buyAmount > EPSILON) {
            block.setLiveActionAmountUsdc(buyAmount);
            block.setLiveActionQuantity(buyAmount / close);
            if (ledger.tryReserve(buyAmount)) {
                block.setLiveActionType(RainbowLiveAction.BUY);
            } else {
                block.setLiveActionType(RainbowLiveAction.BLOCKED);
                block.setLiveBlockReason(LiveBlockReason.INSUFFICIENT_CASH);
                log.info("Bench Rainbow live : achat {} USDC de {} bloqué, cash restant {} USDC (wallet {})", buyAmount,
                        assetSymbol, ledger.remaining(), reading.walletId());
            }
        }
        log.debug("Bench Rainbow live {} : action={} raison={} tradable={}", assetSymbol, block.getLiveActionType(),
                block.getLiveBlockReason(), tradable);
    }
}
