package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.AthReference;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrParamSet;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSetSelector.RangeMapping;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.tree.trend.TrendMixCalculator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rejeu complet MONO-ACTIF d'un lien : cas particulier à un lien de {@link MultiLinkReplayEngine} (une seule implémentation du
 * séquencement). Trend Mix → jeu Bull/Bear → pas Rainbow sur la position VIRTUELLE → {@code LiveDemand} →
 * {@code SizingCore} (même code que le live) → wallet fermé (cash, quantité). Capital fixé au départ, sans apport, sans frais ni
 * minimum d'ordre. Amorçage, ATH, cours de décision 23:55 et ré-amorçage : voir {@link LinkSignalReplay}.
 */
public final class FullReplayEngine {

    private static final String ASSET = "ASSET";
    private static final long LINK_ID = 1L;

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
     * @param rebootstrapDays jours où le live a ré-amorcé son état moteur (trou de passes la veille, config changée)
     * @param settingsByDay  réglages de modulation du jour (relus du journal live) ; jour absent ⇒ {@code settings}
     * @param pinnedWallet   wallet imposé en début de jour ({@code {cash, quantité}}, snapshot live) ; vide ⇒ wallet simulé en continu
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
        LiveSlot slot = new LiveSlot(LINK_ID, 0L, ASSET, 0L, 0, in.bagPercent(), null);
        LinkSignalReplay.Config signal = new LinkSignalReplay.Config(in.ds(), in.candles(), in.trend(), in.range(), in.sets(),
                in.engineStart(), in.startIdx(), in.endIdx(), in.linkBaseAmount(), in.priceOverride(), in.athBefore(),
                in.rebootstrapDays(), in.windowMonths());
        Map<LocalDate, MultiLinkReplayEngine.Pin> pinned = new java.util.HashMap<>();
        in.pinnedWallet().forEach((day, w) -> pinned.put(day, new MultiLinkReplayEngine.Pin(w[0], Map.of(ASSET, w[1]), Map.of())));
        MultiLinkReplayEngine.Result multi = MultiLinkReplayEngine.run(new MultiLinkReplayEngine.Input(
                List.of(new MultiLinkReplayEngine.Link(slot, signal, in.qty0())), in.cash0(), in.settings(), in.limits(),
                in.settingsByDay(), pinned, Map.of(), Map.of()));
        List<Day> days = new ArrayList<>();
        for (int k = 0; k < multi.linkDays().size(); k++) {
            MultiLinkReplayEngine.LinkDay d = multi.linkDays().get(k);
            MultiLinkReplayEngine.WalletDay w = multi.walletDays().get(k);
            days.add(new Day(d.day(), d.index(), d.price(), d.dayClose(), d.setName(), d.regime(), d.cashBefore(), d.qtyBefore(),
                    d.tradableQty(), d.virtualPosition(), d.action(), d.blockReason(), d.amountUsdc(), d.quantity(),
                    d.wantedAction(), d.wantedAmountUsdc(), d.cashAfter(), d.qtyAfter(), d.assetsValue(), d.totalValue(),
                    d.modulation(), w.fixedCash(), w.fixedQty().getOrDefault(ASSET, 0.0), w.fixedValue()));
        }
        double finalValue = days.isEmpty() ? in.cash0() + in.qty0() : multi.finalValue();
        double fixedFinal = days.isEmpty() ? in.cash0() + in.qty0() : multi.fixedFinalValue();
        return new Result(days, in.cash0(), in.qty0(), finalValue, fixedFinal);
    }
}
