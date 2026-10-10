package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePresetEventType;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetEventService;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD des bindings (preset live + wallet réel par actif), scopé à l'utilisateur. Aucun ordre, aucun branchement moteur.
 * Création et modification exécutent {@link BindingCheck} et en retournent le résultat sans le persister (un KO n'empêche
 * pas d'enregistrer le lien : il est revérifié à chaque passe).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveBindingService {

    public static final double DEFAULT_BAG_PERCENT = 100.0;
    private static final double MAX_BAG_PERCENT = 100.0;

    private final RainbowLiveBindingRepository bindingRepository;
    private final RainbowLivePresetService presetService;
    private final WalletRepository walletRepository;
    private final BindingCheck bindingCheck;
    private final RainbowLivePresetEventService eventService;
    private final DomainClock clock;

    /** Création : {@code bagPercent} et {@code priority} nuls => défauts (100 %, rang de l'actif). */
    public record CreateRequest(String assetSymbol, Long presetId, Long walletId, Double bagPercent, Integer priority) {
    }

    /** Modification : l'actif est immuable ; {@code bagPercent} / {@code priority} nuls => inchangés. */
    public record UpdateRequest(Long presetId, Long walletId, Double bagPercent, Integer priority) {
    }

    /** Binding + résultat de la vérification de disponibilité. */
    public record Checked(RainbowLiveBinding binding, BindingCheckResult check) {
    }

    @Transactional
    public Checked create(User user, CreateRequest req) {
        requireAllowedAsset(req.assetSymbol());
        bindingRepository.findByUserAndAssetSymbol(user, req.assetSymbol()).ifPresent(existing -> {
            throw new RainbowLiveBindingConflictException(req.assetSymbol(), existing.getId());
        });
        RainbowLivePreset preset = presetFor(user, req.presetId(), req.assetSymbol());
        Wallet wallet = walletOf(user, req.walletId());
        double bag = req.bagPercent() == null ? DEFAULT_BAG_PERCENT : requireBagPercent(req.bagPercent());
        int priority = req.priority() == null ? defaultPriority(req.assetSymbol()) : req.priority();

        RainbowLiveBinding saved = bindingRepository.save(RainbowLiveBinding.builder()
                .user(user).assetSymbol(req.assetSymbol()).preset(preset).wallet(wallet)
                .bagPercent(bag).priority(priority).build());
        eventService.record(user, saved.getAssetSymbol(), RainbowLivePresetEventType.LIVE_SWITCH, null, preset,
                strategyRevision(preset), "Preset live défini");
        log.info("Binding créé id={} user={} actif={} preset={} wallet={} bag={}% priorité={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), preset.getId(), wallet.getId(), bag, priority);
        return checked(saved);
    }

    /** Bascule du live = changer {@code presetId} ; l'ancien preset n'est plus lié (simulation), historique conservé. */
    @Transactional
    public Checked update(User user, Long id, UpdateRequest req) {
        RainbowLiveBinding binding = owned(user, id);
        if (req.presetId() != null) {
            RainbowLivePreset before = binding.getPreset();
            RainbowLivePreset after = presetFor(user, req.presetId(), binding.getAssetSymbol());
            binding.setPreset(after);
            if (!before.getId().equals(after.getId())) {
                eventService.record(user, binding.getAssetSymbol(), RainbowLivePresetEventType.LIVE_SWITCH, before,
                        after, strategyRevision(after), "Bascule du preset live");
            }
        }
        if (req.walletId() != null) {
            binding.setWallet(walletOf(user, req.walletId()));
        }
        if (req.bagPercent() != null) {
            binding.setBagPercent(requireBagPercent(req.bagPercent()));
        }
        if (req.priority() != null) {
            binding.setPriority(req.priority());
        }
        RainbowLiveBinding saved = bindingRepository.save(binding);
        log.info("Binding modifié id={} user={} actif={} preset={} wallet={} bag={}% priorité={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), saved.getPreset().getId(),
                saved.getWallet().getId(), saved.getBagPercent(), saved.getPriority());
        return checked(saved);
    }

    /** Interrupteur d'exécution du binding (propriétaire). Désactiver ne touche pas à la double validation déjà acquise. */
    @Transactional
    public RainbowLiveBinding setExecution(User user, Long id, boolean enabled) {
        RainbowLiveBinding binding = owned(user, id);
        binding.setExecutionEnabled(enabled);
        log.info("Binding {} user={} executionEnabled={}", id, user.getId(), enabled);
        return bindingRepository.save(binding);
    }

    /** Armement du 1ᵉʳ ordre réel par l'admin : remet la confirmation du propriétaire à zéro. */
    @Transactional
    public RainbowLiveBinding arm(Long id) {
        RainbowLiveBinding binding = bindingRepository.findById(id).orElseThrow(() -> RainbowLiveBindingNotFoundException.binding(id));
        binding.setFirstLiveApprovedAt(clock.now());
        binding.setFirstLiveConfirmedAt(null);
        log.info("Binding {} armé pour le 1er ordre réel", id);
        return bindingRepository.save(binding);
    }

    /** Confirmation du propriétaire ; refusée (409) tant que l'admin n'a pas armé. */
    @Transactional
    public RainbowLiveBinding confirmFirstLive(User user, Long id) {
        RainbowLiveBinding binding = owned(user, id);
        if (binding.getFirstLiveApprovedAt() == null) {
            throw new RainbowLiveBindingStateException("Le 1er ordre réel n'a pas été armé par l'administrateur");
        }
        binding.setFirstLiveConfirmedAt(clock.now());
        log.info("Binding {} user={} : 1er ordre réel confirmé", id, user.getId());
        return bindingRepository.save(binding);
    }

    @Transactional
    public void delete(User user, Long id) {
        RainbowLiveBinding binding = owned(user, id);
        bindingRepository.delete(binding);
        log.info("Binding supprimé id={} user={} actif={}", id, user.getId(), binding.getAssetSymbol());
    }

    @Transactional(readOnly = true)
    public List<RainbowLiveBinding> list(User user) {
        return bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
    }

    @Transactional(readOnly = true)
    public RainbowLiveBinding get(User user, Long id) {
        return owned(user, id);
    }

    /** Revérifie à la demande (appels exchange : lecture des soldes en cache 60 s + endpoint public). */
    @Transactional
    public BindingCheckResult check(User user, Long id) {
        return checked(owned(user, id)).check();
    }

    /** Vérifie le binding et persiste son statut de tradabilité (lu par la passe live et par {@link ExecutionGuard}). */
    private Checked checked(RainbowLiveBinding binding) {
        BindingCheckResult result = bindingCheck.check(binding);
        binding.recordCheck(result, clock.now());
        return new Checked(bindingRepository.save(binding), result);
    }

    private RainbowLiveBinding owned(User user, Long id) {
        return bindingRepository.findById(id)
                .filter(b -> b.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.binding(id));
    }

    /** Preset de l'utilisateur (sinon 404) et de l'actif du binding (sinon 400). */
    private RainbowLivePreset presetFor(User user, Long presetId, String assetSymbol) {
        if (presetId == null) {
            throw new IllegalArgumentException("presetId est requis");
        }
        RainbowLivePreset preset = presetService.get(user, presetId);
        if (!preset.getAssetSymbol().equals(assetSymbol)) {
            throw new IllegalArgumentException("Le preset " + presetId + " est sur " + preset.getAssetSymbol()
                    + ", pas sur " + assetSymbol);
        }
        return preset;
    }

    private static Integer strategyRevision(RainbowLivePreset preset) {
        return preset.isFollowingStrategy() ? preset.getAssetStrategy().getRevision() : null;
    }

    private Wallet walletOf(User user, Long walletId) {
        if (walletId == null) {
            throw new IllegalArgumentException("walletId est requis");
        }
        return walletRepository.findById(walletId)
                .filter(w -> w.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.wallet(walletId));
    }

    private static double requireBagPercent(double bagPercent) {
        if (Double.isNaN(bagPercent) || bagPercent < 0 || bagPercent > MAX_BAG_PERCENT) {
            throw new IllegalArgumentException("bagPercent doit être compris entre 0 et 100 : " + bagPercent);
        }
        return bagPercent;
    }

    private static void requireAllowedAsset(String asset) {
        if (asset == null || !RainbowLiveDefaultPresets.ASSETS.contains(asset)) {
            throw new IllegalArgumentException("Actif non autorisé : " + asset
                    + " (autorisés : " + RainbowLiveDefaultPresets.ASSETS + ")");
        }
    }

    /** Rang de l'actif dans {@link RainbowLiveDefaultPresets#ASSETS} : BTC=0, ETH=1, PAXG=2. */
    private static int defaultPriority(String asset) {
        return RainbowLiveDefaultPresets.ASSETS.indexOf(asset);
    }
}
