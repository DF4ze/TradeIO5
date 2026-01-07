package fr.ses10doigts.tradeIO5.service.bot.dca;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaDecision;
import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.Action;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.DcaStrategyType;
import fr.ses10doigts.tradeIO5.service.bot.dca.strategy.*;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
public class DcaService {
    private final Logger logger = LoggerFactory.getLogger(DcaService.class);

    private final ProviderApiService exchangeService; // Service pour exécuter les ordres
    private final List<DcaStrategy> strategies;

    /**
     * Exécute le DCA pour un DcaSettings donné
     */
    public void executeDca(DcaSettings settings) {
        if (!settings.isEnabled()) return;

        DcaStrategy strategy = getStrategyInstance(settings.getStrategy());
        if (strategy == null) {
            logger.error("Strategy not found {}", settings.getStrategy());
            return;
        }

        // Obtenir la décision de la stratégie
        DcaDecision decision = strategy.decide(settings);

        // Transformer la décision en ordre réel
        applyDecision(decision, settings.getWallet());
    }


    private DcaStrategy getStrategyInstance(DcaStrategyType strategyEnum) {
        AtomicReference<DcaStrategy> r = new AtomicReference<>();
        strategies.forEach(s ->{
            if( s.getCodeName().equals(strategyEnum.name()) ) {
                r.set(s);
                return;
            }
        });
        return r.get();
    }

    private void applyDecision(DcaDecision decision, Wallet wallet) {
        if (decision.getAction() == Action.NONE) return;

        BigDecimal amount;
        String asset = decision.getSettings().getAsset();

        switch (decision.getQuantityMode()) {
            case UNIT:
                amount = decision.getQuantity();
                break;
            case PERCENT:
                amount = exchangeService.getUserBalance(wallet, asset)
                        .multiply(decision.getQuantity())
                        .divide(BigDecimal.valueOf(100), RoundingMode.HALF_DOWN);
                break;
            case UNIT_COEFF:
                amount = decision.getQuantity().multiply(decision.getCoefficient());
                break;
            default:
                throw new IllegalArgumentException("Mode de quantité inconnu");
        }

        if (decision.getAction() == Action.BUY) {
            exchangeService.buy(wallet, amount, asset );
        } else if (decision.getAction() == Action.SELL) {
            exchangeService.sell(wallet, amount, asset);
        }
    }
}
