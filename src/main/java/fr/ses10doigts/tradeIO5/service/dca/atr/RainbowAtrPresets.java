package fr.ses10doigts.tradeIO5.service.dca.atr;

import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;

import java.util.List;

/**
 * Jeux Bull/Bear par actif (BTC, ETH, PAXG) = presets {@code <ACTIF> Perso Bear/Bull} de
 * {@code tools/pine/rainbow_dca_v4_atr_moon.pine} (SOURCE DE VÉRITÉ, jeux trouvés à la main sous TradingView) :
 * en cas d'écart, le pine a raison. Ils diffèrent aussi par des paramètres « globaux » (modulation ATH, moon),
 * d'où le jeu complet {@link RainbowAtrParamSet}. Multiplicateurs de zone et base = défauts du pine.
 */
public final class RainbowAtrPresets {

    public static final int BEAR = 0;
    public static final int BULL = 1;

    private static final ReentryMode T = ReentryMode.TRAILING_STOP;

    private RainbowAtrPresets() {
    }

    /** {@code [BEAR, BULL]} de l'actif ({@code BTC}, {@code ETH} ou {@code PAXG}). */
    public static List<RainbowAtrParamSet> bearBull(String asset) {
        return switch (asset) {
            case "BTC" -> List.of(
                    set("BTC Perso Bear", 36, 14, 2.5, 0, 0, 0, 2.5, 10, 15, 0.3, false, true,
                            60, 30, 0.5, 4.0, 4.0, 1.0, true, 30, 9, 100),
                    set("BTC Perso Bull", 36, 14, 1.0, 0, 0, 0, 2.5, 10, 15, 0.2, false, true,
                            60, 30, 0.5, 4.0, 2.0, 1.0, true, 30, 9, 100));
            case "ETH" -> List.of(
                    set("ETH Perso Bear Tuned", 26, 14, 2.7, 0, 0, 0, 1.7, 10, 3, 0.2, false, true,
                            60, 30, 0.5, 4.0, 4.0, 1.0, false, 50, 10, 50),
                    set("ETH Perso Bull Tuned", 26, 14, 2.0, 0, 0, 0, 2.5, 10, 3, 0.2, false, true,
                            60, 30, 0.5, 4.0, 4.0, 1.0, true, 30, 9, 100));
            case "PAXG" -> List.of(
                    set("PAXG Perso Bear", 24, 14, 2.7, 0, 0, 0, 1.7, 1, 10, 0.2, false, false,
                            30, 99, 0.5, 5.0, 3.0, 1.0, false, 75, 8, 50),
                    set("PAXG Perso Bull", 36, 28, 0, 0, 2.0, 2.0, 2.5, 3, 10, 0.2, false, true,
                            15, 10, 1.0, 1.0, 3.0, 0.5, true, 75, 8, 50));
            default -> throw new IllegalArgumentException("Actif sans jeux Bull/Bear : " + asset);
        };
    }

    /** Tous les presets reprennent : réentrées TRAILING_STOP, trailing achat 3 % / vente 5 %, blockBuy actif, sans cliquet. */
    private static RainbowAtrParamSet set(String name, int sma, int atr, double d2, double d1, double u1, double u2, double u3,
                                          int cooldown, int fixedDelay, double sellFraction,
                                          boolean allowSellCd, boolean cooldownAfterSellOn,
                                          double refBuy, double refSell, double buyMin, double buyMax,
                                          double sellMax, double sellMin,
                                          boolean moonOn, double moonReserve, double moonTrail, double moonStopSell) {
        RainbowAtrTuning t = new RainbowAtrTuning(sma, atr, d2, d1, u1, u2, u3, T, T, 3.0, 5.0,
                cooldown, fixedDelay, sellFraction, allowSellCd, cooldownAfterSellOn, true);
        RainbowAtrGlobals g = new RainbowAtrGlobals(true, refBuy, refSell, buyMin, buyMax, sellMax, sellMin,
                moonOn, moonReserve, false, moonTrail, moonStopSell, 2.0, 1.0, 0.5, 3.0, 1.0);
        return new RainbowAtrParamSet(name, t, g);
    }
}
