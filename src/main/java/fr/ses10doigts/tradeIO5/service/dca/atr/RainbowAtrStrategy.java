package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * L2 — machine d'états du Rainbow DCA ATR, en fonction PURE d'une bougie :
 * {@code step(état, bornes L1, high, ATH de référence, position, jeu de paramètres) → (nouvel état, signal)}.
 * Port fidèle de {@code tools/pine/rainbow_dca_v4_atr_moon.pine} (SOURCE DE VÉRITÉ : en cas d'écart, le pine a
 * raison). Le rejeu sur plage ({@link RainbowAtrEngine#simulate}) est un fold de {@code step} ; l'exécution
 * quotidienne appellera le même {@code step}, l'état étant persisté par (utilisateur, actif).
 * <p>
 * Ce n'est PAS une {@code Strategy} du Tree (interface sans état {@code evaluate(contexte, paramètres)}) : un
 * orchestrateur charge/sauve l'état et appelle {@code step}.
 * <p>
 * Ordre des opérations d'une bougie (identique au pine) :
 * <ol>
 *   <li>automate To the moon ({@link RainbowMoon}) ; cliquet de réserve (option, mode actif, hors bougie d'entrée) ;</li>
 *   <li>entrée moon : réserve = % de la position ;</li>
 *   <li>sortie moon : vente de min(réserve, position) × moonStopSellPct, réserve remise à 0 ; la bougie compte
 *       alors comme « vendue » ;</li>
 *   <li>machine vente : sortie moon => désarme + cooldown ; sinon vente armée (jours++, déclenchement selon le
 *       mode, fraction = min(1, fraction × facteurATH) plafonnée à la position hors réserve ; verrou d'achat +
 *       annulation d'un armement achat antérieur si l'option est active ; désarmement + cooldown même si rien
 *       n'a été vendu) ; sinon armement en zone EXTREME_HAUT (cooldown = 0 OU vente autorisée pendant cooldown) ;</li>
 *   <li>levée du verrou d'achat : clôture &lt; DOWN2 une fois le cooldown terminé (cooldown = 0), pas sur une
 *       bougie vendue ; un passage sous DOWN2 pendant le cooldown ne lève pas le verrou ;</li>
 *   <li>achat (bloqué si vente armée, cooldown &gt; 0 ou verrou ; armement GELÉ alors) : armé => déclenchement
 *       (×multTriggered) ; sinon zone EXTREME_BAS arme ; sinon multiplicateur de zone ;</li>
 *   <li>décrément du cooldown.</li>
 * </ol>
 * Facteurs ATH : neutralisés (1.0) pendant le mode moon ; calculés sur l'ATH bougie du jour incluse.
 */
public final class RainbowAtrStrategy {

    /** Nouvel état + signal de la bougie. */
    public record StepResult(RainbowAtrState state, RainbowSignal signal) { }

    private RainbowAtrStrategy() {
    }

    /**
     * @param state    état avant la bougie
     * @param band     bornes L1 de la bougie (non calculables : {@link #stepInvalid})
     * @param high     plus haut de la bougie (ATH du jour, pic moon)
     * @param ath      ATH de référence de l'actif à la veille ({@link AthReference#NONE} si inconnu)
     * @param position quantité détenue avant la bougie (base de la réserve moon et des ventes)
     */
    public static StepResult step(RainbowAtrState state, RainbowAtrBand band, double high, AthReference ath,
                                  double position, RainbowAtrTuning t, RainbowAtrGlobals g) {
        double cl = band.close();
        RainbowMoon.Step ms = RainbowMoon.advance(state.moon(), g.moonOn(), g.moonTrailingStopPct(), cl, high, ath.value());
        return stepWithMoon(state, band, ms, ath.includingToday(high), position, t, g);
    }

    /** Variante pour une bougie sans bornes : avance seulement l'automate moon. */
    public static StepResult stepInvalid(RainbowAtrState state, double close, double high, AthReference ath,
                                         RainbowAtrGlobals g) {
        RainbowMoon.Step ms = RainbowMoon.advance(state.moon(), g.moonOn(), g.moonTrailingStopPct(), close, high, ath.value());
        return new StepResult(state.withMoon(ms.next()), RainbowSignal.invalid(ms.mode(), ms.entry()));
    }

    /** Cœur de la machine ; {@code ms} = automate moon déjà avancé (permet au rejeu d'utiliser les drapeaux en cache). */
    static StepResult stepWithMoon(RainbowAtrState s, RainbowAtrBand band, RainbowMoon.Step ms, double athToday,
                                   double pos, RainbowAtrTuning t, RainbowAtrGlobals g) {
        double cl = band.close();
        int zone = band.zone();
        double eb = band.down2();
        double eh = band.up3();
        double zh2 = band.up2();
        double trailUp = 1 + t.trailingStopBuyPct() / 100.0;
        double trailDown = 1 - t.trailingStopSellPct() / 100.0;
        int cooldownAfterSell = t.cooldownAfterSellOn() ? t.cooldownDays() : 0;

        boolean moonModeI = ms.mode();
        boolean moonEntryI = ms.entry();
        boolean moonExitI = ms.exit();
        double refBuy = g.athRefDdBuyPct() / 100.0;
        double refSell = g.athRefDdSellPct() / 100.0;
        double athD = athToday > 0 ? 1 - cl / athToday : 0.0;
        double tBuy = Math.min(Math.max(athD / refBuy, 0), 1);
        double tSell = Math.min(Math.max(athD / refSell, 0), 1);
        boolean athActive = g.athOn() && !moonModeI;
        double buyAthFac = athActive ? g.athBuyMin() + (g.athBuyMax() - g.athBuyMin()) * tBuy : 1.0;
        double sellAthFac = athActive ? g.athSellMax() + (g.athSellMin() - g.athSellMax()) * tSell : 1.0;

        boolean buyArmed = s.buyArmed(), sellArmed = s.sellArmed(), buyLocked = s.buyLocked();
        double lowestSinceArmed = s.lowestSinceArmed(), highestSinceArmed = s.highestSinceArmed();
        int buyArmedDays = s.buyArmedDays(), sellArmedDays = s.sellArmedDays(), cooldown = s.cooldown();
        double reserve = s.reserveQty();
        boolean soldThisBar = false;
        boolean sellArmedNow = false, buyArmedNow = false;
        RainbowSignal.SellKind sellKind = RainbowSignal.SellKind.NONE;
        double sellFraction = 0.0;

        // 1) cliquet de réserve
        if (g.moonReserveRatchet() && moonModeI && !moonEntryI) {
            reserve = Math.max(reserve, pos * g.moonReservePct() / 100.0);
        }
        // 2) entrée moon
        if (moonEntryI) {
            reserve = pos * g.moonReservePct() / 100.0;
        }
        // 3) sortie moon
        if (moonExitI) {
            double stopQty = Math.min(reserve, pos) * g.moonStopSellPct() / 100.0;
            if (stopQty > 0) {
                sellKind = RainbowSignal.SellKind.MOON_STOP;
                sellFraction = stopQty / pos;
                soldThisBar = true;
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
                    sellKind = RainbowSignal.SellKind.SELL;
                    sellFraction = sellQty / pos;
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
        } else if (zone == RainbowAtrEngine.EXTREME_HAUT && (cooldown == 0 || t.allowSellDuringCooldown())) {
            sellArmed = true;
            highestSinceArmed = cl;
            sellArmedDays = 0;
            sellArmedNow = true;
        }

        // 5) levée du verrou : clôture sous DOWN2 une fois le cooldown terminé (un passage sous DOWN2 pendant le cooldown ne lève rien)
        if (t.blockBuyAfterSellUntilDown2() && buyLocked && !soldThisBar && cooldown == 0 && cl < eb) {
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
            } else if (zone == RainbowAtrEngine.EXTREME_BAS) {
                buyArmed = true;
                lowestSinceArmed = cl;
                buyArmedDays = 0;
                buyArmedNow = true;
            } else {
                double im = switch (zone) {
                    case RainbowAtrEngine.X2 -> g.multX2();
                    case RainbowAtrEngine.X1 -> g.multX1();
                    case RainbowAtrEngine.X0_5 -> g.multX0_5();
                    default -> Double.NaN;
                };
                if (!Double.isNaN(im) && im > 0) {
                    buyMult = im;
                }
            }
        }
        RainbowSignal.BuyKind buyKind = Double.isNaN(buyMult) ? RainbowSignal.BuyKind.NONE
                : triggered ? RainbowSignal.BuyKind.TRIGGERED : RainbowSignal.BuyKind.ZONE;

        // 7) cooldown
        if (cooldown > 0) {
            cooldown--;
        }

        RainbowAtrState next = new RainbowAtrState(buyArmed, sellArmed, buyLocked, lowestSinceArmed, highestSinceArmed,
                buyArmedDays, sellArmedDays, cooldown, reserve, ms.next());
        RainbowSignal signal = new RainbowSignal(true, zone, buyKind, Double.isNaN(buyMult) ? 0.0 : buyMult, buyAthFac,
                sellKind, sellFraction, sellAthFac, moonModeI, moonEntryI, buyArmedNow, sellArmedNow, athD, null, null);
        return new StepResult(next, signal);
    }
}
