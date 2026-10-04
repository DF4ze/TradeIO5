package fr.ses10doigts.tradeIO5.service.tree.indicator.impl;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorContext;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorParameters;
import fr.ses10doigts.tradeIO5.model.dto.tree.indicator.IndicatorResult;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.IndicatorType;
import fr.ses10doigts.tradeIO5.service.dca.ReentryMode;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrEngine;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrGlobals;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrTuning;
import fr.ses10doigts.tradeIO5.service.tree.indicator.Indicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rainbow DCA "bornes ATR" v3 (ATH + To the moon) — adaptateur fin autour de {@link RainbowAtrEngine}
 * (couche moteur pure, port fidèle de {@code tools/pine/rainbow_dca_v4_atr_moon.pine}, source de vérité).
 * <p>
 * Décrit l'état du marché vu par le Rainbow à la DERNIÈRE bougie du dataset, sans aucune notion de
 * montant/portefeuille (le dimensionnement et le PnL restent dans le moteur/backtest) :
 * <ul>
 *   <li>{@code value} = SMA ; {@code values} : {@code atr}, bornes {@code extremeBas}/{@code zoneBasse}/
 *   {@code zoneHaute1}/{@code zoneHaute2}/{@code extremeHaut}, {@code zone} (0 EXTREME_BAS, 1 X2, 2 X1,
 *   3 X0_5, 4 NO_BUY, 5 EXTREME_HAUT) ;</li>
 *   <li>modulation ATH : {@code athDistance} (1 - close/ATH), {@code buyFactor}, {@code sellFactor}
 *   (neutralisés à 1.0 pendant To the moon) ; {@code moonMode} (0/1) ;</li>
 *   <li>état de la machine à armements, rejouée sur tout le dataset (comme le pine rejoue la plage
 *   visible) : {@code buyArmed}, {@code sellArmed}, {@code buyLocked}, {@code cooldownRemaining}.</li>
 * </ul>
 * ATH, To the moon et armements dépendent de l'historique : le rejeu part de la première bougie
 * exploitable du dataset, donc {@code lookback} (bougies D1, défaut {@value #DEFAULT_LOOKBACK}) conditionne
 * le résultat — plus il est long, plus l'ATH est fidèle. Paramètres facultatifs (défauts = pine, CUSTOM)
 * listés par les constantes {@code P_*}. {@link #getParametersNames()} ne liste que les 7 paramètres
 * de bornes, requis par {@code checkParameters}.
 */
@Component
public class RainbowAtrIndicator implements Indicator {

    private final Logger logger = LoggerFactory.getLogger(RainbowAtrIndicator.class);

    public static final int DEFAULT_LOOKBACK = 1000;

    public static final String P_SMA_PERIOD = "smaPeriod";
    public static final String P_ATR_PERIOD = "atrPeriod";
    public static final String P_MULT_DOWN2 = "atrMultDown2";
    public static final String P_MULT_DOWN1 = "atrMultDown1";
    public static final String P_MULT_UP1 = "atrMultUp1";
    public static final String P_MULT_UP2 = "atrMultUp2";
    public static final String P_MULT_UP3 = "atrMultUp3";
    public static final String P_LOOKBACK = "lookback";
    public static final String P_BUY_REENTRY_MODE = "buyReentryMode";
    public static final String P_SELL_REENTRY_MODE = "sellReentryMode";
    public static final String P_TRAILING_BUY_PCT = "trailingStopBuyPct";
    public static final String P_TRAILING_SELL_PCT = "trailingStopSellPct";
    public static final String P_COOLDOWN_DAYS = "cooldownDays";
    public static final String P_FIXED_DELAY_DAYS = "fixedDelayDays";
    public static final String P_SELL_FRACTION = "sellFraction";
    public static final String P_ALLOW_SELL_DURING_COOLDOWN = "allowSellDuringCooldown";
    public static final String P_COOLDOWN_AFTER_SELL_ON = "cooldownAfterSellOn";
    public static final String P_BLOCK_BUY_UNTIL_DOWN2 = "blockBuyAfterSellUntilDown2";
    public static final String P_ATH_ON = "athOn";
    public static final String P_ATH_REF_DD_BUY = "athRefDdBuyPct";
    public static final String P_ATH_REF_DD_SELL = "athRefDdSellPct";
    public static final String P_ATH_BUY_MIN = "athBuyMin";
    public static final String P_ATH_BUY_MAX = "athBuyMax";
    public static final String P_ATH_SELL_MAX = "athSellMax";
    public static final String P_ATH_SELL_MIN = "athSellMin";
    public static final String P_MOON_ON = "moonOn";
    public static final String P_MOON_RESERVE_PCT = "moonReservePct";
    public static final String P_MOON_RESERVE_RATCHET = "moonReserveRatchet";
    public static final String P_MOON_TRAILING_STOP_PCT = "moonTrailingStopPct";
    public static final String P_MOON_STOP_SELL_PCT = "moonStopSellPct";

    @Override
    public IndicatorType getType() {
        return IndicatorType.RAINBOW_ATR;
    }

    @Override
    public int getRequiredData(IndicatorParameters parameters) {
        RainbowAtrTuning t = tuning(parameters);
        int lookback = (int) num(parameters, P_LOOKBACK, DEFAULT_LOOKBACK);
        return Math.max(lookback, RainbowAtrDataset.warmup(t.smaPeriod(), t.atrPeriod()) + 2);
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
        RainbowAtrResult r = RainbowAtrEngine.simulate(ds, globals(parameters), tuning, start, ds.size() - 1);
        double sma = r.lastSma();
        double atr = r.lastAtr();

        Map<String, Double> values = new HashMap<>();
        values.put("atr", atr);
        values.put("extremeBas", sma - atr * tuning.atrMultDown2());
        values.put("zoneBasse", sma - atr * tuning.atrMultDown1());
        values.put("zoneHaute1", sma + atr * tuning.atrMultUp1());
        values.put("zoneHaute2", sma + atr * tuning.atrMultUp2());
        values.put("extremeHaut", sma + atr * tuning.atrMultUp3());
        values.put("zone", (double) r.lastZone());
        values.put("athDistance", r.lastAthDistance());
        values.put("buyFactor", r.lastBuyFactor());
        values.put("sellFactor", r.lastSellFactor());
        values.put("moonMode", r.moonModeLast() ? 1.0 : 0.0);
        values.put("buyArmed", r.buyArmed() ? 1.0 : 0.0);
        values.put("sellArmed", r.sellArmed() ? 1.0 : 0.0);
        values.put("buyLocked", r.buyLocked() ? 1.0 : 0.0);
        values.put("cooldownRemaining", (double) r.cooldownRemaining());

        return IndicatorResult.builder().value(sma).values(values).valid(true).build();
    }

    private static RainbowAtrTuning tuning(IndicatorParameters p) {
        RainbowAtrTuning d = RainbowAtrTuning.pineCustomDefault();
        return new RainbowAtrTuning(
                (int) num(p, P_SMA_PERIOD, d.smaPeriod()), (int) num(p, P_ATR_PERIOD, d.atrPeriod()),
                num(p, P_MULT_DOWN2, d.atrMultDown2()), num(p, P_MULT_DOWN1, d.atrMultDown1()),
                num(p, P_MULT_UP1, d.atrMultUp1()), num(p, P_MULT_UP2, d.atrMultUp2()), num(p, P_MULT_UP3, d.atrMultUp3()),
                mode(p, P_BUY_REENTRY_MODE, d.buyReentryMode()), mode(p, P_SELL_REENTRY_MODE, d.sellReentryMode()),
                num(p, P_TRAILING_BUY_PCT, d.trailingStopBuyPct()), num(p, P_TRAILING_SELL_PCT, d.trailingStopSellPct()),
                (int) num(p, P_COOLDOWN_DAYS, d.cooldownDays()), (int) num(p, P_FIXED_DELAY_DAYS, d.fixedDelayDays()),
                num(p, P_SELL_FRACTION, d.sellFraction()),
                bool(p, P_ALLOW_SELL_DURING_COOLDOWN, d.allowSellDuringCooldown()),
                bool(p, P_COOLDOWN_AFTER_SELL_ON, d.cooldownAfterSellOn()),
                bool(p, P_BLOCK_BUY_UNTIL_DOWN2, d.blockBuyAfterSellUntilDown2()));
    }

    private static RainbowAtrGlobals globals(IndicatorParameters p) {
        RainbowAtrGlobals d = RainbowAtrGlobals.pineDefault();
        return new RainbowAtrGlobals(
                bool(p, P_ATH_ON, d.athOn()), num(p, P_ATH_REF_DD_BUY, d.athRefDdBuyPct()), num(p, P_ATH_REF_DD_SELL, d.athRefDdSellPct()),
                num(p, P_ATH_BUY_MIN, d.athBuyMin()), num(p, P_ATH_BUY_MAX, d.athBuyMax()),
                num(p, P_ATH_SELL_MAX, d.athSellMax()), num(p, P_ATH_SELL_MIN, d.athSellMin()),
                bool(p, P_MOON_ON, d.moonOn()), num(p, P_MOON_RESERVE_PCT, d.moonReservePct()),
                bool(p, P_MOON_RESERVE_RATCHET, d.moonReserveRatchet()),
                num(p, P_MOON_TRAILING_STOP_PCT, d.moonTrailingStopPct()), num(p, P_MOON_STOP_SELL_PCT, d.moonStopSellPct()),
                d.multX2(), d.multX1(), d.multX0_5(), d.multTriggered(), d.baseAmount());
    }

    private static double num(IndicatorParameters p, String key, double def) {
        Double v = p.getNumerics() == null ? null : p.getNumerics().get(key);
        return v == null ? def : v;
    }

    private static boolean bool(IndicatorParameters p, String key, boolean def) {
        Boolean v = p.getBooleans() == null ? null : p.getBooleans().get(key);
        return v == null ? def : v;
    }

    private static ReentryMode mode(IndicatorParameters p, String key, ReentryMode def) {
        String v = p.getStrings() == null ? null : p.getStrings().get(key);
        return v == null ? def : ReentryMode.valueOf(v);
    }
}
