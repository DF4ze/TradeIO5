package fr.ses10doigts.tradeIO5.service.dca.atr;

import com.fasterxml.jackson.annotation.JsonIgnore;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;

/**
 * Paramètres "bornes + mécanisme" du Rainbow DCA ATR (pine {@code tools/pine/rainbow_dca_v4_atr_moon.pine},
 * source de vérité) : ce sont ceux qui peuvent changer selon le régime de Trend (cf.
 * {@link RainbowAtrEngine}, {@code tunings[régime]}). Les paramètres ATH/To the moon/multiplicateurs de
 * zone sont globaux, cf. {@link RainbowAtrGlobals}.
 *
 * @param smaPeriod                  période SMA centrale des bornes
 * @param atrPeriod                  période ATR (RMA de Pine)
 * @param atrMultDown2               borne EXTREME_BAS = SMA - ATR*mult
 * @param atrMultDown1               borne basse zone X2/X1
 * @param atrMultUp1                 borne haute X1/X0_5
 * @param atrMultUp2                 borne haute X0_5/NO_BUY
 * @param atrMultUp3                 borne EXTREME_HAUT
 * @param buyReentryMode             sortie de l'armement achat
 * @param sellReentryMode            sortie de l'armement vente
 * @param trailingStopBuyPct         rebond % depuis le plus bas (TRAILING_STOP achat)
 * @param trailingStopSellPct        repli % depuis le plus haut (TRAILING_STOP vente)
 * @param cooldownDays               cooldown posé après une vente
 * @param fixedDelayDays             délai FIXED_DELAY (partagé achat/vente)
 * @param sellFraction               fraction de la position vendue
 * @param allowSellDuringCooldown    autorise l'armement de vente pendant le cooldown (jamais l'achat)
 * @param cooldownAfterSellOn        si false, aucun cooldown posé après une vente (ni stop moon)
 * @param blockBuyAfterSellUntilDown2 après une vente, bloque les achats jusqu'à un nouveau franchissement sous DOWN2
 */
public record RainbowAtrTuning(
        int smaPeriod, int atrPeriod,
        double atrMultDown2, double atrMultDown1, double atrMultUp1, double atrMultUp2, double atrMultUp3,
        ReentryMode buyReentryMode, ReentryMode sellReentryMode,
        double trailingStopBuyPct, double trailingStopSellPct,
        int cooldownDays, int fixedDelayDays, double sellFraction,
        boolean allowSellDuringCooldown, boolean cooldownAfterSellOn, boolean blockBuyAfterSellUntilDown2
) {

    /** Valeurs par défaut du mode CUSTOM du pine. */
    public static RainbowAtrTuning pineCustomDefault() {
        return new RainbowAtrTuning(36, 14, 2.0, 1.0, 0.0, 0.0, 2.5,
                ReentryMode.TRAILING_STOP, ReentryMode.TRAILING_STOP, 3.0, 5.0,
                10, 15, 0.20, false, true, true);
    }

    /** Preset "finalCandidate" du pine (ancien preset pine par régime, conservé comme référence du bench). */
    public static RainbowAtrTuning pineFinalCandidate() {
        return new RainbowAtrTuning(20, 14, 4.0, 2.0, 1.0, 2.0, 4.0,
                ReentryMode.FIXED_DELAY, ReentryMode.FIXED_DELAY, 3.0, 3.0,
                7, 15, 0.10, false, true, true);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Copie modifiable, pour dériver des variantes par nom de paramètre (bench). */
    public static final class Builder {
        private int smaPeriod, atrPeriod, cooldownDays, fixedDelayDays;
        private double down2, down1, up1, up2, up3, trailBuy, trailSell, sellFraction;
        private ReentryMode buyMode, sellMode;
        private boolean allowSell, cooldownOn, block;

        private Builder(RainbowAtrTuning t) {
            smaPeriod = t.smaPeriod; atrPeriod = t.atrPeriod;
            down2 = t.atrMultDown2; down1 = t.atrMultDown1; up1 = t.atrMultUp1; up2 = t.atrMultUp2; up3 = t.atrMultUp3;
            buyMode = t.buyReentryMode; sellMode = t.sellReentryMode;
            trailBuy = t.trailingStopBuyPct; trailSell = t.trailingStopSellPct;
            cooldownDays = t.cooldownDays; fixedDelayDays = t.fixedDelayDays; sellFraction = t.sellFraction;
            allowSell = t.allowSellDuringCooldown; cooldownOn = t.cooldownAfterSellOn; block = t.blockBuyAfterSellUntilDown2;
        }

        public Builder set(String name, Object v) {
            switch (name) {
                case "smaPeriod" -> smaPeriod = (Integer) v;
                case "atrPeriod" -> atrPeriod = (Integer) v;
                case "atrMultDown2" -> down2 = (Double) v;
                case "atrMultDown1" -> down1 = (Double) v;
                case "atrMultUp1" -> up1 = (Double) v;
                case "atrMultUp2" -> up2 = (Double) v;
                case "atrMultUp3" -> up3 = (Double) v;
                case "buyReentryMode" -> buyMode = (ReentryMode) v;
                case "sellReentryMode" -> sellMode = (ReentryMode) v;
                case "trailingStopBuyPct" -> trailBuy = (Double) v;
                case "trailingStopSellPct" -> trailSell = (Double) v;
                case "cooldownDays" -> cooldownDays = (Integer) v;
                case "fixedDelayDays" -> fixedDelayDays = (Integer) v;
                case "sellFraction" -> sellFraction = (Double) v;
                case "allowSellDuringCooldown" -> allowSell = (Boolean) v;
                case "cooldownAfterSellOn" -> cooldownOn = (Boolean) v;
                case "blockBuyAfterSellUntilDown2" -> block = (Boolean) v;
                default -> throw new IllegalArgumentException("Paramètre inconnu : " + name);
            }
            return this;
        }

        public RainbowAtrTuning build() {
            return new RainbowAtrTuning(smaPeriod, atrPeriod, down2, down1, up1, up2, up3, buyMode, sellMode,
                    trailBuy, trailSell, cooldownDays, fixedDelayDays, sellFraction, allowSell, cooldownOn, block);
        }
    }

    /** Monotonie des bornes (même règle que {@code RainbowDcaBacktestService#validate} en mode ATR). */
    @JsonIgnore
    public boolean isValid() {
        return smaPeriod > 0 && atrPeriod > 0 && fixedDelayDays > 0 && cooldownDays >= 0
                && sellFraction > 0 && sellFraction <= 1.0
                && atrMultDown1 >= 0 && atrMultDown2 >= atrMultDown1
                && atrMultUp1 >= 0 && atrMultUp1 <= atrMultUp2 && atrMultUp2 <= atrMultUp3;
    }
}
