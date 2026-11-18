package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.LinkOverride;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.expert.ExpertContext;
import fr.ses10doigts.tradeIO5.service.expert.simulation.FullSimulationService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Charge les {@link ScorerInputs} d'un run : réutilise la résolution et le chargement du simulateur complet (bougies de la source
 * du lien, sans fallback, cache DB des candles) sans coûts réseau (frais / carnets) ; capital, date de fin et profil viennent de la
 * demande, jamais de l'horloge ni du wallet réel. Le montant de base de référence de chaque lien suit la proposition d'onboarding
 * ({@link ReferenceBase}).
 */
@Service
@ConditionalOnProperty(name = OptimizerDefaults.ENABLED_PROPERTY, havingValue = "true")
@RequiredArgsConstructor
public class ReplayInputsLoader {

    private static final FullSimulationService.Hooks NO_HOOKS = new FullSimulationService.Hooks(() -> {
    }, () -> {
    }, () -> false);

    private final FullSimulationService full;
    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowModulationControlService controlService;
    private final AffectationConfigResolver configResolver;

    /** Un actif (mode {@code ASSET_L2}) : lien actif du wallet demandé. */
    @Transactional
    public ScorerInputs load(User user, OptimizerRunRequest req) {
        StartingProfile profile = parseProfile(req.profile());
        if (req.asset() == null || req.asset().isBlank() || req.walletId() == null || req.cash0() == null || req.endDate() == null) {
            throw new IllegalArgumentException("asset, walletId, cash0, endDate et profile sont obligatoires");
        }
        List<ScorerInputs.AssetInput> assets = ReferenceBase.assign(loadLink(user, req.asset(), req.walletId(), req.cash0(), req.endDate()),
                req.cash0(), autonomyDays());
        if (req.baseAmount() != null) {
            assets = assets.stream().map(a -> a.withReferenceBase(req.baseAmount())).toList();
        }
        return ScorerInputs.of(assets, req.cash0(), req.endDate(), profile, controlService.current());
    }

    /**
     * Wallet de référence (mode {@code POLICY_L2}) : un lien par actif demandé, cash commun et capital normalisé ; la date de fin du
     * chargement est le dernier jour de test des plis. Les liens peuvent venir de wallets différents (seuls leurs bougies, preset et
     * identité de service comptent : le cash est celui du wallet de référence).
     */
    @Transactional
    public ReferenceWallet loadReferenceWallet(User user, OptimizerRunRequest req) {
        return loadReferenceWallet(user, req, CalibrationConfig.of(req).lastTestEnd());
    }

    /** Idem avec une date de fin de chargement explicite (jour clos) : le dernier jour de test des plis du mode demandé. */
    @Transactional
    public ReferenceWallet loadReferenceWallet(User user, OptimizerRunRequest req, LocalDate end) {
        double cash0 = req.cash0() != null ? req.cash0() : OptimizerDefaults.REFERENCE_CASH_USD;
        List<OptimizerRunRequest.LinkRef> refs = req.links() != null && !req.links().isEmpty() ? req.links() : defaultLinks(user);
        List<ScorerInputs.AssetInput> assets = new ArrayList<>();
        for (OptimizerRunRequest.LinkRef ref : refs) {
            assets.addAll(loadLink(user, ref.asset(), ref.walletId(), cash0, end));
        }
        int days = autonomyDays();
        return new ReferenceWallet(ScorerInputs.of(ReferenceBase.assign(assets, cash0, days), cash0, end, StartingProfile.BALANCED,
                controlService.current()), days);
    }

    private List<OptimizerRunRequest.LinkRef> defaultLinks(User user) {
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        List<OptimizerRunRequest.LinkRef> refs = new ArrayList<>();
        for (String asset : OptimizerDefaults.REFERENCE_ASSETS) {
            RainbowLiveBinding binding = bindings.stream().filter(b -> b.getAssetSymbol().equalsIgnoreCase(asset)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Aucun lien " + asset + " pour le wallet de référence"));
            refs.add(new OptimizerRunRequest.LinkRef(asset, binding.getWallet().getId()));
        }
        return refs;
    }

    private int autonomyDays() {
        return controlService.current().getAutonomyDays();
    }

    /** Le lien actif de {@code asset} sur {@code walletId}, bougies chargées jusqu'à {@code endDate} (jour clos). */
    private List<ScorerInputs.AssetInput> loadLink(User user, String asset, Long walletId, double cash0, LocalDate endDate) {
        List<LinkOverride> overrides = new ArrayList<>();
        boolean found = false;
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
        for (RainbowLiveBinding b : bindings) {
            if (!b.getWallet().getId().equals(walletId)) {
                continue;
            }
            boolean target = b.getAssetSymbol().equalsIgnoreCase(asset);
            found |= target;
            overrides.add(new LinkOverride(b.getId(), target, null, null));
        }
        if (!found) {
            throw new IllegalArgumentException("Aucun lien " + asset + " sur le wallet #" + walletId);
        }
        FullSimulationService.Plan plan = full.resolve(new ExpertContext(user, true), new FullRequest(walletId,
                endDate.minusMonths(OptimizerDefaults.WINDOW_SHORT_MONTHS), endDate, cash0, null, null, null, null, null, false, overrides,
                null, null));
        if (!plan.to().equals(endDate)) {
            throw new IllegalArgumentException("endDate postérieure au dernier jour clos (" + plan.to() + ")");
        }
        FullSimulationService.Loaded loaded = full.load(user, plan, NO_HOOKS);
        Map<Long, RainbowLiveBinding> byId = bindings.stream().collect(Collectors.toMap(RainbowLiveBinding::getId, Function.identity()));
        List<ScorerInputs.AssetInput> assets = new ArrayList<>();
        for (int i = 0; i < loaded.links().size(); i++) {
            var link = loaded.links().get(i);
            double mean = RainbowLiveOnboardingService.averageMultiplier(configResolver.config(byId.get(link.slot().bindingId()).getAffectation()));
            assets.add(new ScorerInputs.AssetInput(link.slot(), link.signal(), loaded.metas().get(i).usedSource(), mean, null));
        }
        return assets;
    }

    public static StartingProfile parseProfile(String name) {
        try {
            return StartingProfile.valueOf(name == null ? "" : name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("profil inconnu : " + name);
        }
    }
}
