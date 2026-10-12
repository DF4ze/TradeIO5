package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepSide;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathEstimation;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveLiveBlock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBindingRun;
import fr.ses10doigts.tradeIO5.model.entity.execution.PlanStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlan;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderPlanAsset;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderStep;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepOrderType;
import fr.ses10doigts.tradeIO5.model.entity.execution.StepStatus;
import fr.ses10doigts.tradeIO5.model.entity.execution.TransitStock;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRunRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.repository.execution.TransitStockRepository;
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
    private final RainbowLiveBindingRunRepository runRepository;
    private final RainbowLiveOrderPlanRepository planRepository;
    private final TransitStockRepository transitRepository;
    private final OrderPlanner planner;
    private final ExecutionSettings settings;
    private final DomainClock clock;
    private final TransactionTemplate transaction;

    public OrderPlanService(UserRepository userRepository, RainbowLiveBindingRepository bindingRepository,
                            RainbowLiveBindingRunRepository runRepository, RainbowLiveOrderPlanRepository planRepository,
                            TransitStockRepository transitRepository, OrderPlanner planner, ExecutionSettings settings, DomainClock clock,
                            PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.bindingRepository = bindingRepository;
        this.runRepository = runRepository;
        this.planRepository = planRepository;
        this.transitRepository = transitRepository;
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
                UserResult result = transaction.execute(status -> planUser(user, day, pass, false));
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

    /**
     * Remise à zéro d'un plan : recalcule une nouvelle révision (même jour, même passe) même si les entrées n'ont pas changé.
     * Réservé aux plans courants dont AUCUNE étape n'est partie (statut PLANNED, expiré, BLOCKED, DISABLED ou CANCELLED, étapes toutes
     * PLANNED) : un plan dont des ordres ont été envoyés ne se rejoue jamais (réconciliation par clOrdId).
     *
     * @throws IllegalArgumentException plan inconnu, remplacé ou non réinitialisable
     */
    public PlanSummary resetPlan(Long planId) {
        if (!settings.planningEnabled()) {
            throw new IllegalArgumentException("Planification désactivée (mode " + settings.getMode() + ")");
        }
        RainbowLiveOrderPlan plan = planRepository.findById(planId).orElseThrow(() -> new IllegalArgumentException("Plan inconnu : " + planId));
        User user = plan.getUser();
        LocalDate day = plan.getDay();
        RainbowLivePass pass = plan.getPass();
        UserResult result = transaction.execute(status -> {
            RainbowLiveOrderPlan latest = planRepository.findFirstByUserAndDayAndPassOrderByRevisionDesc(user, day, pass)
                    .orElseThrow(() -> new IllegalArgumentException("Plan inconnu : " + planId));
            if (!latest.getId().equals(planId)) {
                throw new IllegalArgumentException("Plan #" + planId + " déjà remplacé par la révision " + latest.getRevision());
            }
            PlanStatus effective = latest.effectiveStatus(clock.now());
            boolean statusOk = effective == PlanStatus.PLANNED || effective == PlanStatus.BLOCKED || effective == PlanStatus.EXPIRED
                    || effective == PlanStatus.DISABLED || effective == PlanStatus.CANCELLED;
            if (!statusOk || latest.getSteps().stream().anyMatch(st -> st.getStatus() != StepStatus.PLANNED)) {
                throw new IllegalArgumentException("Plan #" + planId + " non réinitialisable (statut " + effective
                        + ", des ordres ont été envoyés ou sont à réconcilier)");
            }
            return planUser(user, day, pass, true);
        });
        PlanSummary summary = new PlanSummary(day, pass, result == UserResult.PLANNED ? 1 : 0, result == UserResult.BLOCKED ? 1 : 0,
                result == UserResult.DISABLED ? 1 : 0, result == UserResult.UNCHANGED ? 1 : 0, result == UserResult.SKIPPED ? 1 : 0, 0);
        log.info("Plan d'ordres #{} réinitialisé : {}", planId, summary);
        return summary;
    }

    private UserResult planUser(User user, LocalDate day, RainbowLivePass pass, boolean force) {
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        List<AssetInput> inputs = new ArrayList<>();
        boolean anyRun = false;
        for (RainbowLiveBinding binding : bindings) {
            Optional<RainbowLiveBindingRun> run = runRepository.findByBindingAndDay(binding, day);
            anyRun |= run.isPresent();
            inputs.add(inputOf(binding, run.map(r -> pass == RainbowLivePass.T2355 ? r.getPass2355() : r.getPass0005())
                    .filter(RainbowLiveLiveBlock::hasLiveSnapshot).orElse(null), transitOf(binding, run.map(r -> pass == RainbowLivePass.T2355 ? r.getPass2355() : r.getPass0005())
                    .filter(RainbowLiveLiveBlock::hasLiveSnapshot).map(RainbowLiveLiveBlock::getActionType).orElse(null))));
        }
        if (!anyRun) {
            log.debug("Plan d'ordres : aucun run live pour user={} jour {} passe {}, ignoré", user.getId(), day, pass);
            return UserResult.SKIPPED;
        }

        String hash = OrderPlanner.inputsHash(inputs);
        Optional<RainbowLiveOrderPlan> current = planRepository.findFirstByUserAndDayAndPassOrderByRevisionDesc(user, day, pass);
        if (!force && current.isPresent() && current.get().getInputsHash().equals(hash)) {
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
                    .quoteAmount(s.quoteAmount()).walletId(a.walletId())
                    .clOrdId(ClOrdIds.of(user.getId(), a.walletId(), s.assetSymbol(), day, pass, s.rank()))
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

    /** Stock en transit actif du wallet visant l'actif du lien (devise -> quantité). */
    private Map<String, BigDecimal> transitOf(RainbowLiveBinding binding, RainbowLiveAction action) {
        StepSide side = action == RainbowLiveAction.SELL ? StepSide.SELL : StepSide.BUY;
        return transitRepository.findByWalletIdAndTargetAssetAndResolvedAtIsNull(binding.getWallet().getId(), binding.getAssetSymbol())
                .stream().filter(s -> (s.getFinishSide() == null ? StepSide.BUY : s.getFinishSide()) == side).collect(Collectors.toMap(TransitStock::getCurrency, TransitStock::getQuantity, BigDecimal::add));
    }

    private static AssetInput inputOf(RainbowLiveBinding binding, RainbowLiveLiveBlock block, Map<String, BigDecimal> transit) {
        if (block == null) {
            return new AssetInput(binding.getAssetSymbol(), binding.getPriority(), binding.isExecutionEnabled(),
                    binding.getWallet().getId(), binding.getWallet().getWebProviderCode(), binding.getWallet().getCredential(),
                    null, null, null, null, Map.of(), transit);
        }
        return new AssetInput(binding.getAssetSymbol(), binding.getPriority(), binding.isExecutionEnabled(),
                binding.getWallet().getId(), binding.getWallet().getWebProviderCode(), binding.getWallet().getCredential(),
                block.getStatus(), block.getActionType(), decimal(block.getActionAmountUsdc()),
                decimal(block.getActionQuantity()), cashOf(binding, block), transit);
    }

    private static Map<String, BigDecimal> cashOf(RainbowLiveBinding binding, RainbowLiveLiveBlock block) {
        return block.getStatus() == PortfolioStatus.OK
                ? PortfolioReading.fromSnapshot(block, binding.getAssetSymbol()).cashByMember().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> BigDecimal.valueOf(e.getValue())))
                : Map.of();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
