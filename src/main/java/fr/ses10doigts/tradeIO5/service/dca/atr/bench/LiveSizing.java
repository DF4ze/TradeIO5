package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * Dimensionnement d'un lien (binding) sur son portefeuille réel (lecture seule, rien n'est exécuté) pour une passe, à partir de la {@link LiveDemand} du preset :
 * position tradable = {@code bagPercent} x position réelle, achat tout-ou-rien contre le {@link CashLedger} du pool,
 * vente plafonnée à la position tradable. Écrit le snapshot et l'action live dans le {@link RainbowLiveLiveBlock} du lien. {@code UNAVAILABLE}/{@code STALE} ⇒ aucune action.
 * Si une {@link LiveModulation} est fournie, les quantités demandées sont modulées (puissance, biais, bande d'exposition) avant
 * ces plafonds et le journal de modulation est écrit dans le bloc ; le montant final porté par le bloc est celui que lit le plan d'ordres.
 */
@Slf4j
final class LiveSizing {

    private static final double EPSILON = 1e-9;
    private static final double PERCENT = 100.0;
    private static final ObjectMapper JOURNAL_MAPPER = new ObjectMapper();

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
                block.setActionType(RainbowLiveAction.SELL);
                block.setActionQuantity(qty);
                block.setActionAmountUsdc(qty * close);
                applyToExposure(RainbowLiveAction.SELL, qty * close);
            } else if (modulated != null) {
                block.setBlockReason(LiveBlockReason.BAND_LIMIT);
                log.info("Bench Rainbow live : vente de {} ramenée à 0 par la modulation (bande ou plafond)", assetSymbol);
            }
        } else if (demand.buyAmount() > EPSILON) {
            if (buyAmount > EPSILON) {
                block.setActionAmountUsdc(buyAmount);
                block.setActionQuantity(buyAmount / close);
                if (ledger.tryReserve(buyAmount)) {
                    block.setActionType(RainbowLiveAction.BUY);
                    applyToExposure(RainbowLiveAction.BUY, buyAmount);
                } else {
                    block.setActionType(RainbowLiveAction.BLOCKED);
                    block.setBlockReason(LiveBlockReason.INSUFFICIENT_CASH);
                    log.info("Bench Rainbow live : achat {} USD de {} bloqué, cash restant {} USD (wallet {})", buyAmount,
                            assetSymbol, ledger.remaining(), reading.walletId());
                }
            } else if (modulated != null) {
                block.setBlockReason(LiveBlockReason.BAND_LIMIT);
                log.info("Bench Rainbow live : achat de {} ramené à 0 par la modulation (bande ou plafond)", assetSymbol);
            }
        }
        if (modulated != null) {
            writeJournal(block, modulated);
        }
        log.debug("Bench Rainbow live {} : action={} raison={} tradable={}", assetSymbol, block.getActionType(),
                block.getBlockReason(), tradable);
    }

    /** Met à jour l'exposition du wallet pour le lien suivant (action acceptée uniquement). */
    private void applyToExposure(RainbowLiveAction type, double amountUsd) {
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

    private void writeJournal(RainbowLiveLiveBlock block, LiveModulation.Outcome modulated) {
        Map<String, Object> journal = modulated.journal();
        journal.put("action", block.getActionType().name());
        journal.put("finalAmountUsd", block.getActionAmountUsdc());
        journal.put("finalQuantity", block.getActionQuantity());
        ExposureLedger exposure = modulation.exposure();
        if (exposure != null && exposure.isKnown()) {
            journal.put("exposureAfter", Math.round(exposure.exposurePct() * 1e6) / 1e6);
        }
        try {
            block.setModulation(JOURNAL_MAPPER.writeValueAsString(journal));
        } catch (JsonProcessingException e) {
            log.warn("Bench Rainbow live : journal de modulation non sérialisable pour {} : {}", assetSymbol, e.getMessage());
        }
    }
}
