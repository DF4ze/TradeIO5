package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
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
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.CashLedger;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExposureLedger;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveDemand;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveModulation;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.SizingCore;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendRegime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rejeu complet MONO-ACTIF d'un lien, sans I/O (aucun Spring, aucune entité, aucun exchange) : Trend Mix → jeu Bull/Bear →
 * pas de la machine Rainbow sur la position VIRTUELLE du moteur → {@link LiveDemand} (vente = fraction x position tradable
 * SIMULÉE, {@code withLinkBaseAmount}) → {@link SizingCore} (même code que le live : modulation, cash) → wallet fermé
 * (cash, quantité, exposition au cours du jour). Capital fixé au départ, sans apport, sans frais ni minimum d'ordre (lots suivants).
 * <p>
 * Le moteur est amorcé comme le live : état de la machine par rejeu de {@code engineStart} à la veille de {@code startIdx}
 * ({@link RainbowAtrReplay#runFull}), ATH de la fenêtre D1 fournie (profondeur live = 720 jours en parité).
 */
public final class FullReplayEngine {

    private static final double EPSILON = 1e-9;
    private static final double PERCENT = 100.0;

    private FullReplayEngine() {
    }

    /**
     * @param candles        D1 du lien (même longueur que {@code ds})
     * @param engineStart    1re bougie du rejeu de l'état moteur (amorçage) ; {@code <= startIdx}
     * @param startIdx       1er jour dimensionné (wallet simulé)
     * @param endIdx         dernier jour (inclus)
     * @param cash0          capital de départ en USDC
     * @param qty0           position initiale (unités d'actif)
     * @param linkBaseAmount montant de base propre au lien ({@code null} ⇒ celui du jeu actif)
     * @param bagPercent     part (0-100) de la position offerte à la stratégie
     * @param priceOverride  cours du jour imposé (jour -> prix) : cours de décision enregistré par le live ; vide ⇒ clôture D1
     * @param athBefore      ATH de référence à la veille de chaque jour (ATH stocké, comme le live) ; jour absent ⇒ ATH courant de la fenêtre D1
     * @param rebootstrapDays jours où le live a ré-amorcé son état moteur (trou de passes la veille, config changée) : l'état est alors
     *                        recalculé par rejeu de la fenêtre ({@code windowMonths} mois) jusqu'à la veille, comme {@code loadOrBootstrap}
     * @param settingsByDay  réglages de modulation du jour (relus du journal live) ; jour absent ⇒ {@code settings}
     * @param pinnedWallet   wallet imposé en début de jour ({@code {cash, quantité}}, snapshot live) : isole la parité de la
     *                       DÉCISION du jour sans cascade d'un écart antérieur ; vide ⇒ wallet simulé en continu
     */
    public record Input(RainbowAtrDataset ds, List<MarketData> candles, TrendMixCalculator.Params trend, RangeMapping range,
                        RainbowAtrParamSet[] sets, int engineStart, int startIdx, int endIdx, double cash0, double qty0,
                        Double linkBaseAmount, double bagPercent, ModulationSettings settings, RainbowModulationControl limits,
                        Map<LocalDate, Double> priceOverride, Map<LocalDate, double[]> pinnedWallet,
                        Map<LocalDate, AthReference> athBefore, Map<LocalDate, ModulationSettings> settingsByDay,
                        java.util.Set<LocalDate> rebootstrapDays, int windowMonths) {
    }

    /**
     * Une journée rejouée. {@code cashBefore}/{@code qtyBefore} = wallet avant l'action (comparable au snapshot live) ;
     * {@code totalValue} = cash + actifs au cours après l'action. {@code fixed*} = DCA fixe de référence.
     */
    public record Day(LocalDate day, int index, double price, double dayClose, String setName, String regime,
                      double cashBefore, double qtyBefore, double tradableQty, double virtualPosition,
                      RainbowLiveAction action, LiveBlockReason blockReason, Double amountUsdc, Double quantity,
                      RainbowLiveAction wantedAction, double wantedAmountUsdc, double cashAfter, double qtyAfter, double assetsValue, double totalValue,
                      Map<String, Object> modulation, double fixedCash, double fixedQty, double fixedValue) {
    }

    /** Série journalière + valeurs finales (stratégie, DCA fixe de même base). */
    public record Result(List<Day> days, double cash0, double qty0, double finalValue, double fixedFinalValue) {
    }

    public static Result run(Input in) {
        RainbowAtrDataset ds = in.ds();
        RainbowAtrParamSet[] sets = in.sets();
        TrendMixCalculator.Params tp = in.trend();
        TrendRegime[] regime = TrendMixCalculator.compute(in.candles(), ds.sma(tp.smaPeriod()), ds.atr(tp.atrPeriod()), tp)
                .regime();
        int[] setOfBar = RainbowSetSelector.select(regime, in.range());

        // amorçage identique au live : état moteur et position virtuelle par rejeu jusqu'à la veille du 1er jour
        RainbowAtrReplay.Result boot = RainbowAtrReplay.runFull(ds, sets, setOfBar, in.engineStart(), in.startIdx() - 1);
        RainbowAtrState state = boot.finalState();
        double virtual = boot.finalPosition();
        AthReference ath = in.engineStart() > 0
                ? new AthReference(ds.ath(in.engineStart() - 1), ds.time(in.engineStart() - 1)) : AthReference.NONE;
        for (int j = in.engineStart(); j < in.startIdx(); j++) {
            ath = ath.observe(ds.high(j), ds.time(j));
        }

        double cash = in.cash0();
        double qty = in.qty0();
        double fixedCash = in.cash0();
        double fixedQty = in.qty0();
        List<Day> days = new ArrayList<>();
        for (int i = in.startIdx(); i <= in.endIdx(); i++) {
            boolean active = !RainbowAtrReplay.isInactive(setOfBar[i]);
            RainbowAtrParamSet set = sets[RainbowAtrReplay.setIndex(setOfBar[i])];
            LocalDate day = Instant.ofEpochMilli(ds.time(i)).atOffset(ZoneOffset.UTC).toLocalDate();
            double dayClose = ds.close(i);
            double high = ds.high(i);
            Double override = in.priceOverride().get(day);
            double price = override != null && override > 0 ? override : dayClose;
            if (i > in.startIdx() && in.rebootstrapDays().contains(day)) {
                int es = Math.min(firstIndexOnOrAfter(ds, day.minusMonths(in.windowMonths())), i);
                RainbowAtrReplay.Result reboot = RainbowAtrReplay.runFull(ds, sets, setOfBar, es, i - 1);
                state = reboot.finalState();
                virtual = reboot.finalPosition();
            }
            AthReference stepAth = in.athBefore().getOrDefault(day, ath);

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
                List<MarketData> partial = new ArrayList<>(in.candles().subList(0, i + 1));
                MarketData last = partial.get(i);
                partial.set(i, MarketData.builder().timeFrame(last.getTimeFrame()).timestamp(last.getTimestamp())
                        .pair(last.getPair()).open(last.getOpen()).high(last.getHigh().max(BigDecimal.valueOf(override)))
                        .low(last.getLow().min(BigDecimal.valueOf(override))).close(BigDecimal.valueOf(override))
                        .volume(last.getVolume()).build());
                RainbowAtrDataset pds = RainbowAtrDataset.fromMarketData(partial);
                TrendRegime[] preg = TrendMixCalculator.compute(partial, pds.sma(tp.smaPeriod()), pds.atr(tp.atrPeriod()), tp)
                        .regime();
                int[] pset = RainbowSetSelector.select(preg, in.range());
                decSet = sets[RainbowAtrReplay.setIndex(pset[i])];
                decRegime = preg[i];
                decStep = stepOn(pds, i, decSet, override, pds.high(i), state, stepAth, virtual, active);
            }
            RainbowSignal signal = decStep.signal();
            double base = decSet.globals().baseAmount();
            ReferenceSizer sizer = new ReferenceSizer(BigDecimal.valueOf(base));
            Sizer.Order order = sizer.size(signal, BigDecimal.valueOf(price), BigDecimal.valueOf(virtual));
            LiveDemand demand = new LiveDemand(order.buyAmount().doubleValue(),
                    tradable -> sizer.size(signal, BigDecimal.valueOf(price), BigDecimal.valueOf(tradable))
                            .sellQuantity().doubleValue(),
                    price, base).withLinkBaseAmount(in.linkBaseAmount());

            double[] pin = in.pinnedWallet().get(day);
            if (pin != null) {
                cash = pin[0];
                qty = pin[1];
            }
            double wantedSell = order.sellQuantity().doubleValue();
            RainbowLiveAction wanted = wantedSell > EPSILON ? RainbowLiveAction.SELL
                    : order.buyAmount().doubleValue() > EPSILON ? RainbowLiveAction.BUY : RainbowLiveAction.NONE;
            double wantedAmount = wanted == RainbowLiveAction.SELL ? wantedSell * price : order.buyAmount().doubleValue();
            double tradable = qty * in.bagPercent() / PERCENT;
            double assets = qty * price;
            ExposureLedger exposure = ExposureLedger.ofValues(assets, assets + cash);
            SizingCore.Decision decision = SizingCore.decide("ASSET", demand, tradable, new CashLedger(cash),
                    new LiveModulation(in.settingsByDay().getOrDefault(day, in.settings()), in.limits(), exposure));

            double cashBefore = cash;
            double qtyBefore = qty;
            if (decision.action() == RainbowLiveAction.BUY) {
                cash -= decision.amountUsdc();
                qty += decision.quantity();
            } else if (decision.action() == RainbowLiveAction.SELL) {
                cash += decision.amountUsdc();
                qty = Math.max(0.0, qty - decision.quantity());
            }
            // DCA fixe de référence : même base sur les mêmes barres actives, tant que le cash le permet (tout-ou-rien)
            double fixedBase = in.linkBaseAmount() != null && in.linkBaseAmount() > 0 ? in.linkBaseAmount() : base;
            if (active && fixedCash + EPSILON >= fixedBase) {
                fixedCash -= fixedBase;
                fixedQty += fixedBase / price;
            }
            double total = cash + qty * price;
            TrendRegime r = decRegime;
            days.add(new Day(day, i, price, dayClose, decSet.name(), r == null ? null : r.name(), cashBefore, qtyBefore,
                    tradable, virtual, decision.action(), decision.blockReason(), decision.amountUsdc(), decision.quantity(),
                    wanted, wantedAmount, cash, qty, qty * price, total, decision.journal(), fixedCash, fixedQty,
                    fixedCash + fixedQty * price));
            state = step.state();
            virtual = nextVirtual;
            ath = ath.observe(high, ds.time(i));
        }
        double finalValue = days.isEmpty() ? in.cash0() + in.qty0() : days.getLast().totalValue();
        double fixedFinal = days.isEmpty() ? in.cash0() + in.qty0() : days.getLast().fixedValue();
        return new Result(days, in.cash0(), in.qty0(), finalValue, fixedFinal);
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
