package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthReference;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrBand;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrReplay;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrStrategy;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal;
import fr.ses10doigts.tradeIO5.service.dca.atr.ReferenceSizer;
import fr.ses10doigts.tradeIO5.service.dca.atr.Sizer;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveDemand;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Signal d'UNE affectation/lien rejoué jour par jour, sans I/O : Trend Mix → jeu Bull/Bear → pas de la machine Rainbow sur la position
 * VIRTUELLE → {@link LiveDemand}. Machine d'état propre au lien (état, position virtuelle, ATH) ; le dimensionnement (cash,
 * exposition, wallet) est fait par le séquenceur ({@link MultiLinkReplayEngine}). Amorçage identique au live (rejeu de
 * {@code engineStart} à la veille de {@code startIdx}, ATH de la fenêtre D1).
 * <p>
 * Usage : {@link #prepare(int)} pour un jour, puis {@link #commit()} une fois la décision prise (avance l'état à la bougie finale).
 */
public final class LinkSignalReplay {

    private static final double EPSILON = 1e-9;

    /** Paramètres du signal d'un lien. */
    public record Config(RainbowAtrDataset ds, List<MarketData> candles, TrendMixCalculator.Params trend, RangeMapping range,
                  RainbowAtrParamSet[] sets, int engineStart, int startIdx, int endIdx, Double linkBaseAmount,
                  Map<LocalDate, Double> priceOverride, Map<LocalDate, AthReference> athBefore, Set<LocalDate> rebootstrapDays,
                  int windowMonths) {
    }

    /** Le jour préparé : demande du signal et éléments d'audit. */
    public record Prepared(LocalDate day, int index, double price, double dayClose, String setName, String regime, double virtualPosition,
                    LiveDemand demand, RainbowLiveAction wantedAction, double wantedAmountUsdc, boolean active,
                    double fixedBase) {
    }

    private final Config cfg;
    private final TrendRegime[] regime;
    private final int[] setOfBar;
    private RainbowAtrState state;
    private double virtual;
    private AthReference ath;
    // pas en attente de commit
    private RainbowAtrStrategy.StepResult pendingStep;
    private double pendingNextVirtual;
    private int pendingIdx = -1;

    LinkSignalReplay(Config cfg) {
        this.cfg = cfg;
        RainbowAtrDataset ds = cfg.ds();
        TrendMixCalculator.Params tp = cfg.trend();
        this.regime = TrendMixCalculator.compute(cfg.candles(), ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp).regime();
        this.setOfBar = RainbowSetSelector.select(regime, cfg.range());
        RainbowAtrReplay.Result boot = RainbowAtrReplay.runFull(ds, cfg.sets(), setOfBar, cfg.engineStart(), cfg.startIdx() - 1);
        this.state = boot.finalState();
        this.virtual = boot.finalPosition();
        AthReference a = cfg.engineStart() > 0
                ? new AthReference(ds.ath(cfg.engineStart() - 1), ds.time(cfg.engineStart() - 1)) : AthReference.NONE;
        for (int j = cfg.engineStart(); j < cfg.startIdx(); j++) {
            a = a.observe(ds.high(j), ds.time(j));
        }
        this.ath = a;
    }

    /** Jour calendaire de la bougie {@code i}. */
    LocalDate dayAt(int i) {
        return Instant.ofEpochMilli(cfg.ds().time(i)).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    Prepared prepare(int i) {
        RainbowAtrDataset ds = cfg.ds();
        RainbowAtrParamSet[] sets = cfg.sets();
        TrendMixCalculator.Params tp = cfg.trend();
        boolean active = !RainbowAtrReplay.isInactive(setOfBar[i]);
        RainbowAtrParamSet set = sets[RainbowAtrReplay.setIndex(setOfBar[i])];
        LocalDate day = dayAt(i);
        double dayClose = ds.close(i);
        double high = ds.high(i);
        Double override = cfg.priceOverride().get(day);
        double price = override != null && override > 0 ? override : dayClose;
        if (i > cfg.startIdx() && cfg.rebootstrapDays().contains(day)) {
            int es = Math.min(firstIndexOnOrAfter(ds, day.minusMonths(cfg.windowMonths())), i);
            RainbowAtrReplay.Result reboot = RainbowAtrReplay.runFull(ds, sets, setOfBar, es, i - 1);
            state = reboot.finalState();
            virtual = reboot.finalPosition();
        }
        AthReference stepAth = cfg.athBefore().getOrDefault(day, ath);

        // ÉTAT : avance sur la bougie FINALE (le live rejoue la veille sur la bougie clôturée : état, position virtuelle)
        RainbowAtrStrategy.StepResult step = stepOn(ds, i, set, dayClose, high, state, stepAth, virtual, active);
        Sizer.Order finalOrder = new ReferenceSizer(BigDecimal.valueOf(set.globals().baseAmount()))
                .size(step.signal(), BigDecimal.valueOf(dayClose), BigDecimal.valueOf(virtual));
        double nextVirtual = Math.max(0.0, virtual - finalOrder.sellQuantity().doubleValue()
                + finalOrder.buyAmount().doubleValue() / dayClose);

        // DÉCISION : le live décide à 23:55 sur la bougie encore ouverte (cours enregistré) ; sans cours enregistré = bougie finale
        RainbowAtrParamSet decSet = set;
        RainbowAtrStrategy.StepResult decStep = step;
        TrendRegime decRegime = regime[i];
        if (override != null && override > 0 && Math.abs(override - dayClose) > EPSILON) {
            List<MarketData> partial = new ArrayList<>(cfg.candles().subList(0, i + 1));
            MarketData last = partial.get(i);
            partial.set(i, MarketData.builder().timeFrame(last.getTimeFrame()).timestamp(last.getTimestamp())
                    .pair(last.getPair()).open(last.getOpen()).high(last.getHigh().max(BigDecimal.valueOf(override)))
                    .low(last.getLow().min(BigDecimal.valueOf(override))).close(BigDecimal.valueOf(override))
                    .volume(last.getVolume()).build());
            RainbowAtrDataset pds = RainbowAtrDataset.fromMarketData(partial);
            TrendRegime[] preg = TrendMixCalculator.compute(partial, pds.sma(tp.smaPeriod()), pds.atr(tp.atrPeriod()), tp)
                    .regime();
            int[] pset = RainbowSetSelector.select(preg, cfg.range());
            decSet = sets[RainbowAtrReplay.setIndex(pset[i])];
            decRegime = preg[i];
            decStep = stepOn(pds, i, decSet, override, pds.high(i), state, stepAth, virtual, active);
        }
        RainbowSignal signal = decStep.signal();
        double base = decSet.globals().baseAmount();
        ReferenceSizer sizer = new ReferenceSizer(BigDecimal.valueOf(base));
        Sizer.Order order = sizer.size(signal, BigDecimal.valueOf(price), BigDecimal.valueOf(virtual));
        LiveDemand demand = new LiveDemand(order.buyAmount().doubleValue(),
                tradable -> sizer.size(signal, BigDecimal.valueOf(price), BigDecimal.valueOf(tradable)).sellQuantity().doubleValue(),
                price, base).withLinkBaseAmount(cfg.linkBaseAmount());
        double wantedSell = order.sellQuantity().doubleValue();
        RainbowLiveAction wanted = wantedSell > EPSILON ? RainbowLiveAction.SELL
                : order.buyAmount().doubleValue() > EPSILON ? RainbowLiveAction.BUY : RainbowLiveAction.NONE;
        double wantedAmount = wanted == RainbowLiveAction.SELL ? wantedSell * price : order.buyAmount().doubleValue();
        double fixedBase = cfg.linkBaseAmount() != null && cfg.linkBaseAmount() > 0 ? cfg.linkBaseAmount() : base;

        pendingStep = step;
        pendingNextVirtual = nextVirtual;
        pendingIdx = i;
        return new Prepared(day, i, price, dayClose, decSet.name(), decRegime == null ? null : decRegime.name(), virtual, demand,
                wanted, wantedAmount, active, fixedBase);
    }

    /** Avance l'état du lien à la bougie finale du jour préparé. */
    void commit() {
        if (pendingIdx < 0) {
            throw new IllegalStateException("commit() sans prepare()");
        }
        state = pendingStep.state();
        virtual = pendingNextVirtual;
        ath = ath.observe(cfg.ds().high(pendingIdx), cfg.ds().time(pendingIdx));
        pendingIdx = -1;
    }

    /** Un pas de la machine Rainbow sur la bougie {@code i} de {@code data} avec le jeu {@code set} (bornes non calculables : pas invalide). */
    private static RainbowAtrStrategy.StepResult stepOn(RainbowAtrDataset data, int i, RainbowAtrParamSet set, double close,
                                                        double high, RainbowAtrState state, AthReference ath, double position,
                                                        boolean active) {
        double sma = data.sma(set.tuning().smaPeriod())[i];
        double atr = data.atr(set.tuning().atrPeriod())[i];
        if (Double.isNaN(sma) || Double.isNaN(atr)) {
            return RainbowAtrStrategy.stepInvalid(state, close, high, ath, set.globals(), active);
        }
        return RainbowAtrStrategy.step(state, RainbowAtrBand.of(close, sma, atr, set.tuning()), high, ath, position, set.tuning(),
                set.globals(), active);
    }

    private static int firstIndexOnOrAfter(RainbowAtrDataset ds, LocalDate from) {
        long fromMillis = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int k = 0; k < ds.size(); k++) {
            if (ds.time(k) >= fromMillis) {
                return k;
            }
        }
        return ds.size();
    }
}
