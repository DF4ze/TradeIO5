package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.model.entity.currency.Transaction;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.TradeOrigin;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.TransactionRepository;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderStepRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.connector.trades.ExchangeFillReader;
import fr.ses10doigts.tradeIO5.service.connector.trades.ImportedFill;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Importe l'historique des remplissages spot des exchanges (lecture seule) dans {@link Transaction}, pour que les trades passés à
 * la main apparaissent à côté de ceux de TradeIO. Chaque trade est tagué {@link TradeOrigin#TRADEIO} si son ordre a été envoyé par
 * TradeIO (identifiant client {@code t5…} ou ordre connu du plan d'exécution), sinon {@link TradeOrigin#MANUAL}. Un trade de TradeIO
 * remonte aussi sur l'exchange : il est reconnu, jamais compté deux fois (identifiant externe {@code <EXCHANGE>:<tradeId>}, comme
 * le {@code FillRecorder}). Premier passage d'un wallet depuis le démarrage : tout l'historique accessible ; ensuite les 7 derniers
 * jours. Espacé de 5 min par wallet sauf demande explicite ({@code force}). Un échec d'un wallet n'empêche pas les autres.
 */
@Slf4j
@Service
public class ExchangeTradeImportService {

    private static final Duration THROTTLE = Duration.ofMinutes(5);
    private static final Duration RECENT_WINDOW = Duration.ofDays(7);
    /** Préfixe des identifiants client générés par TradeIO ({@code ClOrdIds}). */
    private static final String TRADEIO_CLORDID_PREFIX = "t5";

    public record SyncResult(int imported, int tradeio, int manual, List<String> failedWallets) {
    }

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final RainbowLiveOrderStepRepository stepRepository;
    private final Map<WebProviderCode, ExchangeFillReader> readers;
    private final DomainClock clock;
    private final Map<Long, Instant> lastRun = new ConcurrentHashMap<>();
    private final Set<Long> fullyImported = ConcurrentHashMap.newKeySet();

    public ExchangeTradeImportService(WalletRepository walletRepository, TransactionRepository transactionRepository,
                                      RainbowLiveOrderStepRepository stepRepository, List<ExchangeFillReader> readers, DomainClock clock) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.stepRepository = stepRepository;
        this.readers = readers.stream().collect(Collectors.toMap(ExchangeFillReader::getProviderCode, Function.identity()));
        this.clock = clock;
    }

    /** Importe les nouveaux trades d'un wallet ({@code walletId}) ou de tous les wallets activés de l'utilisateur ; {@code force} ignore l'espacement. */
    public synchronized SyncResult sync(User user, Long walletId, boolean force) {
        List<Wallet> wallets = walletRepository.findByUserAndEnabledTrue(user).stream()
                .filter(w -> walletId == null || w.getId().equals(walletId)).toList();
        if (walletId != null && wallets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet inconnu : " + walletId);
        }
        Set<String> knownClOrdIds = null, knownOrdIds = null;
        int imported = 0, tradeio = 0, manual = 0;
        List<String> failed = new ArrayList<>();
        Instant now = clock.now();
        for (Wallet wallet : wallets) {
            ExchangeFillReader reader = readers.get(wallet.getWebProviderCode());
            Instant last = lastRun.get(wallet.getId());
            if (reader == null || wallet.getCredential() == null || (!force && last != null && Duration.between(last, now).compareTo(THROTTLE) < 0)) {
                continue;
            }
            if (knownClOrdIds == null) {
                knownClOrdIds = new HashSet<>(stepRepository.clOrdIdsOf(user));
                knownOrdIds = new HashSet<>(stepRepository.ordIdsOf(user));
            }
            repairSymbols(wallet);
            try {
                Instant since = fullyImported.contains(wallet.getId()) ? now.minus(RECENT_WINDOW) : null;
                for (ImportedFill fill : reader.fetchFills(wallet.getCredential(), since)) {
                    TradeOrigin origin = originOf(fill, knownClOrdIds, knownOrdIds);
                    if (save(wallet, fill, origin)) {
                        imported++;
                        if (origin == TradeOrigin.TRADEIO) {
                            tradeio++;
                        } else {
                            manual++;
                        }
                    }
                }
                fullyImported.add(wallet.getId());
                lastRun.put(wallet.getId(), now);
            } catch (RuntimeException e) {
                log.warn("Import des trades : wallet={} ({}) indisponible : {}", wallet.getId(), wallet.getWebProviderCode(), e.getMessage());
                failed.add(wallet.getName());
            }
        }
        if (imported > 0) {
            log.info("Import des trades user={} : {} nouveaux ({} TradeIO, {} manuels)", user.getId(), imported, tradeio, manual);
        }
        return new SyncResult(imported, tradeio, manual, failed);
    }

    /** Répare les anciens trades dont le symbole portait un séparateur de paire (« PAXG/ » ⇒ « PAXG »). */
    private void repairSymbols(Wallet wallet) {
        for (Transaction t : transactionRepository.findByWalletOrderByTimestampAsc(wallet)) {
            String clean = HomeOverviewService.cleanSymbol(t.getAsset());
            if (clean != null && !clean.isEmpty() && !clean.equals(t.getAsset())) {
                t.setAsset(clean);
                transactionRepository.save(t);
            }
        }
    }

    static TradeOrigin originOf(ImportedFill fill, Set<String> knownClOrdIds, Set<String> knownOrdIds) {
        boolean ours = (fill.clOrdId() != null && (fill.clOrdId().startsWith(TRADEIO_CLORDID_PREFIX) || knownClOrdIds.contains(fill.clOrdId())))
                || (fill.ordId() != null && knownOrdIds.contains(fill.ordId()));
        return ours ? TradeOrigin.TRADEIO : TradeOrigin.MANUAL;
    }

    /** {@code false} si le trade existe déjà (enregistré par le FillRecorder, une synchro précédente ou l'ancien import). */
    private boolean save(Wallet wallet, ImportedFill fill, TradeOrigin origin) {
        String externalId = wallet.getWebProviderCode() + ":" + fill.tradeId();
        if (transactionRepository.existsByExternalTransactionId(externalId) || transactionRepository.existsByExternalTransactionId(fill.tradeId())) {
            return false;
        }
        try {
            transactionRepository.save(Transaction.builder().externalTransactionId(externalId).user(wallet.getUser())
                    .webProvider(wallet.getWebProvider()).wallet(wallet).asset(fill.base()).quoteCurrency(fill.quote())
                    .quantity(fill.sz()).price(fill.px()).timestamp(LocalDateTime.ofInstant(fill.ts(), ZoneOffset.UTC))
                    .side(fill.side()).fee(fill.fee()).feeCurrency(fill.feeCcy()).origin(origin).build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;   // enregistré en parallèle (FillRecorder)
        }
    }
}
