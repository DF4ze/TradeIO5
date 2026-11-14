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
 * Si une {@link LiveModulation} est fournie, les quantités demandées sont modulées (puissance, biais, bande d'exposition) avant
 * ces plafonds et le journal de modulation est écrit dans le bloc ; le montant final porté par le bloc est celui que lit le plan d'ordres.
 */
@Slf4j
final class LiveSizing {

    private static final double PERCENT = 100.0;

    private final String assetSymbol;
    private final PortfolioReading reading;
    private final CashLedger ledger;
    private final double bagPercent;
    private final LiveModulation modulation;

    private LiveSizing(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent,
                       LiveModulation modulation) {
        this.assetSymbol = assetSymbol;
        this.reading = reading;
        this.ledger = ledger;
        this.bagPercent = bagPercent;
        this.modulation = modulation;
    }

    /** Passe normale sans modulation : {@code ledger} nul si la lecture n'est pas {@code OK}. */
    static LiveSizing of(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent) {
        return new LiveSizing(assetSymbol, reading, ledger, bagPercent, null);
    }

    /** Passe normale avec modulation ({@code null} ⇒ aucune). */
    static LiveSizing of(String assetSymbol, PortfolioReading reading, CashLedger ledger, double bagPercent,
                         LiveModulation modulation) {
        return new LiveSizing(assetSymbol, reading, ledger, bagPercent, modulation);
    }

    /** Position réelle offerte à la stratégie ; 0 si la lecture n'est pas exploitable. */
    double tradablePosition() {
        return reading != null && reading.isOk() ? reading.quantity(assetSymbol) * bagPercent / PERCENT : 0.0;
    }

    /** Écrit le snapshot et l'action live du lien dans {@code block} d'après la demande du preset. */
    void apply(RainbowLiveLiveBlock block, LiveDemand demand) {
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

        SizingCore.Decision decision = SizingCore.decide(assetSymbol, demand, tradable, ledger, modulation);
        block.setActionType(decision.action());
        block.setBlockReason(decision.blockReason());
        block.setActionAmountUsdc(decision.amountUsdc());
        block.setActionQuantity(decision.quantity());
        if (decision.journal() != null) {
            block.setModulation(decision.journalJson());
        }
        log.debug("Bench Rainbow live {} : action={} raison={} tradable={}", assetSymbol, block.getActionType(),
                block.getBlockReason(), tradable);
    }
}
