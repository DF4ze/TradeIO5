package fr.ses10doigts.tradeIO5.service.dca.atr;


import java.util.ArrayList;
import java.util.List;

/**
 * Rejeu du Rainbow DCA "bornes ATR" v3 (ATH + To the moon) — port Java fidèle de
 * {@code tools/pine/rainbow_dca_v4_atr_moon.pine}, SOURCE DE VÉRITÉ : en cas d'écart, c'est le pine
 * qui a raison. Moteur pur (double, sans Spring), ~µs par bougie, conçu pour être appelé des
 * centaines de milliers de fois par un bench.
 * <p>
 * Ordre exact des opérations d'une bougie (identique au pine) :
 * <ol>
 *   <li>cliquet de réserve moon (si option cliquet, mode actif, hors bougie d'entrée) ;</li>
 *   <li>entrée moon : réserve = % du bag courant ;</li>
 *   <li>sortie moon : vente de min(réserve, position) × moonStopSellPct au close, réserve remise à 0 ;
 *       la bougie compte alors comme "vendue" ({@code soldThisBar}) ;</li>
 *   <li>machine vente : sortie moon => désarme + cooldown ; sinon vente armée (jours++, déclenchement
 *       selon le mode, quantité = position × min(1, fraction × facteurATH) plafonnée à la position
 *       moins la réserve ; verrou d'achat + annulation d'un armement achat antérieur si l'option est
 *       active ; désarmement + cooldown même si rien n'a été vendu) ; sinon armement en zone
 *       EXTREME_HAUT (si cooldown = 0 OU vente autorisée pendant cooldown — jamais l'achat) ;</li>
 *   <li>levée du verrou d'achat : franchissement vers le bas de la borne DOWN2 (clôture précédente
 *       ≥ DOWN2 précédente ET clôture &lt; DOWN2), pas sur une bougie vendue, pas à la 1re bougie rejouée ;</li>
 *   <li>achat (bloqué si vente armée, cooldown &gt; 0 ou verrou ; l'armement achat est alors GELÉ, ses
 *       jours ne progressent pas) : armé => déclenchement selon le mode (×multTriggered) ; sinon zone
 *       EXTREME_BAS arme ; sinon multiplicateur de zone ; montant = base × facteurATH × mult ;</li>
 *   <li>décrément du cooldown ; DCA fixe de référence (base chaque jour).</li>
 * </ol>
 * Facteurs ATH : neutralisés (1.0) pendant le mode moon, calculés sur l'ATH global de la série (depuis
 * la 1re bougie, cf. {@link RainbowAtrDataset}) et la valeur de la bougie courante (ATH incluant son high).
 * <p>
 * Pilotage par régime : {@code tunings[k]} avec {@code regimeOfBar[i] = k} ; l'état (armements, cooldown,
 * position) traverse les changements de régime. Un seul tuning = pine classique.
 */
public final class RainbowAtrEngine {

    public static final int EXTREME_BAS = 0;
    public static final int X2 = 1;
    public static final int X1 = 2;
    public static final int X0_5 = 3;
    public static final int NO_BUY = 4;
    public static final int EXTREME_HAUT = 5;

    private RainbowAtrEngine() {
    }

    public static RainbowAtrResult simulate(
            RainbowAtrDataset ds, RainbowAtrGlobals g, RainbowAtrTuning tuning, int startIdx, int endIdx
    ) {
        return simulate(ds, g, new RainbowAtrTuning[]{tuning}, null, startIdx, endIdx, false);
    }

