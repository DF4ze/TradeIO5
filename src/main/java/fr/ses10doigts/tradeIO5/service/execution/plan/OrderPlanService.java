package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePassBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveRun;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveRunRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.service.execution.ExecutionSettings;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Plan d'ordres dry-run pour tous les utilisateurs actifs : lit les bindings et les blocs live de la passe EN BASE (le
 * bench ne dépend pas du plan), appelle {@link OrderPlanner}, enregistre le plan (<b>rien n'est envoyé</b>). Une erreur sur
 * un utilisateur n'arrête jamais les autres ; pas de rattrapage des jours manquants. Mode {@code OFF} : aucun plan.
 */
@Slf4j
@Service
public class OrderPlanService {

    /** Récapitulatif d'un déclenchement (log + réponse de l'endpoint admin). */
    public record PlanSummary(LocalDate day, RainbowLivePass pass, int planned, int blocked, int disabled, int unchanged,
                              int skipped, int errors) {
    }

    private enum UserResult { PLANNED, BLOCKED, DISABLED, UNCHANGED, SKIPPED }

    private final UserRepository userRepository;
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLiveRunRepository runRepository;
    private final RainbowLiveOrderPlanRepository planRepository;
    private final OrderPlanner planner;
    private final ExecutionSettings settings;
    private final DomainClock clock;
    private final TransactionTemplate transaction;

    public OrderPlanService(UserRepository userRepository, RainbowLiveBindingRepository bindingRepository,
                            RainbowLiveRunRepository runRepository, RainbowLiveOrderPlanRepository planRepository,
                            OrderPlanner planner, ExecutionSettings settings, DomainClock clock,
                            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.bindingRepository = bindingRepository;
        this.runRepository = runRepository;
        this.planRepository = planRepository;
        this.planner = planner;
        this.settings = settings;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public PlanSummary planAll(LocalDate day, RainbowLivePass pass) {
        if (!settings.planningEnabled()) {
            log.info("Plan d'ordres : mode {} => aucun plan (jour {} passe {})", settings.getMode(), day, pass);
            return new PlanSummary(day, pass, 0, 0, 0, 0, 0, 0);
        }
        int[] counts = new int[UserResult.values().length];
        int errors = 0;
        for (User user : userRepository.findByEnabledTrueAndArchivedAtIsNull()) {
            try {
                UserResult result = transaction.execute(status -> planUser(user, day, pass));
                counts[result.ordinal()]++;
            } catch (Exception e) {
                errors++;
                log.error("Plan d'ordres : échec pour user={} jour {} passe {}", user.getId(), day, pass, e);
            }
        }
        PlanSummary summary = new PlanSummary(day, pass, counts[UserResult.PLANNED.ordinal()], counts[UserResult.BLOCKED.ordinal()],
                counts[UserResult.DISABLED.ordinal()], counts[UserResult.UNCHANGED.ordinal()], counts[UserResult.SKIPPED.ordinal()], errors);
        log.info("Plan d'ordres : {}", summary);
        return summary;
    }

    private UserResult planUser(User user, LocalDate day, RainbowLivePass pass) {
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        List<AssetInput> inputs = new ArrayList<>();
        boolean anyRun = false;
        for (RainbowLiveBinding binding : bindings) {
            Optional<RainbowLiveRun> run = runRepository.findByPresetAndDay(binding.getPreset(), day);
            anyRun |= run.isPresent();
            inputs.add(inputOf(binding, run.map(r -> pass == RainbowLivePass.T2355 ? r.getPass2355() : r.getPass0005())
                    .filter(RainbowLivePassBlock::hasLiveSnapshot).orElse(null)));
        }
        if (!anyRun) {
            log.debug("Plan d'ordres : aucun run live pour user={} jour {} passe {}, ignoré", user.getId(), day, pass);
            return UserResult.SKIPPED;
        }

        String hash = OrderPlanner.inputsHash(inputs);
        Optional<RainbowLiveOrderPlan> current = planRepository.findFirstByUserAndDayAndPassOrderByRevisionDesc(user, day, pass);
        if (current.isPresent() && current.get().getInputsHash().equals(hash)) {
            log.debug("Plan d'ordres inchangé user={} jour {} passe {}", user.getId(), day, pass);
            return UserResult.UNCHANGED;
        }

        PlanDraft draft = planner.plan(inputs);
        Instant now = clock.now();
        RainbowLiveOrderPlan plan = RainbowLiveOrderPlan.builder().user(user).day(day).pass(pass)
                .revision(current.map(p -> p.getRevision() + 1).orElse(1)).status(draft.status()).mode(settings.getMode())
                .totalCostPct(draft.totalCostPct()).feeTestLevel(draft.feeTestLevel()).blockReason(draft.blockReason())
                .inputsHash(hash).createdAt(now).expiresAt(now.plus(settings.getPlanTtl())).build();
        draft.assets().forEach(a -> {
            plan.addAsset(RainbowLiveOrderPlanAsset.builder().assetSymbol(a.assetSymbol()).priority(a.priority())
                    .walletId(a.walletId()).action(a.action()).outcome(a.outcome()).blockReason(a.blockReason())
                    .costPct(a.costPct()).feeTestLevel(a.feeTestLevel()).warning(a.warning()).build());
            a.steps().forEach(s -> plan.addStep(RainbowLiveOrderStep.builder().rank(s.rank()).assetSymbol(s.assetSymbol())
                    .instId(s.instId()).side(s.side()).ordType(StepOrderType.LIMIT_IOC).sz(s.sz()).px(s.px())
                    .quoteAmount(s.quoteAmount()).clOrdId(ClOrdIds.of(user.getId(), s.assetSymbol(), day, pass, s.rank()))
                    .feePct(s.feePct()).spreadPct(s.spreadPct()).slippagePct(s.slippagePct()).status(StepStatus.PLANNED)
                    .estimation(s.estimation() == null ? PathEstimation.BOOK : s.estimation()).build()));
        });
        current.ifPresent(previous -> {
            previous.setStatus(PlanStatus.SUPERSEDED);
            planRepository.saveAndFlush(previous);
            log.info("Plan d'ordres user={} jour {} passe {} : révision {} remplace la révision {}", user.getId(), day, pass,
                    plan.getRevision(), previous.getRevision());
        });
        planRepository.save(plan);
        log.info("Plan d'ordres user={} jour {} passe {} : {} ({} étape(s)) expire à {}", user.getId(), day, pass, plan.getStatus(),
                plan.getSteps().size(), plan.getExpiresAt());
        return switch (plan.getStatus()) {
            case BLOCKED -> UserResult.BLOCKED;
            case DISABLED -> UserResult.DISABLED;
            default -> UserResult.PLANNED;
        };
    }

    private static AssetInput inputOf(RainbowLiveBinding binding, RainbowLivePassBlock block) {
        if (block == null) {
            return new AssetInput(binding.getAssetSymbol(), binding.getPriority(), binding.isExecutionEnabled(),
                    binding.getWallet().getId(), binding.getWallet().getWebProviderCode(), binding.getWallet().getCredential(),
                    null, null, null, null, Map.of());
        }
        return new AssetInput(binding.getAssetSymbol(), binding.getPriority(), binding.isExecutionEnabled(),
                binding.getWallet().getId(), binding.getWallet().getWebProviderCode(), binding.getWallet().getCredential(),
                block.getLiveStatus(), block.getLiveActionType(), decimal(block.getLiveActionAmountUsdc()),
                decimal(block.getLiveActionQuantity()), cashOf(binding, block));
    }

    private static Map<String, BigDecimal> cashOf(RainbowLiveBinding binding, RainbowLivePassBlock block) {
        return block.getLiveStatus() == PortfolioStatus.OK
                ? PortfolioReading.fromSnapshot(block, binding.getAssetSymbol()).cashByMember().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> BigDecimal.valueOf(e.getValue())))
                : Map.of();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
