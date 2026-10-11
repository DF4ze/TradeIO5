package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import lombok.extern.slf4j.Slf4j;

/**
 * Dimensionnement d'un lien (binding) sur son portefeuille réel (lecture seule, rien n'est exécuté) pour une passe, à partir de la {@link LiveDemand} du preset :
 * position tradable = {@code bagPercent} x position réelle, achat tout-ou-rien contre le {@link CashLedger} du pool,
 * vente plafonnée à la position tradable. Écrit le snapshot et l'action live dans le {@link RainbowLiveLiveBlock} du lien. {@code UNAVAILABLE}/{@code STALE} ⇒ aucune action.
 */
@Slf4j
final class LiveSizing {

    private static final double EPSILON = 1e-9;
    private static final double PERCENT = 100.0;

    private final String assetSymbol;
    private final PortfolioReading reading;
    private final CashLedger ledger;
    private final double bagPercent;

    private LiveSizing(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent) {
        this.assetSymbol = assetSymbol;
        this.reading = reading;
        this.ledger = ledger;
        this.bagPercent = bagPercent;
    }

    /** Passe normale : {@code ledger} nul si la lecture n'est pas {@code OK}. */
    static LiveSizing of(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent) {
        return new LiveSizing(assetSymbol, reading, ledger, bagPercent);
    }

    /** Position réelle offerte à la stratégie ; 0 si la lecture n'est pas exploitable. */
    double tradablePosition() {
        return reading != null && reading.isOk() ? reading.quantity(assetSymbol) * bagPercent / PERCENT : 0.0;
    }

    /** Écrit le snapshot et l'action live du lien dans {@code block} d'après la demande du preset. */
    void apply(RainbowLiveLiveBlock block, LiveDemand demand) {
        double buyAmount = demand.buyAmount();
        double close = demand.close();
        block.setStatus(reading.status());
        block.setWalletId(reading.walletId());
        block.setFetchedAt(reading.fetchedAt());
        block.setActionType(RainbowLiveAction.NONE);
        block.setBlockReason(LiveBlockReason.NONE);
        if (!reading.isOk()) {
            block.setBlockReason(reading.status() == PortfolioStatus.NOT_TRADABLE
                    ? LiveBlockReason.NOT_TRADABLE_WITHOUT_FIAT : LiveBlockReason.UNAVAILABLE);
            log.warn("Bench Rainbow live : portefeuille {} pour {} (wallet {}) : aucune action", reading.status(), assetSymbol,
                    reading.walletId());
            return;
        }
        double tradable = tradablePosition();
        block.setCashUsd(reading.cash());
        block.setCashDetail(CashDetailCodec.write(reading.cashByMember()));
        block.setPositionQty(reading.quantity(assetSymbol));
        block.setTradableQty(tradable);
        block.setCashReserved(ledger.reserved());

        double sellQty = demand.sellQty(tradable);
        if (buyAmount > 0 && sellQty > 0) {
            log.warn("Bench Rainbow live : achat ET vente sur la même bougie pour {} : la vente l'emporte", assetSymbol);
        }
        if (sellQty > 0) {
            double qty = Math.min(sellQty, tradable);
            if (qty > EPSILON) {
                block.setActionType(RainbowLiveAction.SELL);
                block.setActionQuantity(qty);
                block.setActionAmountUsdc(qty * close);
            }
        } else if (buyAmount > EPSILON) {
            block.setActionAmountUsdc(buyAmount);
            block.setActionQuantity(buyAmount / close);
            if (ledger.tryReserve(buyAmount)) {
                block.setActionType(RainbowLiveAction.BUY);
            } else {
                block.setActionType(RainbowLiveAction.BLOCKED);
                block.setBlockReason(LiveBlockReason.INSUFFICIENT_CASH);
                log.info("Bench Rainbow live : achat {} USD de {} bloqué, cash restant {} USD (wallet {})", buyAmount,
                        assetSymbol, ledger.remaining(), reading.walletId());
            }
        }
        log.debug("Bench Rainbow live {} : action={} raison={} tradable={}", assetSymbol, block.getActionType(),
                block.getBlockReason(), tradable);
    }
}
