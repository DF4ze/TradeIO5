package fr.ses10doigts.tradeIO5.service.execution.run;

import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeSide;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.service.execution.exchange.Fill;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Enregistre les remplissages en {@link Transaction}, de façon idempotente : {@code externalTransactionId} =
 * {@code <exchange>:<tradeId>} (le préfixe évite toute collision d'identifiants entre exchanges) ; un remplissage déjà
 * enregistré (rejeu, réconciliation) est ignoré.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FillRecorder {

    private final TransactionRepository transactions;
    private final WalletRepository wallets;

    /**
     * @param asset base de l'instrument (BTC, USDT...)
     * @return nombre de transactions créées
     */
    @Transactional
    public int record(Long walletId, String asset, List<Fill> fills) {
        Wallet wallet = wallets.getReferenceById(walletId);
        int created = 0;
        for (Fill fill : fills) {
            String externalId = wallet.getWebProviderCode() + ":" + fill.tradeId();
            if (transactions.existsByExternalTransactionId(externalId)) {
                log.debug("Remplissage {} déjà enregistré", externalId);
                continue;
            }
            transactions.save(Transaction.builder().externalTransactionId(externalId).user(wallet.getUser())
                    .webProvider(wallet.getWebProvider()).wallet(wallet).asset(asset).quantity(fill.sz()).price(fill.px())
                    .timestamp(LocalDateTime.ofInstant(fill.ts(), ZoneOffset.UTC))
                    .side(fill.side() == StepSide.BUY ? TradeSide.BUY : TradeSide.SELL).fee(fill.fee())
                    .feeCurrency(fill.feeCcy()).build());
            created++;
        }
        return created;
    }
}
