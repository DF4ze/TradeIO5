package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ChartDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.WalletDto;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrBand;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowSignal;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.CostDay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.LinkDay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.SignalDay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.TradeDay;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine.WalletDay;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Graphique Rainbow d'UN lien du rejeu libre, fonction PURE : mêmes colonnes que l'onglet Rainbow ({@code RainbowReplayColumns},
 * donc le même composant graphique), alimentées par le rejeu du WALLET (cash commun) et non par un rejeu mono-actif. Jours = ceux
 * du lien (bougie présente).
 * <ul>
 *   <li>bornes ATR, ATH, zone, moon : audit du signal de la décision du jour ({@link LinkSignalReplay.Audit}) ;</li>
 *   <li>achats / ventes : montants et quantités RÉELLEMENT échangés (nets de frais, {@link TradeDay}), type de signal
 *       ({@code buyKind} 1 zone / 2 déclenché, {@code sellKind} 1 vente / 2 stop moon) ;</li>
 *   <li>{@code blocked} : ordre voulu mais non passé (1 achat bloqué, 2 vente bloquée, 3 sous le minimum d'ordre), montant voulu
 *       dans {@code blockedAmt} ;</li>
 *   <li>{@code pos} : quantité détenue en fin de jour ; PnL au prix moyen pondéré, frais inclus (même définition que
 *       {@link EpisodeTimeline}), position d'ouverture valorisée au cours du 1er jour ; DCA fixe de référence sans mise à l'échelle
 *       ({@code fixedInvested} nul : sa mise est déjà celle de la stratégie).</li>
 * </ul>
 */
public final class AssetChartBuilder {

    private static final double EPSILON = 1e-12;

    private AssetChartBuilder() {
    }

    /** Colonnes en cours de remplissage (une liste par nom, même longueur). */
    private static final class Cols {
        final Map<String, List<Number>> m = new LinkedHashMap<>();

        void add(String name, double v) {
            m.computeIfAbsent(name, k -> new ArrayList<>()).add(Double.isNaN(v) || Double.isInfinite(v) ? null : v);
        }

        void add(String name, int v) {
            m.computeIfAbsent(name, k -> new ArrayList<>()).add(v);
        }

        void addNull(String name) {
            m.computeIfAbsent(name, k -> new ArrayList<>()).add(null);
        }
    }

    /**
     * @param bindingId lien (clé dans {@code result})
     * @param asset     symbole de l'actif
     * @param ds        bougies D1 du lien
     * @param cash0     capital de départ du wallet
     * @param qty0      position d'ouverture de l'actif
     * @return le graphique, ou {@code null} si le lien n'a aucun jour rejoué
     */
    public static ChartDto build(Long bindingId, String asset, RainbowAtrDataset ds, MultiLinkReplayEngine.Result result,
                                 double cash0, double qty0) {
        List<LinkDay> days = result.linkDays().stream().filter(x -> x.bindingId().equals(bindingId)).toList();
        if (days.isEmpty()) {
            return null;
        }
        Map<LocalDate, TradeDay> trade = new HashMap<>();
        result.trades().stream().filter(x -> x.bindingId().equals(bindingId)).forEach(x -> trade.put(x.day(), x));
        Map<LocalDate, SignalDay> signal = new HashMap<>();
        result.signals().stream().filter(x -> x.bindingId().equals(bindingId)).forEach(x -> signal.put(x.day(), x));
        Map<LocalDate, CostDay> cost = new HashMap<>();
        result.costDays().stream().filter(x -> x.bindingId().equals(bindingId)).forEach(x -> cost.put(x.day(), x));
        Map<LocalDate, WalletDay> wallet = new HashMap<>();
        result.walletDays().forEach(w -> wallet.put(w.day(), w));

        List<String> d = new ArrayList<>();
        List<Integer> trend = new ArrayList<>();
        List<Integer> active = new ArrayList<>();
        List<Number> wAsset = new ArrayList<>();
        List<Number> wStable = new ArrayList<>();
        List<Number> wTotal = new ArrayList<>();
        Cols c = new Cols();

        double price0 = trade.get(days.getFirst().day()).price();
        double qty = qty0;
        double basis = qty0 * price0;
        double realized = 0.0;
        double invested = 0.0;
        double proceeds = 0.0;
        double fixedQty = 0.0;
        double fixedCash = 0.0;
        int set = 0;
        for (LinkDay ld : days) {
            LocalDate day = ld.day();
            TradeDay td = trade.get(day);
            SignalDay sd = signal.get(day);
            RainbowSignal sig = sd == null || sd.audit() == null ? null : sd.audit().signal();
            RainbowAtrBand band = sd == null || sd.audit() == null ? null : sd.audit().band();
            int i = ld.index();
            double price = td.price();
            d.add(day.toString());
            String reg = td.regime();
            trend.add("UP".equals(reg) ? 1 : "DOWN".equals(reg) ? 2 : 0);
            active.add(reg != null ? 1 : 0);
            if ("UP".equals(reg)) {
                set = 1;
            } else if ("DOWN".equals(reg)) {
                set = 0;
            }
            c.add("o", ds.open(i));
            c.add("h", ds.high(i));
            c.add("l", ds.low(i));
            c.add("c", ds.close(i));
            c.add("set", set);
            // bornes ATR, ATH, zone
            c.add("sma", band == null ? Double.NaN : band.sma());
            c.add("atr", band == null ? Double.NaN : band.atr());
            c.add("down2", band == null ? Double.NaN : band.down2());
            c.add("down1", band == null ? Double.NaN : band.down1());
            c.add("up1", band == null ? Double.NaN : band.up1());
            c.add("up2", band == null ? Double.NaN : band.up2());
            c.add("up3", band == null ? Double.NaN : band.up3());
            c.add("zone", td.zone());
            c.add("ath", sd == null || sd.audit() == null ? Double.NaN : sd.audit().ath());
            c.add("athDist", sig == null ? Double.NaN : sig.athDistance());
            c.add("buyFac", sig == null ? Double.NaN : sig.buyAthFactor());
            c.add("sellFac", sig == null ? Double.NaN : sig.sellAthFactor());
            c.add("multZone", sig == null ? Double.NaN : sig.multZone());
            c.add("moon", sig != null && sig.moonMode() ? 1 : 0);
            c.add("moonEntry", sig != null && sig.moonEntry() ? 1 : 0);
            c.add("buyArmNow", sig != null && sig.buyArmedNow() ? 1 : 0);
            c.add("sellArmNow", sig != null && sig.sellArmedNow() ? 1 : 0);

            // ordres réellement passés (échanges nets de frais), puis ordres voulus non passés
            boolean bought = td.qtyDelta() > EPSILON;
            boolean sold = td.qtyDelta() < -EPSILON;
            int buyKind = bought ? Math.max(1, sig == null ? 1 : sig.buyKind().ordinal()) : 0;
            int sellKind = sold ? Math.max(1, sig == null ? 1 : sig.sellKind().ordinal()) : 0;
            c.add("buyKind", buyKind);
            c.add("buyAmt", bought ? -td.cashDelta() : 0.0);
            c.add("buyQty", bought ? td.qtyDelta() : 0.0);
            c.add("sellKind", sellKind);
            c.add("sellProc", sold ? td.cashDelta() : 0.0);
            c.add("sellQty", sold ? -td.qtyDelta() : 0.0);
            c.add("sellFrac", sold && sig != null ? sig.sellFraction() : 0.0);
            int blocked = 0;
            if (ld.wantedAction() != RainbowLiveAction.NONE && !bought && !sold) {
                CostDay cd = cost.get(day);
                blocked = cd != null && "BELOW_MIN".equals(cd.outcome()) ? 3 : ld.wantedAction() == RainbowLiveAction.BUY ? 1 : 2;
            }
            c.add("blocked", blocked);
            c.add("blockedAmt", blocked > 0 ? ld.wantedAmountUsdc() : 0.0);

            // position, PnL au prix moyen pondéré (frais inclus)
            if (td.qtyDelta() > EPSILON) {
                basis += -td.cashDelta();
                qty += td.qtyDelta();
                invested += -td.cashDelta();
            } else if (td.qtyDelta() < -EPSILON) {
                double qSold = -td.qtyDelta();
                double avg = qty > 0 ? basis / qty : 0.0;
                realized += td.cashDelta() - avg * qSold;
                basis -= avg * qSold;
                qty = Math.max(0.0, qty - qSold);
                proceeds += td.cashDelta();
            }
            double latent = qty * price - basis;
            fixedQty += td.fixedQtyDelta();
            fixedCash += td.fixedCashDelta();
            c.add("pos", ld.qtyAfter());
            c.add("reserve", sd == null || sd.audit() == null || Double.isNaN(sd.audit().reserveQty()) ? 0.0 : sd.audit().reserveQty());
            c.add("bagValue", ld.qtyAfter() * ld.dayClose());
            c.add("invested", invested);
            c.add("proceeds", proceeds);
            c.add("realized", realized);
            c.add("potential", latent);
            c.add("total", realized + latent);
            c.add("fixedGain", fixedQty * price + fixedCash - qty0 * price0);
            c.addNull("fixedInvested");

            WalletDay w = wallet.get(day);
            wAsset.add(w == null ? null : w.value().getOrDefault(asset, 0.0));
            wStable.add(w == null ? null : w.cash());
            wTotal.add(w == null ? null : w.totalValue());
        }
        return new ChartDto(d, trend, active, c.m, new WalletDto(cash0, wAsset, wStable, wTotal), List.of(), Map.of());
    }
}
