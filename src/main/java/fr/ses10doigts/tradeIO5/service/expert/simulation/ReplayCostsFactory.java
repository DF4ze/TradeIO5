package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.service.market.instrument.FeeTest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Construit les coûts d'un rejeu de wallet ({@link MultiLinkReplayEngine.Costs} + barème figé) : seul appel réseau du rejeu avec frais
 * (via {@link ReplayScheduleBuilder}). Partagé par le rejeu de parité et le rejeu libre : minimum d'achat et route imposée = ceux du
 * lien ; montant de référence du barème = {@code refAmountUsdc} (défaut 100 si non positif).
 */
@Component
@RequiredArgsConstructor
public class ReplayCostsFactory {

    private static final double DEFAULT_REF_USD = 100.0;

    private final ReplayScheduleBuilder scheduleBuilder;
    private final FeeTest feeTest;

    /** Barème figé et paramètres d'ordre (minimum, route) des liens. */
    public record Built(MultiLinkReplayEngine.Costs costs, OrderReplay.Schedule schedule) {
    }

    /** @param bindings liens du MÊME wallet (le 1er donne le wallet et ses identifiants d'exchange) */
    public Built build(List<RainbowLiveBinding> bindings, double refAmountUsdc) {
        Wallet wallet = bindings.getFirst().getWallet();
        OrderReplay.Schedule schedule = scheduleBuilder.build(wallet.getWebProviderCode(), wallet.getCredential(),
                bindings.stream().map(RainbowLiveBinding::getAssetSymbol).toList(),
                BigDecimal.valueOf(refAmountUsdc > 0 ? refAmountUsdc : DEFAULT_REF_USD));
        Map<Long, Double> minBuy = new HashMap<>();
        Map<Long, String> route = new HashMap<>();
        for (RainbowLiveBinding b : bindings) {
            if (b.getMinBuyUsd() != null) {
                minBuy.put(b.getId(), b.getMinBuyUsd());
            }
            if (b.getPreferredRoute() != null) {
                route.put(b.getId(), b.getPreferredRoute());
            }
        }
        return new Built(new MultiLinkReplayEngine.Costs(new OrderReplay(schedule, feeTest), minBuy, route), schedule);
    }
}
