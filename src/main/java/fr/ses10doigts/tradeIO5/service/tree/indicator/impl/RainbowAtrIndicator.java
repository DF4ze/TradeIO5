package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrBand;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rainbow DCA "bornes ATR" — L1 « bête » : calcul pur sur la fenêtre D1 fournie (SMA, ATR, bornes, zone de la
 * DERNIÈRE bougie), sans état, sans ATH, sans armement ni montant. Les couches au-dessus
 * ({@link fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrStrategy} : machine d'états ;
 * {@link fr.ses10doigts.tradeIO5.service.dca.atr.AthReference} : ATH par actif ;
 * {@link fr.ses10doigts.tradeIO5.service.dca.atr.Sizer} : quantification) sont hors de l'indicateur.
 * Formules fidèles à {@code tools/pine/rainbow_dca_v4_atr_moon.pine} (source de vérité).
 * <ul>
 *   <li>{@code value} = SMA ; {@code values} : {@code atr}, bornes {@code extremeBas}/{@code zoneBasse}/
 *   {@code zoneHaute1}/{@code zoneHaute2}/{@code extremeHaut}, {@code zone} (0 EXTREME_BAS, 1 X2, 2 X1,
 *   3 X0_5, 4 NO_BUY, 5 EXTREME_HAUT).</li>
 * </ul>
 * Fenêtre : la RMA de l'ATR est amorcée au début de la fenêtre ; 10 × période d'ATR suffit pour un écart ≤ 0,02 %
 * avec un calcul depuis la 1re bougie ({@link RainbowAtrDataset#recommendedWindow}), c'est {@link #getRequiredData}.
 * Paramètres : les 7 bornes ({@link #getParametersNames()}).
 */
@Component
public class RainbowAtrIndicator implements Indicator {

    private final Logger logger = LoggerFactory.getLogger(RainbowAtrIndicator.class);

    public static final String P_SMA_PERIOD = "smaPeriod";
    public static final String P_ATR_PERIOD = "atrPeriod";
    public static final String P_MULT_DOWN2 = "atrMultDown2";
    public static final String P_MULT_DOWN1 = "atrMultDown1";
    public static final String P_MULT_UP1 = "atrMultUp1";
    public static final String P_MULT_UP2 = "atrMultUp2";
    public static final String P_MULT_UP3 = "atrMultUp3";

    @Override
    public IndicatorType getType() {
        return IndicatorType.RAINBOW_ATR;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        RainbowAtrTuning t = tuning(parameters);
        return RainbowAtrDataset.recommendedWindow(t.smaPeriod(), t.atrPeriod());
    }

    @Override
    public List<String> getParametersNames() {
        return List.of(P_SMA_PERIOD, P_ATR_PERIOD, P_MULT_DOWN2, P_MULT_DOWN1, P_MULT_UP1, P_MULT_UP2, P_MULT_UP3);
    }

    @Override
    public IndicatorResult compute(IndicatorContext context, IndicatorParameters parameters) {
        List<MarketData> data = context.marketDataset().getMarketDatas();
        RainbowAtrTuning tuning = tuning(parameters);
        if (!tuning.isValid()) {
            logger.error("Invalid parameters : ATR multipliers must satisfy down2>=down1>=0 and 0<=up1<=up2<=up3");
            return IndicatorResult.invalid();
        }
        int start = RainbowAtrDataset.warmup(tuning.smaPeriod(), tuning.atrPeriod());
        if (data == null || data.size() <= start + 1) {
            logger.error("Invalid context : MarketData size too short for RAINBOW_ATR");
            return IndicatorResult.invalid();
        }
        for (MarketData m : data) {
            if (m.getHigh() == null || m.getLow() == null || m.getClose() == null || m.getTimestamp() == null) {
                logger.error("Invalid context : timestamp/high/low/close required for RAINBOW_ATR");
                return IndicatorResult.invalid();
            }
        }

        RainbowAtrDataset ds = RainbowAtrDataset.fromMarketData(data);
        int last = ds.size() - 1;
        RainbowAtrBand b = RainbowAtrBand.of(ds.close(last), ds.sma(tuning.smaPeriod())[last],
                ds.atr(tuning.atrPeriod())[last], tuning);

        Map<String, Double> values = new HashMap<>();
        values.put("atr", b.atr());
        values.put("extremeBas", b.down2());
        values.put("zoneBasse", b.down1());
        values.put("zoneHaute1", b.up1());
        values.put("zoneHaute2", b.up2());
        values.put("extremeHaut", b.up3());
        values.put("zone", (double) b.zone());

        return IndicatorResult.builder().value(b.sma()).values(values).valid(true).build();
    }

    private static RainbowAtrTuning tuning(IndicatorParameters p) {
        RainbowAtrTuning d = RainbowAtrTuning.pineCustomDefault();
        return new RainbowAtrTuning(
                (int) num(p, P_SMA_PERIOD, d.smaPeriod()), (int) num(p, P_ATR_PERIOD, d.atrPeriod()),
                num(p, P_MULT_DOWN2, d.atrMultDown2()), num(p, P_MULT_DOWN1, d.atrMultDown1()),
                num(p, P_MULT_UP1, d.atrMultUp1()), num(p, P_MULT_UP2, d.atrMultUp2()), num(p, P_MULT_UP3, d.atrMultUp3()),
                d.buyReentryMode(), d.sellReentryMode(), d.trailingStopBuyPct(), d.trailingStopSellPct(),
                d.cooldownDays(), d.fixedDelayDays(), d.sellFraction(),
                d.allowSellDuringCooldown(), d.cooldownAfterSellOn(), d.blockBuyAfterSellUntilDown2());
    }

    private static double num(IndicatorParameters p, String key, double def) {
        Double v = p.getNumerics() == null ? null : p.getNumerics().get(key);
        return v == null ? def : v;
    }
}
