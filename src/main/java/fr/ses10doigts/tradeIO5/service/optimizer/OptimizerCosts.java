package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.service.expert.simulation.MultiLinkReplayEngine;
import fr.ses10doigts.tradeIO5.service.expert.simulation.OrderReplay;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Barèmes de coûts STATIQUES du scorer (aucun appel exchange) pour {@link OrderReplay} : une paire directe {@code ACTIF-USDC} par
 * actif. « Simplifié » = frais constant, sans spread, slippage ni minimum d'ordre ; « complet » = frais, demi-spread, slippage,
 * lot et achat minimum du lien (route, carry et blocages du vrai calcul d'ordre).
 */
public final class OptimizerCosts {

    private static final String CASH = ExecutionDefaults.CASH_STABLE;
    private static final BigDecimal LOT = BigDecimal.valueOf(OptimizerDefaults.LOT_SIZE);
    private static final BigDecimal TICK = new BigDecimal("0.01");

    private OptimizerCosts() {
    }

    /** @param bindingIds identifiants de lien par actif (achat minimum du mode complet) */
    public static MultiLinkReplayEngine.Costs simplified(Set<String> assets) {
        return build(assets, OptimizerDefaults.SIMPLIFIED_FEE_PCT, 0.0, 0.0, Map.of());
    }

    public static MultiLinkReplayEngine.Costs complete(Set<String> assets, Map<Long, Double> minBuyUsd) {
        return build(assets, OptimizerDefaults.COMPLETE_FEE_PCT, OptimizerDefaults.COMPLETE_HALF_SPREAD_PCT,
                OptimizerDefaults.COMPLETE_SLIPPAGE_PCT, minBuyUsd);
    }

    private static MultiLinkReplayEngine.Costs build(Set<String> assets, double feePct, double halfSpreadPct, double slippagePct,
                                                     Map<Long, Double> minBuyUsd) {
        List<InstrumentInfo> instruments = assets.stream().sorted()
                .map(a -> new InstrumentInfo(a + "-" + CASH, a, CASH, LOT, LOT, TICK)).toList();
        Map<String, OrderReplay.Quote> quotes = new HashMap<>();
        instruments.forEach(i -> quotes.put(i.instId(), new OrderReplay.Quote(BigDecimal.valueOf(feePct),
                BigDecimal.valueOf(halfSpreadPct), BigDecimal.valueOf(slippagePct))));
        OrderReplay.Schedule schedule = new OrderReplay.Schedule(instruments, Set.of(CASH), ExecutionDefaults.DEFAULT_FORBIDDEN_NODES,
                quotes, Map.of(), List.of("Barème statique du moteur d'optimisation : aucun appel exchange."));
        return new MultiLinkReplayEngine.Costs(new OrderReplay(schedule, new FeeTest()), minBuyUsd, Map.of());
    }
}
