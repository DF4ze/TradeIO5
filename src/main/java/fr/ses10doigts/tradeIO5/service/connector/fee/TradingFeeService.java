package fr.ses10doigts.tradeIO5.service.connector.fee;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeRate;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.model.entity.exchange.TradingFeeCache;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.repository.TradingFeeCacheRepository;
import fr.ses10doigts.tradeIO5.service.connector.balance.BalanceUnavailableException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service unique des frais réels du compte, partagé par le plan d'ordres, le devis de chemin, l'onboarding / ajout d'un lien, le
 * re-devis d'exécution et le simulateur. Cache {@link ExecutionDefaults#FEE_CACHE_TTL} (24 h, sur {@link DomainClock}) en mémoire
 * puis <b>persisté</b> ({@link TradingFeeCache}, survit au redémarrage). Les {@link TradingFeeProvider} ne font que la récupération
 * réseau (throttle compris). Clé = (credential, instrument), ou credential seul si {@link TradingFeeProvider#accountWide()}.
 * <ul>
 *   <li>Échec de récupération non caché ; aucun frais jamais supposé.</li>
 *   <li>{@link #getFeeRate} : strict (plan d'ordres, re-devis d'exécution) : valeur périmée + échec => exception.</li>
 *   <li>{@link #lenient} : estimation (devis, onboarding, simulateur) : valeur périmée acceptée si la récupération échoue, signalée.</li>
 *   <li>Single-flight par clé : N demandes simultanées identiques => un seul appel réseau.</li>
 * </ul>
 * Aucune dépendance à un port d'ordre.
 */
@Slf4j
@Service
public class TradingFeeService {

    private record Entry(FeeRate rate, Instant fetchedAt) {
    }

    /** Frais lus et indicateur de péremption (valeur de plus de 24 h acceptée faute de récupération). */
    public record Lookup(FeeRate rate, boolean stale) {
    }

    private final Map<WebProviderCode, TradingFeeProvider> fetchers;
    private final TradingFeeCacheRepository repository;
    private final DomainClock clock;
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public TradingFeeService(List<TradingFeeProvider> fetchers, TradingFeeCacheRepository repository, DomainClock clock) {
        this.fetchers = fetchers.stream().collect(Collectors.toMap(TradingFeeProvider::getProviderCode, Function.identity()));
        this.repository = repository;
        this.clock = clock;
    }

    /** Strict : frais valides (< 24 h) ou récupérés à neuf ; sinon l'exception du provider. */
    public FeeRate getFeeRate(WebProviderCode provider, ApiCredential credential, String instId) {
        return lookup(provider, credential, instId, false).rate();
    }

    /** Estimation : comme {@link #getFeeRate} mais accepte une valeur périmée si la récupération échoue (hors clé rejetée). */
    public Lookup getFeeRateLenient(WebProviderCode provider, ApiCredential credential, String instId) {
        return lookup(provider, credential, instId, true);
    }

    /** Vue {@link TradingFeeProvider} stricte, ou {@code null} si l'exchange n'a pas de récupération de frais. */
    public TradingFeeProvider forProvider(WebProviderCode provider) {
        return view(provider, false, null);
    }

    /** Vue estimation ; {@code onStale} reçoit l'instrument dont la valeur périmée a été utilisée. */
    public TradingFeeProvider lenient(WebProviderCode provider, Consumer<String> onStale) {
        return view(provider, true, onStale);
    }

    /** Vide le cache mémoire (la base est conservée) : tests, ou rafraîchissement forcé. */
    public void clear() {
        memory.clear();
    }

    /** Préchauffage (simulateur) : récupère seulement les entrées absentes ou expirées. Lève comme {@link #getFeeRate}. */
    public void warm(WebProviderCode provider, ApiCredential credential, Collection<String> instIds) {
        for (String instId : instIds) {
            getFeeRate(provider, credential, instId);
        }
    }

    private TradingFeeProvider view(WebProviderCode provider, boolean lenient, Consumer<String> onStale) {
        TradingFeeProvider fetcher = fetchers.get(provider);
        if (fetcher == null) {
            return null;
        }
        return new TradingFeeProvider() {
            @Override
            public WebProviderCode getProviderCode() {
                return provider;
            }

            @Override
            public boolean accountWide() {
                return fetcher.accountWide();
            }

            @Override
            public FeeRate getFeeRate(ApiCredential credential, String instId) {
                Lookup l = lookup(provider, credential, instId, lenient);
                if (l.stale() && onStale != null) {
                    onStale.accept(instId);
                }
                return l.rate();
            }
        };
    }

    private Lookup lookup(WebProviderCode provider, ApiCredential credential, String instId, boolean lenient) {
        TradingFeeProvider fetcher = fetchers.get(provider);
        if (fetcher == null) {
            throw new BalanceUnavailableException("Frais non pris en charge pour " + provider);
        }
        String instKey = fetcher.accountWide() ? TradingFeeCache.ACCOUNT_WIDE : instId;
        Long credId = credential.getId();
        String key = (credId != null ? "id:" + credId : "k:" + credential.getApiKey()) + "/" + provider + "/" + instKey;
        Instant now = clock.now();
        Entry cached = fresh(key, credId, instKey, now);
        if (cached != null) {
            return new Lookup(cached.rate(), false);
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            Entry again = fresh(key, credId, instKey, clock.now());
            if (again != null) {
                return new Lookup(again.rate(), false);
            }
            try {
                FeeRate rate = fetcher.getFeeRate(credential, instId);
                Entry entry = new Entry(rate, clock.now());
                memory.put(key, entry);
                persist(credId, instKey, entry);
                return new Lookup(rate, false);
            } catch (BalanceUnavailableException e) {
                Entry stale = lenient && !(e instanceof fr.ses10doigts.tradeIO5.service.connector.balance.CredentialRejectedException)
                        ? memory.get(key) : null;
                if (stale != null) {
                    log.warn("⚠️ [FRAIS] {} {} indisponibles ({}) : valeur du {} utilisée (périmée)", provider, instKey, e.getMessage(),
                            stale.fetchedAt());
                    return new Lookup(stale.rate(), true);
                }
                throw e;
            }
        }
    }

    /** Entrée valide en mémoire, sinon en base (chargée en mémoire même périmée, pour le repli estimation). */
    private Entry fresh(String key, Long credId, String instKey, Instant now) {
        Entry e = memory.get(key);
        if (e == null && credId != null) {
            e = repository.findByCredentialIdAndInstKey(credId, instKey)
                    .map(r -> new Entry(new FeeRate(r.getMakerPct(), r.getTakerPct()), r.getFetchedAt())).orElse(null);
            if (e != null) {
                memory.put(key, e);
            }
        }
        return e != null && e.fetchedAt().plus(ExecutionDefaults.FEE_CACHE_TTL).isAfter(now) ? e : null;
    }

    private void persist(Long credId, String instKey, Entry entry) {
        if (credId == null) {
            return;
        }
        try {
            TradingFeeCache row = repository.findByCredentialIdAndInstKey(credId, instKey)
                    .orElseGet(() -> TradingFeeCache.builder().credentialId(credId).instKey(instKey).build());
            row.setMakerPct(entry.rate().makerPct());
            row.setTakerPct(entry.rate().takerPct());
            row.setFetchedAt(entry.fetchedAt());
            repository.save(row);
        } catch (RuntimeException ex) {
            log.warn("⚠️ [FRAIS] persistance du cache impossible ({}) : mémoire seule", ex.getMessage());
        }
    }
}
