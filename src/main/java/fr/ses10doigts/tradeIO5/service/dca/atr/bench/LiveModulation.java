package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.modulation.ExposureModulator;
import fr.ses10doigts.tradeIO5.service.modulation.ExposureModulator.Band;
import fr.ses10doigts.tradeIO5.service.modulation.ExposureModulator.Factors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modulation des quantités d'un lien par l'utilisateur et l'admin : réglages effectifs de l'utilisateur (bande, puissance,
 * biais), paramètres admin et {@link ExposureLedger} du wallet (null ou inconnu ⇒ pas de modulation de bande, puissance et biais
 * restent appliqués). Chaîne : {@code signal x puissance·(1 ± biais) x facteur de bande}, produit plafonné à
 * {@code modulationProductMax}, puis plafond dur de bande. Ne crée jamais d'action : le Rainbow reste seul à acheter et vendre,
 * ce calcul ne fait que moduler la quantité demandée. Produit le journal JSON de ce que chaque terme a valu.
 */
final class LiveModulation {

    static final String STATUS_OK = "OK";
    static final String STATUS_UNKNOWN = "UNKNOWN";
    private static final double ROUNDING = 1e6;

    /** Quantités modulées (avant plafonds cash/position) et journal à compléter puis sérialiser. */
    record Outcome(double buyAmount, double sellQty, Map<String, Object> journal) {
    }

    private final ModulationSettings settings;
    private final RainbowModulationControl limits;
    private final ExposureLedger exposure;

    LiveModulation(ModulationSettings settings, RainbowModulationControl limits, ExposureLedger exposure) {
        this.settings = settings;
        this.limits = limits;
        this.exposure = exposure;
    }

    ExposureLedger exposure() {
        return exposure;
    }

    Outcome modulate(double signalBuyUsd, double signalSellQty, double close) {
        boolean known = exposure != null && exposure.isKnown();
        Band band = new Band(settings.exposureMin(), settings.exposureMax(), limits.getRegulationMarginPct().doubleValue(),
                limits.getSellBoostMax().doubleValue());
        Factors factors = known ? ExposureModulator.factors(exposure.exposurePct(), band) : new Factors(1.0, 1.0, 0.0, 0.0);
        double sideBuy = settings.power() * (1.0 + settings.bias());
        double sideSell = settings.power() * (1.0 - settings.bias());
        double productMax = limits.getModulationProductMax().doubleValue();
        double productBuy = Math.min(sideBuy * factors.buyFactor(), productMax);
        double productSell = Math.min(sideSell * factors.sellFactor(), productMax);
        double buy = signalBuyUsd * productBuy;
        double sell = signalSellQty * productSell;
        boolean hardCap = false;
        if (known) {
            double buyCap = ExposureModulator.buyCapUsd(exposure.assetsValue(), exposure.totalValue(), band);
            double sellCap = ExposureModulator.sellCapUsd(exposure.assetsValue(), exposure.totalValue(), band) / close;
            hardCap = buy > buyCap || sell > sellCap;
            buy = Math.min(buy, buyCap);
            sell = Math.min(sell, sellCap);
        }
        Map<String, Object> journal = new LinkedHashMap<>();
        journal.put("signalBuyUsd", round(signalBuyUsd));
        journal.put("signalSellQty", round(signalSellQty));
        journal.put("power", round(settings.power()));
        journal.put("bias", round(settings.bias()));
        journal.put("sideBuy", round(sideBuy));
        journal.put("sideSell", round(sideSell));
        journal.put("bandMin", round(settings.exposureMin()));
        journal.put("bandMax", round(settings.exposureMax()));
        journal.put("exposureStatus", known ? STATUS_OK : STATUS_UNKNOWN);
        if (known) {
            journal.put("exposureBefore", round(exposure.exposurePct()));
        }
        journal.put("lHigh", round(factors.highL()));
        journal.put("lLow", round(factors.lowL()));
        journal.put("bandBuy", round(factors.buyFactor()));
        journal.put("bandSell", round(factors.sellFactor()));
        journal.put("productBuy", round(productBuy));
        journal.put("productSell", round(productSell));
        journal.put("productCapped", sideBuy * factors.buyFactor() > productMax || sideSell * factors.sellFactor() > productMax);
        journal.put("hardCap", hardCap);
        journal.put("marginPct", round(band.marginPct()));
        journal.put("sellBoostMax", round(band.boost()));
        journal.put("productMax", round(productMax));
        journal.put("modulatedBuyUsd", round(buy));
        journal.put("modulatedSellQty", round(sell));
        return new Outcome(buy, sell, journal);
    }

    private static double round(double value) {
        return Math.round(value * ROUNDING) / ROUNDING;
    }
}
