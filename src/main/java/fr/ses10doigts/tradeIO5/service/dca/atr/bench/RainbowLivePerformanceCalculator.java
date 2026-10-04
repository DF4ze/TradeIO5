package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Performance du bench grandeur nature vs DCA fixe, calculée à la lecture depuis les runs 23:55 (pur, sans Spring).
 * <p>
 * Mêmes définitions que {@code RainbowAtrResult} / le panneau du pine : investi, vendu, restant (= position × dernier
 * close), gain réalisé (coût moyen), gain potentiel (= restant − coût de revient), gain total (= vendu + restant −
 * investi), pourcentages rapportés à l'investi (0 si investi = 0).
 * <ul>
 *   <li>Stratégie = rejeu des actions figées du bloc 23:55 (le bloc 00:05 est ignoré). Elle reflète le wallet mock
 *       (actions plafonnées par cash/position), pas la position du rejeu moteur.</li>
 *   <li>DCA fixe de référence = achat de {@code baseAmount} (celui du snapshot de config du run du jour) au close de
 *       chaque jour ayant un bloc 23:55, à partir du 1er run du preset. Aucun comblement des jours manquants.</li>
 *   <li>Un jour sans bloc 23:55 (ou sans close exploitable) est ignoré.</li>
 * </ul>
 */
public final class RainbowLivePerformanceCalculator {

    private RainbowLivePerformanceCalculator() {
    }

    public record Metrics(double invested, double saleProceeds, double currentValue, double realizedGain,
                          double potentialGain, double totalGain, double pnlPercent, double realizedPercent,
                          double potentialPercent, double position, double costBasis, double lastClose,
                          double fixedInvested, double fixedQuantity, double fixedValue, double fixedGain,
                          double fixedPnlPercent, double outperformanceGain, double outperformancePoints) {
    }

    /** Équité du wallet mock : cash + position × dernier close, vs capital initial. */
    public record WalletMetrics(double initialCapitalUsdc, double cashUsdc, double positionQuantity,
                                double equityUsdc, double pnlPercent) {
    }

    public record DayPoint(LocalDate day, double close, Integer zone, RainbowLiveAction actionType,
                           double actionAmountUsdc, double actionQuantity, double invested, double saleProceeds,
                           double currentValue, double position, double costBasis, double walletEquity,
                           double fixedValue, double fixedInvested) {
    }

    public record TradeMarker(LocalDate day, RainbowLiveAction type, double price, double amountUsdc, double quantity) {
    }

    public record Performance(int days, LocalDate firstDay, LocalDate lastDay, Metrics metrics, WalletMetrics wallet,
                              List<DayPoint> series, List<TradeMarker> markers) {
    }

    /**
     * @param runs               runs du preset, ordre chronologique
     * @param initialCapitalUsdc capital initial du wallet mock
     */
    public static Performance compute(List<RainbowLiveRun> runs, double initialCapitalUsdc) {
        double invested = 0;
        double saleProceeds = 0;
        double realized = 0;
        double position = 0;
        double costBasis = 0;
        double fixedInvested = 0;
        double fixedQty = 0;
        double lastClose = 0;
        double cash = initialCapitalUsdc;
        double storedPosition = 0;
        int days = 0;
        LocalDate firstDay = null;
        LocalDate lastDay = null;
        List<DayPoint> series = new ArrayList<>();
        List<TradeMarker> markers = new ArrayList<>();

        for (RainbowLiveRun run : runs) {
            RainbowLivePassBlock b = run.getPass2355();
            if (b == null || b.getClose() == null || b.getClose() <= 0) {
                continue;
            }
            double close = b.getClose();
            RainbowLiveAction type = b.getActionType() == null ? RainbowLiveAction.NONE : b.getActionType();
            double amount = nz(b.getActionAmountUsdc());
            double qty = nz(b.getActionQuantity());

            if (type == RainbowLiveAction.BUY && amount > 0 && qty > 0) {
                invested += amount;
                position += qty;
                costBasis += amount;
                markers.add(new TradeMarker(run.getDay(), type, close, amount, qty));
            } else if (type == RainbowLiveAction.SELL && amount > 0 && qty > 0 && position > 0) {
                double fraction = Math.min(1.0, qty / position);
                double soldCost = costBasis * fraction;
                realized += amount - soldCost;
                costBasis -= soldCost;
                position = Math.max(0.0, position - qty);
                saleProceeds += amount;
                markers.add(new TradeMarker(run.getDay(), type, close, amount, qty));
            } else {
                type = RainbowLiveAction.NONE;
                amount = 0;
                qty = 0;
            }

            double base = run.getConfig() == null ? 0 : run.getConfig().getBaseAmount();
            if (base > 0) {
                fixedInvested += base;
                fixedQty += base / close;
            }

            // état du wallet mock stocké par le 23:55 ; à défaut, rejeu (cash = capital − investi + vendu)
            cash = b.getCashAfter() != null ? b.getCashAfter() : initialCapitalUsdc - invested + saleProceeds;
            storedPosition = b.getPositionAfter() != null ? b.getPositionAfter() : position;
            lastClose = close;
            days++;
            if (firstDay == null) {
                firstDay = run.getDay();
            }
            lastDay = run.getDay();
            series.add(new DayPoint(run.getDay(), close, b.getZone(), type, amount, qty, invested, saleProceeds,
                    position * close, position, costBasis, cash + storedPosition * close, fixedQty * close, fixedInvested));
        }

        double currentValue = position * lastClose;
        double potential = currentValue - costBasis;
        double total = saleProceeds + currentValue - invested;
        double pnl = pct(total, invested);
        double fixedValue = fixedQty * lastClose;
        double fixedGain = fixedValue - fixedInvested;
        double fixedPnl = pct(fixedGain, fixedInvested);
        Metrics metrics = new Metrics(invested, saleProceeds, currentValue, realized, potential, total, pnl,
                pct(realized, invested), pct(potential, invested), position, costBasis, lastClose,
                fixedInvested, fixedQty, fixedValue, fixedGain, fixedPnl, total - fixedGain, pnl - fixedPnl);
        double equity = cash + storedPosition * lastClose;
        WalletMetrics wallet = new WalletMetrics(initialCapitalUsdc, cash, storedPosition, equity,
                pct(equity - initialCapitalUsdc, initialCapitalUsdc));
        return new Performance(days, firstDay, lastDay, metrics, wallet, series, markers);
    }

    private static double pct(double gain, double base) {
        return base > 0 ? gain / base * 100 : 0;
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
