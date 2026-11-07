package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.EligibilityDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.ProviderEligibilityDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationCreatedDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationErrorDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationRequest;
import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;
import fr.ses10doigts.tradeIO5.model.enumerate.market.MarketDataSource;
import fr.ses10doigts.tradeIO5.service.connector.apiclient.marketdata.MarketDataProviderLimits;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssets;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.dataset.DailyDataset;
import fr.ses10doigts.tradeIO5.service.market.dataset.NoProviderAvailableException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Simulations de l'Espace Expert : demande validée de façon synchrone (droits, réglages, historique atteignable), calcul
 * ASYNCHRONE sur un exécuteur borné ({@code QUEUED → LOADING_DATA → COMPUTING → DONE | FAILED}). Garde-fous : une seule
 * simulation active par utilisateur (409), limite de débit par utilisateur (429), file bornée (429), chargement des données
 * single-flight ({@link DailyDataLoader}). Résultats conservés en MÉMOIRE (durée de vie et plafond par utilisateur), visibles de
 * leur seul créateur (404 pour tout autre) ; une relance remplace la simulation de même (type, actif).
 */
@Slf4j
@Service
public class ExpertSimulationService {

    private static final String THREAD_PREFIX = "expert-simulation-";

    private final SimulationRequestResolver resolver;
    private final DailyDataLoader loader;
    private final SimulationEligibility eligibility;
    private final ExpertSimulationSettings settings;
    private final DomainClock clock;
    private final ExpertAssets assets;
    private final ThreadPoolExecutor executor;

    private final ConcurrentMap<String, SimulationRecord> records = new ConcurrentHashMap<>();
    private final Map<Long, ArrayDeque<Instant>> submissions = new HashMap<>();
    private final Object bookkeeping = new Object();

