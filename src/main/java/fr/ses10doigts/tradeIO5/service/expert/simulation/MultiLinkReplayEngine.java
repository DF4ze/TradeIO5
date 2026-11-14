package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.LiveBlockReason;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.service.UserTradingSettingsService.ModulationSettings;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.CashLedger;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.ExposureLedger;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveModulation;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.LiveSlot;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.SizingCore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Rejeu complet MULTI-LIENS d'UN wallet, sans I/O : N liens (actifs) partagent le même cash USDC et la même exposition, comme la
 * passe quotidienne live ({@code RainbowLiveExecutionService#runLinks}). Par jour : signal de chaque lien ({@link LinkSignalReplay}),
 * puis liens servis dans l'ordre {@link LiveSlot#SERVICE_ORDER} : achat accepté ⇒ cash réservé (tout-ou-rien), les ventes ne
 * créditent PAS le cash le jour même, chaque action acceptée met à jour l'exposition ({@code P}) du lien suivant ({@code V} constant).
 * Le wallet n'est mis à jour qu'une fois tous les liens du jour décidés. Exposition valorisée aux cours des demandes du jour ; un actif
 * détenu sans prix ce jour-là ⇒ exposition inconnue (règle de {@link ExposureLedger#of}). Mono-lien = cas particulier
 * ({@link FullReplayEngine} délègue ici). Avec {@link Costs} : frais / spread / slippage, minimum d'ordre du lien et carry via {@link OrderReplay} (même calcul de route
 * que le plan d'ordres) ; sans : comportement des lots 1-2 (ni frais, ni minimum, ni carry).
 */
public final class MultiLinkReplayEngine {

    private static final double EPSILON = 1e-9;

    private MultiLinkReplayEngine() {
    }

    /** Un lien du wallet : identité de service (priorité, actif, bag), signal et position initiale de son actif. */
    public record Link(LiveSlot slot, LinkSignalReplay.Config signal, double qty0) {
    }

    /**
     * Wallet imposé en début de jour (snapshots live) : cash, quantité par actif et cash déjà réservé par les liens servis avant
     * chaque lien ({@code bindingId -> reserved}). Isole la parité de la DÉCISION du jour sans cascade d'un écart antérieur.
     */
    public record Pin(double cash, Map<String, Double> qty, Map<Long, Double> reservedBefore) {
    }

    /**
     * @param links        liens du wallet (ordre quelconque : le service suit {@link LiveSlot#SERVICE_ORDER})
     * @param cash0        cash USDC de départ du wallet
     * @param settings     réglages de modulation par défaut ; {@code settingsByDay} (journal live) les remplace jour par jour
     * @param pinned       wallet imposé par jour (parité « recalé ») ; vide ⇒ wallet simulé en continu
     * @param frozenQty    actifs détenus SANS lien : valorisés (exposition) mais jamais traités
     * @param frozenCloses cours par actif gelé et par jour ; jour sans cours ⇒ exposition inconnue (comme le live)
     */
    public record Input(List<Link> links, double cash0, ModulationSettings settings, RainbowModulationControl limits,
                        Map<LocalDate, ModulationSettings> settingsByDay, Map<LocalDate, Pin> pinned,
                        Map<String, Double> frozenQty, Map<String, Map<LocalDate, Double>> frozenCloses, Costs costs) {

        /** Sans coûts (lots 1-2). */
        public Input(List<Link> links, double cash0, ModulationSettings settings, RainbowModulationControl limits,
                     Map<LocalDate, ModulationSettings> settingsByDay, Map<LocalDate, Pin> pinned,
                     Map<String, Double> frozenQty, Map<String, Map<LocalDate, Double>> frozenCloses) {
            this(links, cash0, settings, limits, settingsByDay, pinned, frozenQty, frozenCloses, null);
        }
    }

    /** Coûts : calcul d'ordre, achat minimum et route imposée par lien ({@code bindingId}). */
    public record Costs(OrderReplay orders, Map<Long, Double> minBuyUsd, Map<Long, String> preferredRoute) {
    }

    /**
     * Ordre réellement passé par un lien un jour donné (avec coûts) : {@code outcome} du plan ({@code OK}, {@code BELOW_MIN} ou
     * blocage), mise effective (jour + carry), carry entrant / sortant, route, coût % et USDC, niveau Fee Test, cash et quantité
     * réellement échangés, frais cumulés sur la période.
     */
    public record CostDay(Long bindingId, String asset, LocalDate day, String outcome, double effectiveAmount, double carryIn,
                          double carryOut, String route, double costPct, double costUsdc, String feeTestLevel, double cashDelta,
                          double qtyDelta, double cumulativeCostUsdc) {
    }

    /**
     * Décision d'un lien un jour donné. {@code cashBefore}/{@code qtyBefore} = wallet en début de jour (comparable au snapshot live) ;
     * {@code cashReservedBefore} = cash réservé par les liens servis avant ; {@code cashAfter}/{@code qtyAfter}/{@code totalValue} =
     * wallet en fin de journée (tous liens appliqués).
     */
    public record LinkDay(Long bindingId, String asset, LocalDate day, int index, double price, double dayClose, String setName,
                          String regime, double cashBefore, double qtyBefore, double cashReservedBefore, double tradableQty,
                          double virtualPosition, RainbowLiveAction action, LiveBlockReason blockReason, Double amountUsdc,
                          Double quantity, RainbowLiveAction wantedAction, double wantedAmountUsdc, double cashAfter,
                          double qtyAfter, double assetsValue, double totalValue, Map<String, Object> modulation) {
    }

    /** Wallet en fin de journée : cash, quantités et valeurs par actif, total, et DCA fixe de référence (mêmes bases, cash commun). */
    public record WalletDay(LocalDate day, double cash, Map<String, Double> qty, Map<String, Double> value, double assetsValue,
                            double totalValue, double fixedCash, Map<String, Double> fixedQty, double fixedValue) {
    }

    public record Result(List<LinkDay> linkDays, List<WalletDay> walletDays, double cash0, Map<String, Double> qty0,
                         double finalValue, double fixedFinalValue, List<CostDay> costDays, double totalCostUsdc,
                         double fixedTotalCostUsdc) {
    }

    public static Result run(Input in) {
        List<Link> links = in.links().stream().sorted((a, b) -> LiveSlot.SERVICE_ORDER.compare(a.slot(), b.slot())).toList();
        Map<Long, LinkSignalReplay> replays = new HashMap<>();
        Map<Long, Map<LocalDate, Integer>> indexByDay = new HashMap<>();
        TreeSet<LocalDate> days = new TreeSet<>();
        Map<String, Double> qty = new LinkedHashMap<>();
        Map<String, Double> fixedQty = new LinkedHashMap<>();
        for (Link link : links) {
            Long id = link.slot().bindingId();
            LinkSignalReplay replay = new LinkSignalReplay(link.signal());
            replays.put(id, replay);
            Map<LocalDate, Integer> byDay = new HashMap<>();
            for (int i = link.signal().startIdx(); i <= link.signal().endIdx(); i++) {
                LocalDate d = replay.dayAt(i);
                byDay.put(d, i);
                days.add(d);
            }
            indexByDay.put(id, byDay);
            qty.putIfAbsent(link.slot().assetSymbol(), link.qty0());
            fixedQty.putIfAbsent(link.slot().assetSymbol(), link.qty0());
        }
        Map<String, Double> qty0 = new LinkedHashMap<>(qty);
        double cash = in.cash0();
        double fixedCash = in.cash0();
        Map<String, Double> lastPrice = new HashMap<>();
        List<LinkDay> linkDays = new ArrayList<>();
        List<WalletDay> walletDays = new ArrayList<>();
        List<CostDay> costDays = new ArrayList<>();
        Map<Long, Double> carry = new HashMap<>();
        Map<Long, Double> lastBase = new HashMap<>();
        double totalCost = 0.0;
        double fixedCost = 0.0;
        Costs costs = in.costs();

        for (LocalDate day : days) {
            Pin pin = in.pinned().get(day);
            if (pin != null) {
                cash = pin.cash();
                qty.putAll(pin.qty());
            }
            Map<Long, LinkSignalReplay.Prepared> prepared = new LinkedHashMap<>();
            Map<String, Double> closes = new HashMap<>();
            for (Link link : links) {
                Long id = link.slot().bindingId();
                Integer idx = indexByDay.get(id).get(day);
                if (idx != null) {
                    LinkSignalReplay.Prepared p = replays.get(id).prepare(idx);
                    prepared.put(id, p);
                    closes.putIfAbsent(link.slot().assetSymbol(), p.price());
                }
            }
            Map<String, Double> positions = new HashMap<>(qty);
            in.frozenQty().forEach(positions::putIfAbsent);
            in.frozenCloses().forEach((asset, byDay) -> {
                Double c = byDay.get(day);
                if (c != null) {
                    closes.putIfAbsent(asset, c);
                }
            });
            ModulationSettings settings = in.settingsByDay().getOrDefault(day, in.settings());
            double cashBefore = cash;
            Map<String, Double> qtyBefore = new HashMap<>(qty);

            CashLedger ledger = null;
            ExposureLedger exposure = null;
            Map<Long, SizingCore.Decision> decisions = new HashMap<>();
            Map<Long, double[]> audit = new HashMap<>(); // {reservedBefore, tradable}
            for (Link link : links) {
                Long id = link.slot().bindingId();
                LinkSignalReplay.Prepared p = prepared.get(id);
                if (p == null) {
                    continue;
                }
                if (ledger == null) {
                    ledger = new CashLedger(cash);
                    exposure = ExposureLedger.of(new PortfolioReading(cash, positions, null, PortfolioStatus.OK, 0L), closes);
                }
                if (pin != null) {
                    double wanted = pin.reservedBefore().getOrDefault(id, 0.0);
                    if (wanted > ledger.reserved()) {
                        ledger.forceReserve(wanted - ledger.reserved());
                    }
                }
                String asset = link.slot().assetSymbol();
                double tradable = qty.getOrDefault(asset, 0.0) * link.slot().bagPercent() / 100.0;
                double reservedBefore = ledger.reserved();
                decisions.put(id, SizingCore.decide(asset, p.demand(), tradable, ledger,
                        new LiveModulation(settings, in.limits(), exposure)));
                audit.put(id, new double[]{reservedBefore, tradable});
            }
            // wallet mis à jour une fois tous les liens du jour décidés
            double cashLeft = cash; // cash utilisable par les achats du jour (les ventes ne créditent pas le jour même)
            double soldCash = 0.0;
            for (Link link : links) {
                SizingCore.Decision d = decisions.get(link.slot().bindingId());
                if (d == null) {
                    continue;
                }
                String asset = link.slot().assetSymbol();
                if (costs == null) {
                    if (d.action() == RainbowLiveAction.BUY) {
                        cash -= d.amountUsdc();
                        qty.merge(asset, d.quantity(), Double::sum);
                    } else if (d.action() == RainbowLiveAction.SELL) {
                        cash += d.amountUsdc();
                        qty.put(asset, Math.max(0.0, qty.getOrDefault(asset, 0.0) - d.quantity()));
                    }
                    continue;
                }
                Long id = link.slot().bindingId();
                LinkSignalReplay.Prepared p = prepared.get(id);
                double carryIn = carry.getOrDefault(id, 0.0);
                Double prevBase = lastBase.put(id, p.fixedBase());
                if (prevBase != null && Math.abs(prevBase - p.fixedBase()) > EPSILON) {
                    carryIn = 0.0; // base_amount du preset changé : carry remis à zéro (comme le live)
                }
                OrderReplay.Executed e = costs.orders().execute(new OrderReplay.Request(asset, d.action(),
                        d.amountUsdc() == null ? 0.0 : d.amountUsdc(), d.quantity() == null ? 0.0 : d.quantity(), carryIn,
                        cashLeft, costs.minBuyUsd().get(id), costs.preferredRoute().get(id), closes));
                if (d.action() == RainbowLiveAction.BUY || d.action() == RainbowLiveAction.SELL) {
                    carry.put(id, e.carryOut());
                } else {
                    carry.put(id, carryIn);
                }
                if (e.executed()) {
                    if (d.action() == RainbowLiveAction.BUY) {
                        cash += e.cashDelta();
                        cashLeft += e.cashDelta();
                        qty.merge(asset, e.qtyDelta(), Double::sum);
                    } else {
                        soldCash += e.cashDelta();
                        qty.put(asset, Math.max(0.0, qty.getOrDefault(asset, 0.0) + e.qtyDelta()));
                    }
                    totalCost += e.costUsdc();
                }
                if (d.action() == RainbowLiveAction.BUY || d.action() == RainbowLiveAction.SELL) {
                    costDays.add(new CostDay(id, asset, day, e.outcome(), e.effectiveAmount(), carryIn, e.carryOut(), e.route(),
                            e.costPct(), e.executed() ? e.costUsdc() : 0.0,
                            e.feeTestLevel() == null ? null : e.feeTestLevel().name(), e.cashDelta(), e.qtyDelta(), totalCost));
                }
            }
            cash += soldCash;
            // DCA fixe de référence : base de chaque lien sur ses barres actives, cash commun, tout-ou-rien, même ordre de service
            for (Link link : links) {
                LinkSignalReplay.Prepared p = prepared.get(link.slot().bindingId());
                if (p != null && p.active() && fixedCash + EPSILON >= p.fixedBase()) {
                    if (costs == null) {
                        fixedCash -= p.fixedBase();
                        fixedQty.merge(link.slot().assetSymbol(), p.fixedBase() / p.price(), Double::sum);
                    } else { // mêmes frais / minimum d'ordre que la stratégie, sans carry
                        OrderReplay.Executed fe = costs.orders().execute(new OrderReplay.Request(link.slot().assetSymbol(),
                                RainbowLiveAction.BUY, p.fixedBase(), 0.0, 0.0, fixedCash, null,
                                costs.preferredRoute().get(link.slot().bindingId()), closes));
                        if (fe.executed()) {
                            fixedCash += fe.cashDelta();
                            fixedQty.merge(link.slot().assetSymbol(), fe.qtyDelta(), Double::sum);
                            fixedCost += fe.costUsdc();
                        }
                    }
                }
            }
            prepared.forEach((id, p) -> lastPrice.put(assetOf(links, id), p.price()));
            in.frozenCloses().forEach((asset, byDay) -> {
                Double c = byDay.get(day);
                if (c != null) {
                    lastPrice.put(asset, c);
                }
            });
            Map<String, Double> value = new LinkedHashMap<>();
            double assets = 0.0;
            for (Map.Entry<String, Double> e : qty.entrySet()) {
                double v = e.getValue() * lastPrice.getOrDefault(e.getKey(), 0.0);
                value.put(e.getKey(), v);
                assets += v;
            }
            double fixedAssets = 0.0;
            for (Map.Entry<String, Double> e : fixedQty.entrySet()) {
                fixedAssets += e.getValue() * lastPrice.getOrDefault(e.getKey(), 0.0);
            }
            double total = cash + assets;
            for (Link link : links) {
                Long id = link.slot().bindingId();
                LinkSignalReplay.Prepared p = prepared.get(id);
                if (p == null) {
                    continue;
                }
                SizingCore.Decision d = decisions.get(id);
                String asset = link.slot().assetSymbol();
                double[] a = audit.get(id);
                linkDays.add(new LinkDay(id, asset, day, p.index(), p.price(), p.dayClose(), p.setName(), p.regime(), cashBefore,
                        qtyBefore.getOrDefault(asset, 0.0), a[0], a[1], p.virtualPosition(), d.action(), d.blockReason(),
                        d.amountUsdc(), d.quantity(), p.wantedAction(), p.wantedAmountUsdc(), cash, qty.getOrDefault(asset, 0.0),
                        value.getOrDefault(asset, 0.0), total, d.journal()));
            }
            walletDays.add(new WalletDay(day, cash, new LinkedHashMap<>(qty), value, assets, total, fixedCash,
                    new LinkedHashMap<>(fixedQty), fixedCash + fixedAssets));
            for (Long id : prepared.keySet()) {
                replays.get(id).commit();
            }
        }
        double finalValue = walletDays.isEmpty() ? in.cash0() : walletDays.getLast().totalValue();
        double fixedFinal = walletDays.isEmpty() ? in.cash0() : walletDays.getLast().fixedValue();
        return new Result(linkDays, walletDays, in.cash0(), qty0, finalValue, fixedFinal, costDays, totalCost, fixedCost);
    }

    private static String assetOf(List<Link> links, Long id) {
        for (Link link : links) {
            if (link.slot().bindingId().equals(id)) {
                return link.slot().assetSymbol();
            }
        }
        throw new IllegalStateException("lien inconnu " + id);
    }
}
