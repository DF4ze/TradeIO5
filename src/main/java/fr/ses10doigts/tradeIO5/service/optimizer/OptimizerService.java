package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs d'optimisation (admin) : asynchrones, UN SEUL à la fois, résultats en MÉMOIRE avec durée de vie
 * ({@code tradeio.optimizer.result-ttl-minutes}), annulables. Pool d'évaluation dédié ({@code tradeio.optimizer.pool-size}),
 * jamais celui des simulations utilisateur. Désactivé par défaut ({@code tradeio.optimizer.enabled=false} ⇒ bean absent).
 * Lancement tracé au journal admin ({@link AdminAction#RUN}) ; aucune persistance en base.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = OptimizerDefaults.ENABLED_PROPERTY, havingValue = "true")
public class OptimizerService {

    public enum State {
        RUNNING, DONE, FAILED, CANCELLED;

        public boolean isActive() {
            return this == RUNNING;
        }
    }

    /** Vue d'un run : progression et, une fois terminé, rapport JSON. */
    public record RunView(String id, String state, double progress, String stage, String error, String reportJson, Instant createdAt) {
    }

    /** Erreur métier : {@code BUSY} (409) ou {@code NOT_FOUND} (404). */
    public static final class OptimizerException extends RuntimeException {
        public enum Code { BUSY, NOT_FOUND }

        private final Code code;

        public OptimizerException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code code() {
            return code;
        }
    }

    private static final class Run {
        final String id;
        final Instant createdAt;
        final SearchControl control;
        volatile State state = State.RUNNING;
        volatile double progress;
        volatile String stage = "démarrage";
        volatile String error;
        volatile String reportJson;
        volatile Instant finishedAt;

        Run(String id, Instant createdAt) {
            this.id = id;
            this.createdAt = createdAt;
            this.control = new SearchControl((fraction, stageName) -> {
                this.progress = fraction;
                this.stage = stageName;
            });
        }
    }

    private static final String POLICY_TARGET = "optimizer:policy-l2";
    private static final String PRESET_TARGET = "optimizer:preset-l1";

    private final ReplayInputsLoader loader;
    private final AdminActionLogService actionLog;
    private final DomainClock clock;
    private final Duration ttl;
    private final ExecutorService evaluationPool;
    private final ExecutorService coordinator;
    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
    private final Object startLock = new Object();

    public OptimizerService(ReplayInputsLoader loader, AdminActionLogService actionLog, DomainClock clock,
                            @Value("${tradeio.optimizer.pool-size:" + OptimizerDefaults.POOL_SIZE + "}") int poolSize,
                            @Value("${tradeio.optimizer.result-ttl-minutes:" + OptimizerDefaults.RESULT_TTL_MINUTES + "}") long ttlMinutes) {
        this.loader = loader;
        this.actionLog = actionLog;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        AtomicInteger n = new AtomicInteger();
        this.evaluationPool = Executors.newFixedThreadPool(Math.max(1, poolSize), r -> daemon(r, "optimizer-eval-" + n.incrementAndGet()));
        this.coordinator = Executors.newSingleThreadExecutor(r -> daemon(r, "optimizer-run"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(r -> r.control.cancel());
        coordinator.shutdownNow();
        evaluationPool.shutdownNow();
    }

    /** Valide, trace et lance le run ; {@code BUSY} si un run est déjà actif. */
    public RunView start(User admin, OptimizerRunRequest req) {
        OptimizerRunRequest.Mode mode = req.resolvedMode();
        String target;
        Map<String, Object> payload;
        if (mode == OptimizerRunRequest.Mode.POLICY_L2) {
            CalibrationConfig config = CalibrationConfig.of(req);
            target = POLICY_TARGET;
            payload = AdminActionLogService.payload("mode", mode.name(), "cash0", req.cash0(), "profiles",
                    config.profiles().stream().map(Enum::name).toList().toString(), "foldEnds", config.foldEnds().toString(), "seed", req.seed());
        } else if (mode == OptimizerRunRequest.Mode.PRESET_L1) {
            PresetOptimizerConfig config = PresetOptimizerConfig.of(req);
            target = PRESET_TARGET;
            payload = AdminActionLogService.payload("mode", mode.name(), "cash0", req.cash0(), "profile", config.profile().name(), "foldEnds",
                    config.foldEnds().toString(), "seed", req.seed());
        } else {
            ReplayInputsLoader.parseProfile(req.profile());
            target = "optimizer:" + req.asset();
            payload = AdminActionLogService.payload("asset", req.asset(), "walletId", req.walletId(), "cash0", req.cash0(),
                    "endDate", req.endDate() == null ? null : req.endDate().toString(), "profile", req.profile(), "seed", req.seed());
        }
        Run run;
        synchronized (startLock) {
            Instant now = clock.now();
            purgeExpired(now);
            if (runs.values().stream().anyMatch(r -> r.state.isActive())) {
                actionLog.record(admin.getUsername(), AdminAction.RUN, target, payload, AdminActionOutcome.REFUSED);
                throw new OptimizerException(OptimizerException.Code.BUSY, "Un run d'optimisation est déjà en cours");
            }
            run = new Run(UUID.randomUUID().toString(), now);
            runs.put(run.id, run);
        }
        actionLog.record(admin.getUsername(), AdminAction.RUN, target, payload, AdminActionOutcome.OK);
        Run launched = run;
        coordinator.execute(() -> execute(launched, admin, req));
        return view(launched, false);
    }

    private void execute(Run run, User admin, OptimizerRunRequest req) {
        try {
            run.reportJson = switch (req.resolvedMode()) {
                case POLICY_L2 -> calibrate(run, admin, req);
                case PRESET_L1 -> optimizePresets(run, admin, req);
                case ASSET_L2 -> search(run, admin, req);
            };
            run.state = State.DONE;
        } catch (SearchControl.CancelledException e) {
            run.state = State.CANCELLED;
        } catch (IllegalArgumentException e) {
            run.error = e.getMessage();
            run.state = State.FAILED;
        } catch (RuntimeException e) {
            log.error("Run d'optimisation {} en échec", run.id, e);
            run.error = "Run en échec";
            run.state = State.FAILED;
        } finally {
            run.finishedAt = clock.now();
        }
    }

    private String search(Run run, User admin, OptimizerRunRequest req) {
        ScorerInputs inputs = loader.load(admin, req);
        StartingProfile profile = inputs.profile();
        SearchConfig config = SearchConfig.defaults();
        if (req.seed() != null) {
            config = config.withSeed(req.seed());
        }
        if (req.sobolSamples() != null) {
            config = config.withSobolSamples(req.sobolSamples());
        }
        ReplayObjective objective = new ReplayObjective(inputs, L2Params.adapter(profile), L2Params.validator(inputs.limits()),
                OptimizerDefaults.BUMP_DAYS, Map.of());
        SearchResult result = new SearchEngine(evaluationPool).run(L2Params.space(profile), objective, config, run.control);
        return OptimizationReport.of(req.asset() + " " + profile, result, config, inputs).toJson();
    }

    private String calibrate(Run run, User admin, OptimizerRunRequest req) {
        ReferenceWallet wallet = loader.loadReferenceWallet(admin, req);
        return new PolicyCalibrator(evaluationPool).run(wallet, CalibrationConfig.of(req), run.control).toJson();
    }

    /** Presets L1 : walk-forward simple par actif ; le wallet est chargé jusqu'à la fin du dernier test ; rien n'est appliqué ni persisté. */
    private String optimizePresets(Run run, User admin, OptimizerRunRequest req) {
        PresetOptimizerConfig config = PresetOptimizerConfig.of(req);
        ReferenceWallet wallet = loader.loadReferenceWallet(admin, req, config.lastTestEnd());
        return new PresetOptimizer(evaluationPool, PresetSpaceSource.defaults()).run(wallet, config, run.control).toJson();
    }

    public RunView get(String id) {
        purgeExpired(clock.now());
        return view(find(id), true);
    }

    /** Annule le run (s'il est actif) et le libère ; la vue renvoyée est son dernier état connu. */
    public RunView cancel(String id) {
        Run run = find(id);
        run.control.cancel();
        if (run.state.isActive()) {
            run.state = State.CANCELLED;
            run.finishedAt = clock.now();
        }
        return view(run, false);
    }

    private Run find(String id) {
        Run run = runs.get(id);
        if (run == null) {
            throw new OptimizerException(OptimizerException.Code.NOT_FOUND, "Run inconnu ou expiré");
        }
        return run;
    }

    private void purgeExpired(Instant now) {
        runs.values().removeIf(r -> r.finishedAt != null && Duration.between(r.finishedAt, now).compareTo(ttl) > 0);
    }

    private static RunView view(Run r, boolean withReport) {
        return new RunView(r.id, r.state.name(), r.progress, r.stage, r.error, withReport ? r.reportJson : null, r.createdAt);
    }

    List<String> ids() {
        return List.copyOf(runs.keySet());
    }
}
