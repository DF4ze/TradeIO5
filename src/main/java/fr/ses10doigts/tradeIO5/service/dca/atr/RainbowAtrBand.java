package fr.ses10doigts.tradeIO5.service.dca.atr;

/**
 * L1 — sortie « bête » du Rainbow ATR pour UNE bougie : SMA, ATR, bornes et zone de la clôture. Aucun état,
 * aucun ATH, aucun montant. Fonction pure de (clôture, SMA, ATR, bornes du jeu de paramètres).
 */
public record RainbowAtrBand(double close, double sma, double atr,
                             double down2, double down1, double up1, double up2, double up3, int zone) {

    public static RainbowAtrBand of(double close, double sma, double atr, RainbowAtrTuning t) {
        double eb = sma - atr * t.atrMultDown2();
        double zb = sma - atr * t.atrMultDown1();
        double zh1 = sma + atr * t.atrMultUp1();
        double zh2 = sma + atr * t.atrMultUp2();
        double eh = sma + atr * t.atrMultUp3();
        int zone = close < eb ? RainbowAtrEngine.EXTREME_BAS
                : close < zb ? RainbowAtrEngine.X2
                : close < zh1 ? RainbowAtrEngine.X1
                : close < zh2 ? RainbowAtrEngine.X0_5
                : close < eh ? RainbowAtrEngine.NO_BUY : RainbowAtrEngine.EXTREME_HAUT;
        return new RainbowAtrBand(close, sma, atr, eb, zb, zh1, zh2, eh, zone);
    }
}
