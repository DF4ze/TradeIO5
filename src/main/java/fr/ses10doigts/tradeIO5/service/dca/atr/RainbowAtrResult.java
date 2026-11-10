package fr.ses10doigts.tradeIO5.service.dca.atr;

import java.util.List;

/**
 * Résultat d'un rejeu {@link RainbowAtrEngine} sur une fenêtre (mêmes agrégats que le panneau du pine).
 * Montants en unité de {@code baseAmount}.
 */
public record RainbowAtrResult(
        double invested, double saleProceeds, double currentValue, double realizedGain, double costBasis,
        double position, double moonReserveQty,
        double fixedQty, double fixedInvested, double lastPrice,
        int zoneBuys, int triggeredBuys, int sells, int moonEntries, int moonStops,
        int bars,
        // état final (utile à l'indicateur)
        int lastZone, double lastSma, double lastAtr, boolean moonModeLast, boolean buyArmed, boolean sellArmed,
        boolean buyLocked, int cooldownRemaining, double lastBuyFactor, double lastSellFactor, double lastAthDistance,
        List<Event> events
) {

    /** Évènement de trace (si {@code trace=true}) : type = BUY_ZONE, BUY_TRIGGERED, SELL, MOON_ENTRY, MOON_STOP, BUY_ARM, SELL_ARM. */
    public record Event(int index, String type, double quantity, double amount) { }

    /**
     * Agrégats du pine reconstitués depuis un rejeu jour par jour ({@link RainbowAtrReplay}) : seules les barres ACTIVES
     * alimentent le DCA fixe de référence ({@code baseAmount} par barre, comme le rejeu). {@code rows} non vide.
     */
    public static RainbowAtrResult fromRows(List<RainbowAtrReplay.DayRow> rows, double baseAmount) {
        RainbowAtrReplay.DayRow last = rows.getLast();
        int zoneBuys = 0;
        int triggeredBuys = 0;
        int sells = 0;
        int moonEntries = 0;
        int moonStops = 0;
        double fixedQty = 0;
        double fixedInvested = 0;
        for (RainbowAtrReplay.DayRow r : rows) {
            if (r.buyKind() == RainbowSignal.BuyKind.ZONE) {
                zoneBuys++;
            } else if (r.buyKind() == RainbowSignal.BuyKind.TRIGGERED) {
                triggeredBuys++;
            }
            if (r.sellKind() == RainbowSignal.SellKind.SELL) {
                sells++;
            } else if (r.sellKind() == RainbowSignal.SellKind.MOON_STOP) {
                moonStops++;
            }
            moonEntries += r.moonEntry() ? 1 : 0;
            if (r.active()) {
                fixedQty += baseAmount / r.valuationPrice();
                fixedInvested += baseAmount;
            }
        }
        return new RainbowAtrResult(last.invested(), last.saleProceeds(), last.position() * last.valuationPrice(), last.realizedGain(),
                last.costBasis(), last.position(), last.reserveQty(), fixedQty, fixedInvested, last.valuationPrice(),
                zoneBuys, triggeredBuys, sells, moonEntries, moonStops, rows.size(),
                last.band().zone(), last.band().sma(), last.band().atr(), last.moonMode(), last.buyArmed(), last.sellArmed(),
                last.buyLocked(), last.cooldown(), last.buyAthFactor(), last.sellAthFactor(), last.athDistance(), List.of());
    }

    /** Gain potentiel = valeur restante - coût de revient restant. */
    public double potentialGain() { return currentValue - costBasis; }

    /** Gain total = vendu + restant - investi. */
    public double totalGain() { return saleProceeds + currentValue - invested; }

    public double pnlPercent() { return invested > 0 ? totalGain() / invested * 100 : 0; }

    public double realizedPercent() { return invested > 0 ? realizedGain / invested * 100 : 0; }

    public double potentialPercent() { return invested > 0 ? potentialGain() / invested * 100 : 0; }

    /** PnL % du DCA fixe de référence (même base, tous les jours, valorisé au dernier prix). */
    public double fixedPnlPercent() {
        return fixedInvested > 0 ? (fixedQty * lastPrice - fixedInvested) / fixedInvested * 100 : 0;
    }

    /** Gain du DCA fixe de référence (même base, tous les jours, valorisé au dernier prix). */
    public double fixedGain() { return fixedQty * lastPrice - fixedInvested; }

    /** Part du gain (réalisé+potentiel) déjà encaissée ; NaN si les deux sont nuls. */
    public double realizedRatio() {
        double r = realizedGain;
        double p = potentialGain();
        return (r + p) != 0 ? r / (r + p) : Double.NaN;
    }
}
