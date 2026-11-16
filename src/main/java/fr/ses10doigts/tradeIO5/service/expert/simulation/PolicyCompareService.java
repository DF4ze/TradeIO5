package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.model.dto.expert.EpisodeDtos.Gain;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyRunDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicySpec;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveAction;
import fr.ses10doigts.tradeIO5.service.expert.ExpertApiException;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.expert.ExpertValidationException;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicies;
import fr.ses10doigts.tradeIO5.service.expert.simulation.policy.ModulationPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Comparaison de politiques de modulation sur un même wallet / plage / capital (mode EXPERT) : un chargement des bougies, puis un
 * rejeu {@code CONSTANT} (référence) et un par politique demandée (max {@value #MAX_POLICIES}). Synchrone, un seul calcul à la fois
 * par utilisateur ; sans frais par défaut. Aucune écriture, aucun ordre.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyCompareService {

    static final int MAX_POLICIES = 5;

    private static final double EPSILON = 1e-9;
    private static final FullSimulationService.Hooks NO_HOOKS = new FullSimulationService.Hooks(() -> {
    }, () -> {
    }, () -> false);

    private final FullSimulationService full;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    @Transactional
    public PolicyCompareDto compare(ExpertContext ctx, PolicyCompareRequest request) {
        if (request == null) {
            throw new ExpertValidationException("policies", "obligatoire (1 à " + MAX_POLICIES + " politiques)");
        }
        List<PolicySpec> specs = request.policies() == null ? List.of() : request.policies();
        if (specs.isEmpty() || specs.size() > MAX_POLICIES) {
            throw new ExpertValidationException("policies", "1 à " + MAX_POLICIES + " politiques attendues, reçu " + specs.size());
        }
        List<ModulationPolicy> policies = new ArrayList<>();
        policies.add(ModulationPolicies.from(new PolicySpec(ModulationPolicies.CONSTANT, Map.of())));
        specs.forEach(spec -> policies.add(ModulationPolicies.from(spec)));
        FullRequest base = request.full() == null ? new FullRequest(null, null, null, null, null, null, null, null, null, false, null, null)
                : request.full();
        if (base.withCosts() == null) {
            base = new FullRequest(base.walletId(), base.from(), base.to(), base.cash0(), base.exposureMin(), base.exposureMax(),
                    base.power(), base.bias(), base.regulationMarginPct(), false, base.links(), null);
        }
        Long owner = ctx.user().getId();
        if (!running.add(owner)) {
            throw new ExpertApiException(ExpertApiException.Code.SIMULATION_ALREADY_RUNNING,
                    "Une comparaison est déjà en cours : attendez son résultat");
        }
        try {
            FullSimulationService.Plan plan = full.resolve(ctx, base);
            FullSimulationService.Loaded data = full.load(ctx.user(), plan, NO_HOOKS);
            List<PolicyRunDto> runs = new ArrayList<>();
            runs.add(run(data, plan, policies.getFirst(), new PolicySpec(ModulationPolicies.CONSTANT, Map.of())));
            for (int i = 0; i < specs.size(); i++) {
                runs.add(run(data, plan, policies.get(i + 1), specs.get(i)));
            }
            return new PolicyCompareDto(plan.walletId(), plan.from(), plan.to(), data.cash0(),
                    data.costs() != null, runs, new ArrayList<>(data.warnings()));
        } finally {
            running.remove(owner);
        }
    }

    private PolicyRunDto run(FullSimulationService.Loaded data, FullSimulationService.Plan plan, ModulationPolicy policy, PolicySpec spec) {
        long t0 = System.nanoTime();
        MultiLinkReplayEngine.Result r = full.run(data, plan.settings(), policy);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (r.walletDays().isEmpty()) {
            throw new ExpertValidationException("from", "aucun jour rejouable dans la plage (SMA / ATR pas encore définis)");
        }
        List<LocalDate> days = r.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::day).toList();
        List<Double> totals = r.walletDays().stream().map(MultiLinkReplayEngine.WalletDay::totalValue).toList();
        double maxDd = DrawdownMeter.measure(days, totals).maxPct();
        double avgExposure = r.walletDays().stream().filter(w -> w.totalValue() > EPSILON)
                .mapToDouble(w -> 100.0 * w.assetsValue() / w.totalValue()).average().orElse(0.0);
        Gain gain = EpisodeTimeline.compute(EpisodeSeries.build(r, data.links()), days.getFirst(), days.getLast()).gain().total();
        long blocked = r.linkDays().stream().filter(x -> x.wantedAction() != RainbowLiveAction.NONE
                && x.action() != RainbowLiveAction.BUY && x.action() != RainbowLiveAction.SELL).count();
        long belowMin = r.costDays().stream().filter(x -> "BELOW_MIN".equals(x.outcome())).count();
        double fin = r.finalValue();
        double fix = r.fixedFinalValue();
        log.info("Comparaison de politiques : {} => {} USDC (DCA fixe {}), {} ms", policy.label(), Math.round(fin), Math.round(fix), ms);
        return new PolicyRunDto(policy.label(), spec, fin, fix, fin - fix, fix > EPSILON ? 100.0 * (fin - fix) / fix : null, maxDd,
                avgExposure, gain, blocked, belowMin, ms);
    }
}