    public static RainbowAtrResult simulate(
            RainbowAtrDataset ds, RainbowAtrGlobals g, RainbowAtrTuning[] tunings, int[] regimeOfBar,
            int startIdx, int endIdx, boolean trace
    ) {
        int nT = tunings.length;
        double[][] smaArr = new double[nT][];
        double[][] atrArr = new double[nT][];
        for (int k = 0; k < nT; k++) {
            smaArr[k] = ds.sma(tunings[k].smaPeriod());
            atrArr[k] = ds.atr(tunings[k].atrPeriod());
        }
        RainbowAtrDataset.MoonFlags moon = ds.moon(g.moonOn(), g.moonTrailingStopPct());
        double refBuy = g.athRefDdBuyPct() / 100.0;
        double refSell = g.athRefDdSellPct() / 100.0;
        List<RainbowAtrResult.Event> events = trace ? new ArrayList<>() : null;

        double pos = 0, costBasis = 0, realized = 0, invested = 0, saleProceeds = 0;
        double fixedQty = 0, fixedInvested = 0, reserve = 0;
        boolean buyArmed = false, sellArmed = false, buyLocked = false;
        double lowestSinceArmed = Double.NaN, highestSinceArmed = Double.NaN;
        int buyArmedDays = 0, sellArmedDays = 0, cooldown = 0;
        double prevEb = Double.NaN, prevClose = Double.NaN;
        int zoneBuys = 0, triggeredBuys = 0, sells = 0, moonEntries = 0, moonStops = 0, bars = 0;
        int lastZone = -1;
        double lastSma = Double.NaN, lastAtr = Double.NaN, lastBuyFac = 1, lastSellFac = 1, lastAthD = 0;
        boolean lastMoon = false;
        double lastPrice = Double.NaN;

        for (int i = startIdx; i <= endIdx; i++) {
            int k = regimeOfBar == null ? 0 : regimeOfBar[i];
            RainbowAtrTuning t = tunings[k];
            double sma = smaArr[k][i];
            double atr = atrArr[k][i];
            double cl = ds.close(i);
            if (Double.isNaN(sma) || Double.isNaN(atr)) {
                prevEb = Double.NaN;
                prevClose = cl;
                continue;
            }
            double eb = sma - atr * t.atrMultDown2();
            double zb = sma - atr * t.atrMultDown1();
            double zh1 = sma + atr * t.atrMultUp1();
            double zh2 = sma + atr * t.atrMultUp2();
            double eh = sma + atr * t.atrMultUp3();
            int zone = cl < eb ? EXTREME_BAS : cl < zb ? X2 : cl < zh1 ? X1 : cl < zh2 ? X0_5 : cl < eh ? NO_BUY : EXTREME_HAUT;

            double trailUp = 1 + t.trailingStopBuyPct() / 100.0;
            double trailDown = 1 - t.trailingStopSellPct() / 100.0;
            int cooldownAfterSell = t.cooldownAfterSellOn() ? t.cooldownDays() : 0;

            boolean moonModeI = moon.mode()[i];
            boolean moonEntryI = moon.entry()[i];
            boolean moonExitI = moon.exit()[i];
            double athI = ds.ath(i);
            double athD = athI > 0 ? 1 - cl / athI : 0.0;
            double tBuy = Math.min(Math.max(athD / refBuy, 0), 1);
            double tSell = Math.min(Math.max(athD / refSell, 0), 1);
            boolean athActive = g.athOn() && !moonModeI;
            double buyAthFac = athActive ? g.athBuyMin() + (g.athBuyMax() - g.athBuyMin()) * tBuy : 1.0;
            double sellAthFac = athActive ? g.athSellMax() + (g.athSellMin() - g.athSellMax()) * tSell : 1.0;
            boolean soldThisBar = false;

            // 1) cliquet de réserve
            if (g.moonReserveRatchet() && moonModeI && !moonEntryI) {
                reserve = Math.max(reserve, pos * g.moonReservePct() / 100.0);
            }
            // 2) entrée moon
            if (moonEntryI) {
                reserve = pos * g.moonReservePct() / 100.0;
                moonEntries++;
                if (trace) { events.add(new RainbowAtrResult.Event(i, "MOON_ENTRY", 0, 0)); }
            }
            // 3) sortie moon
            if (moonExitI) {
                double stopQty = Math.min(reserve, pos) * g.moonStopSellPct() / 100.0;
                if (stopQty > 0) {
                    double proceeds = stopQty * cl;
                    double cost = costBasis * stopQty / pos;
                    realized += proceeds - cost;
                    costBasis -= cost;
                    pos -= stopQty;
                    saleProceeds += proceeds;
                    soldThisBar = true;
                    moonStops++;
                    if (trace) { events.add(new RainbowAtrResult.Event(i, "MOON_STOP", stopQty, proceeds)); }
                }
                reserve = 0.0;
            }

            // 4) machine vente
            if (moonExitI) {
                sellArmed = false;
                highestSinceArmed = Double.NaN;
                sellArmedDays = 0;
                cooldown = cooldownAfterSell;
            } else if (sellArmed) {
                sellArmedDays++;
                boolean trig = switch (t.sellReentryMode()) {
                    case TRAILING_STOP -> cl < eh || cl < highestSinceArmed * trailDown;
                    case IMMEDIATE -> cl < eh || cl < zh2;
                    case FIXED_DELAY -> sellArmedDays >= t.fixedDelayDays();
                };
                if (trig) {
                    double requested = pos * Math.min(1.0, t.sellFraction() * sellAthFac);
                    double sellable = Math.max(pos - reserve, 0.0);
                    double sellQty = Math.min(requested, sellable);
                    if (sellQty > 0) {
                        double proceeds = sellQty * cl;
                        double cost = costBasis * sellQty / pos;
                        realized += proceeds - cost;
                        costBasis -= cost;
                        pos -= sellQty;
                        saleProceeds += proceeds;
                        sells++;
                        if (trace) { events.add(new RainbowAtrResult.Event(i, "SELL", sellQty, proceeds)); }
                        if (t.blockBuyAfterSellUntilDown2()) {
                            buyLocked = true;
                            soldThisBar = true;
                            buyArmed = false;
                            lowestSinceArmed = Double.NaN;
                            buyArmedDays = 0;
                        }
                    }
                    sellArmed = false;
                    highestSinceArmed = Double.NaN;
                    sellArmedDays = 0;
                    cooldown = cooldownAfterSell;
                } else {
                    highestSinceArmed = Math.max(highestSinceArmed, cl);
                }
            } else if (zone == EXTREME_HAUT && (cooldown == 0 || t.allowSellDuringCooldown())) {
                sellArmed = true;
                highestSinceArmed = cl;
                sellArmedDays = 0;
                if (trace) { events.add(new RainbowAtrResult.Event(i, "SELL_ARM", 0, 0)); }
            }

            // 5) levée du verrou : nouveau franchissement sous DOWN2 (pas à la 1re bougie rejouée)
            boolean crossedBelowDown2 = i > startIdx && !Double.isNaN(prevEb) && prevClose >= prevEb && cl < eb;
            if (t.blockBuyAfterSellUntilDown2() && buyLocked && !soldThisBar && crossedBelowDown2) {
                buyLocked = false;
            }

            // 6) achat
            double buyMult = Double.NaN;
            boolean triggered = false;
            if (!sellArmed && cooldown == 0 && !buyLocked) {
                if (buyArmed) {
                    buyArmedDays++;
                    boolean trig = switch (t.buyReentryMode()) {
                        case TRAILING_STOP -> cl > eb || cl > lowestSinceArmed * trailUp;
                        case IMMEDIATE -> cl > eb;
                        case FIXED_DELAY -> buyArmedDays >= t.fixedDelayDays();
                    };
                    if (trig) {
                        buyMult = g.multTriggered();
                        triggered = true;
                        buyArmed = false;
                        lowestSinceArmed = Double.NaN;
                        buyArmedDays = 0;
                    } else {
                        lowestSinceArmed = Math.min(lowestSinceArmed, cl);
                    }
                } else if (zone == EXTREME_BAS) {
                    buyArmed = true;
                    lowestSinceArmed = cl;
                    buyArmedDays = 0;
                    if (trace) { events.add(new RainbowAtrResult.Event(i, "BUY_ARM", 0, 0)); }
                } else {
                    double im = switch (zone) {
                        case X2 -> g.multX2();
                        case X1 -> g.multX1();
                        case X0_5 -> g.multX0_5();
                        default -> Double.NaN;
                    };
                    if (!Double.isNaN(im) && im > 0) {
                        buyMult = im;
                    }
                }
            }
            if (!Double.isNaN(buyMult)) {
                double inv = g.baseAmount() * buyAthFac * buyMult;
                pos += inv / cl;
                costBasis += inv;
                invested += inv;
                if (triggered) { triggeredBuys++; } else { zoneBuys++; }
                if (trace) { events.add(new RainbowAtrResult.Event(i, triggered ? "BUY_TRIGGERED" : "BUY_ZONE", inv / cl, inv)); }
            }

            // 7) cooldown, DCA fixe
            if (cooldown > 0) { cooldown--; }
            fixedQty += g.baseAmount() / cl;
            fixedInvested += g.baseAmount();

            prevEb = eb;
            prevClose = cl;
            bars++;
            lastZone = zone; lastSma = sma; lastAtr = atr; lastMoon = moonModeI;
            lastBuyFac = buyAthFac; lastSellFac = sellAthFac; lastAthD = athD;
            lastPrice = cl;
        }

        return new RainbowAtrResult(invested, saleProceeds, pos * lastPrice, realized, costBasis, pos, reserve,
                fixedQty, fixedInvested, lastPrice,
                zoneBuys, triggeredBuys, sells, moonEntries, moonStops, bars,
                lastZone, lastSma, lastAtr, lastMoon, buyArmed, sellArmed, buyLocked, cooldown,
                lastBuyFac, lastSellFac, lastAthD, events == null ? List.of() : events);
    }
}
