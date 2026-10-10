package fr.ses10doigts.tradeIO5.service.market.instrument;

import fr.ses10doigts.tradeIO5.model.dto.execution.InstrumentInfo;
import fr.ses10doigts.tradeIO5.model.entity.exchange.WebProvider;
import fr.ses10doigts.tradeIO5.model.entity.market.ExchangeInstrument;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.market.ExchangeInstrumentRepository;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentListClient;
import fr.ses10doigts.tradeIO5.service.connector.instrument.InstrumentLookupException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue des paires spot par exchange, en base (cache DB : un seul appel public « toutes les paires » par exchange,
 * au plus 1×/jour, jamais un appel par paire). Lecture seule côté exchange.
 * <ul>
 *   <li>Rafraîchissement à la demande quand le catalogue a plus de {@code refresh-after} ({@link ExecutionDefaults#CATALOG_REFRESH_AFTER}),
 *       ou explicite via {@link #refresh}.</li>
 *   <li>Panne : le catalogue en base est conservé (WARN), jamais vidé ; après un échec, pas de nouvelle tentative avant
 *       {@link ExecutionDefaults#CATALOG_RETRY_AFTER_FAILURE} (pas de boucle vers le provider).</li>
 *   <li>Catalogue absent ET provider injoignable => {@link InstrumentLookupException}.</li>
 * </ul>
 */
@Slf4j
@Service
public class InstrumentCatalog {

    private static final String LIVE_STATE = "live";
    private static final String DELISTED_STATE = "inactive";

    private final ExchangeInstrumentRepository repository;
    private final Map<WebProviderCode, InstrumentListClient> clients;
    private final DomainClock clock;
    private final TransactionTemplate transaction;
    private final Duration refreshAfter;
    private final Map<WebProviderCode, Instant> lastFailure = new EnumMap<>(WebProviderCode.class);

    public InstrumentCatalog(ExchangeInstrumentRepository repository, List<InstrumentListClient> clients,
                             DomainClock clock, PlatformTransactionManager transactionManager,
                             @Value("${" + ExecutionDefaults.CATALOG_REFRESH_AFTER_PROPERTY + ":PT24H}") Duration refreshAfter) {
        this.repository = repository;
        this.clients = clients.stream().collect(Collectors.toMap(InstrumentListClient::getProviderCode, Function.identity()));
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.refreshAfter = refreshAfter;
    }

    /** Un client de catalogue existe-t-il pour cet exchange ? */
    public boolean supports(WebProviderCode provider) {
        return provider != null && clients.containsKey(provider);
    }

    /** La paire {@code base/quote} existe-t-elle et est-elle négociable ? Lit le catalogue (frais ou conservé). */
    public boolean isTradable(WebProvider provider, String base, String quote) {
        ensureFresh(provider);
        return repository.findByProviderAndInstId(provider.getCode(), base + "-" + quote)
                .map(i -> LIVE_STATE.equals(i.getState())).orElse(false);
    }

    /** Paires négociables de l'exchange (catalogue frais ou conservé). */
    public List<InstrumentInfo> liveInstruments(WebProvider provider) {
        ensureFresh(provider);
        return repository.findByProvider(provider.getCode()).stream()
                .filter(i -> LIVE_STATE.equals(i.getState()))
                .map(i -> new InstrumentInfo(i.getInstId(), i.getBase(), i.getQuote(), i.getMinSz(), i.getLotSz(), i.getTickSz()))
                .toList();
    }

    /** Rafraîchissement explicite (un appel public). En cas d'échec le catalogue en base est conservé. */
    public synchronized void refresh(WebProvider provider) {
        InstrumentListClient client = clients.get(provider.getCode());
        if (client == null) {
            throw new InstrumentLookupException("Aucun client de catalogue pour " + provider.getCode());
        }
        List<InstrumentListClient.Listed> listed = client.fetchAll(provider);
        if (listed.isEmpty()) {
            throw new InstrumentLookupException(provider.getCode() + " : liste d'instruments vide");
        }
        Instant now = clock.now();
        transaction.executeWithoutResult(status -> store(provider.getCode(), listed, now));
        lastFailure.remove(provider.getCode());
    }

    private void store(WebProviderCode code, List<InstrumentListClient.Listed> listed, Instant now) {
        Map<String, ExchangeInstrument> existing = repository.findByProvider(code).stream()
                .collect(Collectors.toMap(ExchangeInstrument::getInstId, Function.identity(), (a, b) -> a, HashMap::new));
        List<ExchangeInstrument> toSave = new ArrayList<>(listed.size());
        Set<String> ids = new HashSet<>();
        int added = 0;
        for (InstrumentListClient.Listed l : listed) {
            ids.add(l.instId());
            ExchangeInstrument row = existing.get(l.instId());
            if (row == null) {
                row = ExchangeInstrument.builder().provider(code).instId(l.instId()).build();
                added++;
            }
            row.setBase(l.base());
            row.setQuote(l.quote());
            row.setState(l.live() ? LIVE_STATE : DELISTED_STATE);
            row.setMinSz(l.minSz());
            row.setLotSz(l.lotSz());
            row.setTickSz(l.tickSz());
            row.setFetchedAt(now);
            toSave.add(row);
        }
        repository.saveAll(toSave);
        int removed = (int) existing.keySet().stream().filter(id -> !ids.contains(id)).count();
        if (removed > 0) {
            repository.deleteByProviderAndInstIdNotIn(code, ids);
        }
        log.info("Catalogue {} rafraîchi : {} paires ({} ajoutées, {} retirées)", code, listed.size(), added, removed);
    }

    private synchronized void ensureFresh(WebProvider provider) {
        WebProviderCode code = provider.getCode();
        Instant now = clock.now();
        Instant last = repository.findTopByProviderOrderByFetchedAtDesc(code).map(ExchangeInstrument::getFetchedAt).orElse(null);
        if (last != null && last.plus(refreshAfter).isAfter(now)) {
            return;
        }
        Instant failedAt = lastFailure.get(code);
        if (failedAt != null && failedAt.plus(ExecutionDefaults.CATALOG_RETRY_AFTER_FAILURE).isAfter(now)) {
            if (last == null) {
                throw new InstrumentLookupException(code + " : catalogue absent et exchange injoignable");
            }
            return;
        }
        try {
            refresh(provider);
        } catch (InstrumentLookupException e) {
            lastFailure.put(code, now);
            if (last == null) {
                throw e;
            }
            log.warn("Catalogue {} : rafraîchissement impossible, catalogue conservé (dernier : {}) : {}", code, last, e.getMessage());
        }
    }
}