    public ExpertSimulationService(SimulationRequestResolver resolver, DailyDataLoader loader, SimulationEligibility eligibility,
                                   ExpertSimulationSettings settings, DomainClock clock, ExpertAssets assets) {
        this.resolver = resolver;
        this.loader = loader;
        this.eligibility = eligibility;
        this.settings = settings;
        this.clock = clock;
        this.assets = assets;
        int[] counter = {0};
        this.executor = new ThreadPoolExecutor(settings.poolSize(), settings.poolSize(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), r -> {
            Thread t = new Thread(r, THREAD_PREFIX + (++counter[0]));
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- API

    /** Valide et met en file ; renvoie l'identifiant (le calcul se poursuit en tâche de fond). */
    public SimulationCreatedDto startSimulation(ExpertContext ctx, SimulationRequest request) {
        SimulationType type = parseType(request.type());
        String asset = assets.requireAsset(request.assetSymbol());
        MarketDataSource requested = parseSource(request.provider());
        ResolvedSimulation resolved = resolver.resolve(ctx, type, asset, request);
        MarketDataSource preferred = requested != null ? requested : parseSource(resolved.referenceProvider());
        if (eligibility.unreachable(asset, preferred, resolved.requiredPoints())) {
            throw insufficient(resolved.requiredPoints(), preferred == null ? 0 : MarketDataProviderLimits.maxD1Candles(preferred),
                    preferred == null ? null : preferred.name());
        }

        SimulationRecord record;
        synchronized (bookkeeping) {
            Instant now = clock.now();
            purgeExpired(now);
            Long owner = ctx.user().getId();
            if (records.values().stream().anyMatch(r -> r.ownerId.equals(owner) && r.state().isActive())) {
                throw new ExpertApiException(ExpertApiException.Code.SIMULATION_ALREADY_RUNNING,
                        "Une simulation est déjà en cours : attendez son résultat ou libérez-la");
            }
            rateLimit(owner, now);
            records.values().removeIf(r -> r.ownerId.equals(owner) && r.type == type && r.assetSymbol.equals(asset));
            evictOldest(owner);
            record = new SimulationRecord(UUID.randomUUID().toString(), owner, type, asset, now);
            records.put(record.id, record);
            try {
                executor.execute(() -> run(record, resolved, request, preferred));
            } catch (RejectedExecutionException e) {
                records.remove(record.id);
                throw new ExpertApiException(ExpertApiException.Code.SIMULATION_BUSY, "Trop de simulations en attente : réessayez plus tard");
            }
        }
        return new SimulationCreatedDto(record.id, record.state().name());
    }

    public SimulationDto get(ExpertContext ctx, String id) {
        synchronized (bookkeeping) {
            purgeExpired(clock.now());
        }
        return dto(find(ctx, id));
    }

    /** Libère une simulation (annule son calcul s'il est en cours). */
    public void delete(ExpertContext ctx, String id) {
        SimulationRecord record = find(ctx, id);
        record.cancel();
        records.remove(record.id);
    }

    // ---------------------------------------------------------------- calcul

    private void run(SimulationRecord record, ResolvedSimulation resolved, SimulationRequest request, MarketDataSource preferred) {
        try {
            record.loading();
            DailyDataset daily = loader.load(record.assetSymbol, preferred);
            if (record.cancelled()) {
                return;
            }
            List<MarketData> candles = closedCandles(daily);
            if (candles.size() < resolved.requiredPoints()) {
                ExpertApiException e = insufficient(resolved.requiredPoints(), candles.size(), daily.source().name());
                record.fail(new SimulationErrorDto(e.getCode().name(), e.getMessage(), e.getDetails()), clock.now());
                return;
            }
            record.computing();
            List<String> warnings = new ArrayList<>();
            if (daily.substituted()) {
                warnings.add("Provider " + preferred + " indisponible pour " + record.assetSymbol + " : données servies par "
                        + daily.source() + " (fallback).");
            }
            SimulationInput input = new SimulationInput(record.type, record.assetSymbol, candles, resolved.trend(),
                    resolved.rangeMapping(), resolved.trendLabel(), resolved.up(), resolved.down(), resolved.signal(),
                    request.from(), request.to());
            EligibilityDto used = new EligibilityDto(resolved.requiredPoints(), List.of(new ProviderEligibilityDto(
                    daily.source().name(), candles.size(), depthOf(daily.source()), true, true)));
            var outcome = SimulationEngine.run(input, new SimulationInput.Provenance(preferred == null ? null : preferred.name(),
                    daily.source(), daily.providerSymbol(), daily.substituted()), used, warnings);
            if (!record.cancelled()) {
                record.done(outcome, clock.now());
            }
        } catch (ExpertValidationException e) {
            record.fail(new SimulationErrorDto("VALIDATION", e.getMessage(), e.getFields()), clock.now());
        } catch (NoProviderAvailableException e) {
            log.warn("Simulation {} : aucune donnée pour {} : {}", record.id, record.assetSymbol, e.getMessage());
            record.fail(new SimulationErrorDto("DATA_UNAVAILABLE", "Aucun provider ne sert les bougies de " + record.assetSymbol, null),
                    clock.now());
        } catch (RuntimeException e) {
            log.error("Simulation {} en échec", record.id, e);
            record.fail(new SimulationErrorDto("INTERNAL", "Simulation en échec", null), clock.now());
        }
    }

    /** Bougies chronologiques sans la bougie du jour (UTC) encore ouverte. */
    private List<MarketData> closedCandles(DailyDataset daily) {
        Instant today = clock.now().truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        return daily.dataset().getMarketDatas().stream()
                .filter(c -> c.getTimestamp().atZone(ZoneOffset.UTC).toInstant().isBefore(today))
                .sorted(Comparator.comparing(MarketData::getTimestamp)).toList();
    }

    private static Integer depthOf(MarketDataSource source) {
        int depth = MarketDataProviderLimits.maxD1Candles(source);
        return depth == MarketDataProviderLimits.MAX_D1_HISTORY_CANDLES ? null : depth;
    }

    private static ExpertApiException insufficient(int required, int available, String provider) {
        Map<String, Object> details = new HashMap<>();
        details.put("requiredPoints", required);
        details.put("availablePoints", available);
        details.put("provider", provider);
        return new ExpertApiException(ExpertApiException.Code.ELIGIBILITY_INSUFFICIENT,
                "Historique insuffisant : " + required + " bougies D1 requises, " + available + " disponibles"
                        + (provider == null ? "" : " chez " + provider), details);
    }

    // ---------------------------------------------------------------- bookkeeping (sous le verrou)

    private void rateLimit(Long owner, Instant now) {
        ArrayDeque<Instant> window = submissions.computeIfAbsent(owner, k -> new ArrayDeque<>());
        Instant oldest = now.minus(settings.rateLimitWindow());
        while (!window.isEmpty() && !window.getFirst().isAfter(oldest)) {
            window.removeFirst();
        }
        if (window.size() >= settings.rateLimitMax()) {
            throw new ExpertApiException(ExpertApiException.Code.SIMULATION_RATE_LIMITED,
                    "Trop de simulations demandées : " + settings.rateLimitMax() + " par " + settings.rateLimitWindow().toSeconds() + " s");
        }
        window.addLast(now);
    }

    private void purgeExpired(Instant now) {
        records.values().removeIf(r -> r.finishedAt() != null && !r.finishedAt().plus(settings.resultTtl()).isAfter(now));
    }

    /** Libère le(s) plus ancien(s) résultat(s) de l'utilisateur pour tenir sous le plafond (la nouvelle simulation compte). */
    private void evictOldest(Long owner) {
        while (records.values().stream().filter(r -> r.ownerId.equals(owner)).count() >= settings.maxResultsPerUser()) {
            records.values().stream().filter(r -> r.ownerId.equals(owner)).min(Comparator.comparing(r -> r.createdAt))
                    .ifPresent(r -> records.remove(r.id));
        }
    }

    private SimulationRecord find(ExpertContext ctx, String id) {
        SimulationRecord record = id == null ? null : records.get(id);
        if (record == null || !record.ownerId.equals(ctx.user().getId())) {
            throw new ExpertApiException(ExpertApiException.Code.SIMULATION_NOT_FOUND, "Simulation introuvable : " + id);
        }
        return record;
    }

    private SimulationDto dto(SimulationRecord r) {
        Instant finished = r.finishedAt();
        return new SimulationDto(r.id, r.type.name(), r.assetSymbol, r.state().name(), r.progress(), r.createdAt, finished,
                finished == null ? null : finished.plus(settings.resultTtl()), r.error(), r.result());
    }

    private static SimulationType parseType(String type) {
        try {
            return SimulationType.valueOf(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type : " + java.util.Arrays.toString(SimulationType.values()) + " attendu (reçu " + type + ")");
        }
    }

    private static MarketDataSource parseSource(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        try {
            return MarketDataSource.valueOf(provider.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provider inconnu : " + provider);
        }
    }
}
