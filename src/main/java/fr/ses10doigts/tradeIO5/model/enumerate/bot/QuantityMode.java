package fr.ses10doigts.tradeIO5.model.enumerate.bot;

public enum QuantityMode {
        PERCENT,       // % du portefeuille
        UNIT,          // unité fixe (ex. 25 USDC)
        UNIT_COEFF     // unité * coefficient dynamique
    }