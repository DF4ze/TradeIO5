package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveMode;
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
 * CRUD des liens/bindings (preset + wallet réel ; unicité {@code (utilisateur, actif, wallet)}, le preset est un attribut hors
 * unicité et peut alimenter plusieurs liens), scopé à l'utilisateur. Aucun ordre, aucun branchement moteur.
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
    public record CreateRequest(String assetSymbol, Long presetId, Long walletId, Double bagPercent, Integer priority,
                                Double baseAmount) {
        public CreateRequest(String assetSymbol, Long presetId, Long walletId, Double bagPercent, Integer priority) {
            this(assetSymbol, presetId, walletId, bagPercent, priority, null);
        }
    }

    /** Modification : l'actif est immuable ; {@code bagPercent} / {@code priority} nuls => inchangés. */
    public record UpdateRequest(Long presetId, Long walletId, Double bagPercent, Integer priority, Double baseAmount,
                                boolean resetBaseAmount) {
        public UpdateRequest(Long presetId, Long walletId, Double bagPercent, Integer priority) {
            this(presetId, walletId, bagPercent, priority, null, false);
        }
    }

    /** Origine d'un preset candidat : suit la stratégie système, ou détaché/créé par l'utilisateur. */
    public enum PresetOrigin { SYSTEM, CUSTOM }

    /**
     * Preset de l'utilisateur pour un actif, proposé au choix « stratégie puis preset » ; {@code alreadyLinkedBindingId} =
     * lien du wallet demandé qui utilise déjà ce preset (nul sinon).
     */
    public record Candidate(Long id, String name, String mode, PresetOrigin origin, Long alreadyLinkedBindingId) {
    }

    /** Binding + résultat de la vérification de disponibilité. */
    public record Checked(RainbowLiveBinding binding, BindingCheckResult check) {
    }

    @Transactional
    public Checked create(User user, CreateRequest req) {
        requireAllowedAsset(req.assetSymbol());
        Wallet wallet = walletOf(user, req.walletId());
        bindingRepository.findByUserAndAssetSymbolAndWallet_Id(user, req.assetSymbol(), wallet.getId()).ifPresent(existing -> {
            throw new RainbowLiveBindingConflictException(req.assetSymbol(), wallet.getId(), existing.getId());
        });
        RainbowLivePreset preset = presetFor(user, req.presetId(), req.assetSymbol());
        double bag = req.bagPercent() == null ? DEFAULT_BAG_PERCENT : requireBagPercent(req.bagPercent());
        int priority = req.priority() == null ? defaultPriority(req.assetSymbol()) : req.priority();

        RainbowLiveBinding saved = bindingRepository.save(RainbowLiveBinding.builder()
                .user(user).assetSymbol(req.assetSymbol()).preset(preset).wallet(wallet)
                .bagPercent(bag).priority(priority).baseAmount(optionalBaseAmount(req.baseAmount())).build());
        eventService.record(user, saved.getAssetSymbol(), RainbowLivePresetEventType.LIVE_SWITCH, null, preset,
                strategyRevision(preset), "Preset live défini");
        log.info("Binding créé id={} user={} actif={} preset={} wallet={} bag={}% priorité={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), preset.getId(), wallet.getId(), bag, priority);
        return checked(saved);
    }

    /** Bascule du live = changer {@code presetId} ; l'ancien preset n'est plus lié, historique conservé. */
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
            Wallet wallet = walletOf(user, req.walletId());
            bindingRepository.findByUserAndAssetSymbolAndWallet_Id(user, binding.getAssetSymbol(), wallet.getId())
                    .filter(other -> !other.getId().equals(binding.getId())).ifPresent(other -> {
                        throw new RainbowLiveBindingConflictException(binding.getAssetSymbol(), wallet.getId(), other.getId());
                    });
            binding.setWallet(wallet);
        }
        if (req.bagPercent() != null) {
            binding.setBagPercent(requireBagPercent(req.bagPercent()));
        }
        if (req.priority() != null) {
            binding.setPriority(req.priority());
        }
        if (req.resetBaseAmount()) {
            binding.setBaseAmount(null);
        } else if (req.baseAmount() != null) {
            binding.setBaseAmount(optionalBaseAmount(req.baseAmount()));
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

    /** Presets de l'utilisateur sur {@code asset}, pour le choix stratégie -> preset du lien (aucun appel exchange). */
    @Transactional
    public List<Candidate> candidates(User user, Long walletId, String asset) {
        requireAllowedAsset(asset);
        Wallet wallet = walletOf(user, walletId);
        presetService.ensureStrategyPresets(user);
        return presetService.list(user).stream().filter(p -> p.getAssetSymbol().equals(asset))
                .map(p -> new Candidate(p.getId(), p.getName(), p.isTrendMix() ? RainbowLiveMode.TREND_MIX.name()
                        : RainbowLiveMode.FIXED.name(), p.isFollowingStrategy() ? PresetOrigin.SYSTEM : PresetOrigin.CUSTOM,
                        bindingRepository.findByUserAndAssetSymbolAndWallet_Id(user, asset, wallet.getId())
                                .filter(b -> b.getPreset().getId().equals(p.getId())).map(RainbowLiveBinding::getId)
                                .orElse(null)))
                .toList();
    }

    /** Wallets actifs de l'utilisateur, pour le formulaire de binding. */
    @Transactional(readOnly = true)
    public List<Wallet> wallets(User user) {
        return walletRepository.findByUserAndEnabledTrue(user);
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

    /** Montant de base du lien : nul (celui du preset) ou strictement positif et fini. */
    private static Double optionalBaseAmount(Double baseAmount) {
        if (baseAmount != null && (Double.isNaN(baseAmount) || Double.isInfinite(baseAmount) || baseAmount <= 0)) {
            throw new IllegalArgumentException("baseAmount doit être > 0 : " + baseAmount);
        }
        return baseAmount;
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
