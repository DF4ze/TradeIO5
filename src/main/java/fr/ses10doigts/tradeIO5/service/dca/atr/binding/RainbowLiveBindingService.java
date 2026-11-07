package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.BindingExecutionMode;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.repository.WalletRepository;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetAffectationService;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * CRUD des liens/bindings (Affectation + wallet réel ; unicité {@code (utilisateur, actif, wallet)}, l'Affectation est un attribut hors
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
    private final AssetAffectationService affectationService;
    private final WalletRepository walletRepository;
    private final BindingCheck bindingCheck;
    private static final java.util.regex.Pattern ROUTE_FORMAT = java.util.regex.Pattern.compile("[A-Z0-9]{2,10}(>[A-Z0-9]{2,10}){1,2}");

    private final DomainClock clock;

    /** Création : {@code bagPercent} et {@code priority} nuls => défauts (100 %, rang de l'actif). */
    public record CreateRequest(String assetSymbol, Long walletId, Double bagPercent, Integer priority,
                                Double baseAmount, String preferredRoute) {
        public CreateRequest(String assetSymbol, Long walletId, Double bagPercent, Integer priority) {
            this(assetSymbol, walletId, bagPercent, priority, null, null);
        }
    }

    /** Modification : l'actif est immuable ; {@code bagPercent} / {@code priority} nuls => inchangés. */
    public record UpdateRequest(Long walletId, Double bagPercent, Integer priority, Double baseAmount,
                                boolean resetBaseAmount, Double minBuyUsd, boolean resetMinBuy) {
        public UpdateRequest(Long walletId, Double bagPercent, Integer priority) {
            this(walletId, bagPercent, priority, null, false, null, false);
        }
    }

    /** Binding + résultat de la vérification de disponibilité. */
    public record Checked(RainbowLiveBinding binding, BindingCheckResult check) {
    }

    @Transactional
    public Checked create(User user, CreateRequest req) {
        requireAllowedAsset(req.assetSymbol());
        Wallet wallet = walletOf(user, req.walletId());
        RainbowLiveBinding archived = bindingRepository.findByUserAndAssetSymbolAndWallet_Id(user, req.assetSymbol(), wallet.getId())
                .map(existing -> {
                    if (!existing.isArchived()) {
                        throw new RainbowLiveBindingConflictException(req.assetSymbol(), wallet.getId(), existing.getId());
                    }
                    return existing;
                }).orElse(null);
        AssetAffectation affectation = affectationService.forAsset(user, req.assetSymbol());
        double bag = req.bagPercent() == null ? DEFAULT_BAG_PERCENT : requireBagPercent(req.bagPercent());
        int priority = req.priority() == null ? defaultPriority(req.assetSymbol()) : req.priority();

        RainbowLiveBinding toSave = archived == null
                ? RainbowLiveBinding.builder().user(user).assetSymbol(req.assetSymbol()).wallet(wallet).build() : archived;
        toSave.setAffectation(affectation);
        toSave.setBagPercent(bag);
        toSave.setPriority(priority);
        toSave.setBaseAmount(optionalBaseAmount(req.baseAmount()));
        toSave.setPreferredRoute(optionalRoute(req.preferredRoute(), req.assetSymbol()));
        if (archived != null) { // réactivation : repart d'un lien neuf (exécution coupée, 1er ordre à re-valider)
            toSave.setArchivedAt(null);
            toSave.applyExecutionMode(BindingExecutionMode.DISABLED);
            toSave.setMinBuyUsd(null);
            toSave.setFirstLiveApprovedAt(null);
            toSave.setFirstLiveConfirmedAt(null);
        }
        RainbowLiveBinding saved = bindingRepository.save(toSave);
        log.info("Binding créé id={} user={} actif={} affectation={} wallet={} bag={}% priorité={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), affectation.getId(), wallet.getId(), bag, priority);
        return checked(saved);
    }

    /** Modification du lien (wallet, part, priorité, montants) ; les presets se changent sur l'Affectation. */
    @Transactional
    public Checked update(User user, Long id, UpdateRequest req) {
        RainbowLiveBinding binding = owned(user, id);
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
        if (req.resetMinBuy()) {
            binding.setMinBuyUsd(null);
        } else if (req.minBuyUsd() != null) {
            binding.setMinBuyUsd(optionalMinBuy(req.minBuyUsd()));
        }
        RainbowLiveBinding saved = bindingRepository.save(binding);
        log.info("Binding modifié id={} user={} actif={} affectation={} wallet={} bag={}% priorité={}",
                saved.getId(), user.getId(), saved.getAssetSymbol(), saved.getAffectation().getId(),
                saved.getWallet().getId(), saved.getBagPercent(), saved.getPriority());
        return checked(saved);
    }

    /**
     * Interrupteur d'exécution du binding (propriétaire). La 1ʳᵉ activation <b>arme automatiquement</b> le 1ᵉʳ ordre réel
     * (l'utilisateur n'attend pas l'admin) ; il reste à confirmer ({@link #confirmFirstLive}). Désactiver ne touche pas à la
     * double validation déjà acquise ; un réarmement admin ({@link #arm}) la remet à zéro.
     */
    @Transactional
    public RainbowLiveBinding setExecution(User user, Long id, boolean enabled) {
        return setExecutionMode(user, id, enabled ? BindingExecutionMode.ACTIVE : BindingExecutionMode.DISABLED);
    }

    /**
     * Mode d'exécution du lien (propriétaire) : {@code DISABLED}, {@code SIMULATION} (chaîne jouée et taguée, jamais d'ordre) ou
     * {@code ACTIVE} (mêmes règles que {@link #setExecution} : armement automatique du 1ᵉʳ ordre réel).
     */
    @Transactional
    public RainbowLiveBinding setExecutionMode(User user, Long id, BindingExecutionMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("Mode d'exécution obligatoire (DISABLED, SIMULATION, ACTIVE)");
        }
        RainbowLiveBinding binding = owned(user, id);
        boolean enabled = mode == BindingExecutionMode.ACTIVE;
        binding.applyExecutionMode(mode);
        if (enabled && binding.getFirstLiveApprovedAt() == null) {
            binding.setFirstLiveApprovedAt(clock.now());
            log.info("Binding {} user={} : 1er ordre réel armé automatiquement à la 1re activation", id, user.getId());
        }
        log.info("Binding {} user={} mode d'exécution={}", id, user.getId(), mode);
        return bindingRepository.save(binding);
    }

    /** Armement du 1ᵉʳ ordre réel par l'admin : remet la confirmation du propriétaire à zéro. */
    @Transactional
    public RainbowLiveBinding arm(Long id) {
        RainbowLiveBinding binding = bindingRepository.findById(id).filter(b -> !b.isArchived())
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.binding(id));
        binding.setFirstLiveApprovedAt(clock.now());
        binding.setFirstLiveConfirmedAt(null);
        log.info("Binding {} armé pour le 1er ordre réel", id);
        return bindingRepository.save(binding);
    }

    /**
     * Confirmation du propriétaire ; refusée (409) tant que l'exécution du lien n'est pas activée. Un lien activé avant
     * l'armement automatique (jamais armé) est armé ici.
     */
    @Transactional
    public RainbowLiveBinding confirmFirstLive(User user, Long id) {
        RainbowLiveBinding binding = owned(user, id);
        if (binding.getFirstLiveApprovedAt() == null) {
            if (!binding.isExecutionEnabled()) {
                throw new RainbowLiveBindingStateException("Activez d'abord l'exécution du lien avant de confirmer le 1er ordre réel");
            }
            binding.setFirstLiveApprovedAt(clock.now());
        }
        binding.setFirstLiveConfirmedAt(clock.now());
        log.info("Binding {} user={} : 1er ordre réel confirmé", id, user.getId());
        return bindingRepository.save(binding);
    }

    @Transactional
    public void delete(User user, Long id) {
        RainbowLiveBinding binding = owned(user, id);
        binding.setArchivedAt(clock.now());
        binding.applyExecutionMode(BindingExecutionMode.DISABLED);
        binding.setFirstLiveApprovedAt(null);
        binding.setFirstLiveConfirmedAt(null);
        bindingRepository.save(binding);
        log.info("Binding archivé id={} user={} actif={}", id, user.getId(), binding.getAssetSymbol());
    }

    @Transactional(readOnly = true)
    public List<RainbowLiveBinding> list(User user) {
        return bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user);
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

    /** Mémorise le Fee Test d'un devis sur le lien de l'utilisateur (404 si autre utilisateur). */
    @Transactional
    public RainbowLiveBinding recordFeeTest(User user, Long id, PathQuote quote, BigDecimal amount) {
        RainbowLiveBinding binding = owned(user, id);
        binding.recordFeeTest(quote, amount, clock.now());
        return bindingRepository.save(binding);
    }

    private RainbowLiveBinding owned(User user, Long id) {
        return bindingRepository.findById(id)
                .filter(b -> !b.isArchived())
                .filter(b -> b.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.binding(id));
    }

    /** Wallet de l'utilisateur ; autre utilisateur ou inconnu => {@link RainbowLiveBindingNotFoundException} (404). */
    public Wallet walletOf(User user, Long walletId) {
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

    /** Montant de base du lien : nul (celui des presets Rainbow) ou strictement positif et fini. */
    private static Double optionalBaseAmount(Double baseAmount) {
        if (baseAmount != null && (Double.isNaN(baseAmount) || Double.isInfinite(baseAmount) || baseAmount <= 0)) {
            throw new IllegalArgumentException("baseAmount doit être > 0 : " + baseAmount);
        }
        return baseAmount;
    }

    private static Double optionalMinBuy(Double minBuyUsd) {
        if (minBuyUsd != null && (Double.isNaN(minBuyUsd) || Double.isInfinite(minBuyUsd) || minBuyUsd <= 0)) {
            throw new IllegalArgumentException("minBuyUsd doit être > 0 : " + minBuyUsd);
        }
        return minBuyUsd;
    }

    /** Route imposée : nulle/vide => aucune ; sinon nœuds {@code A>B[>C]} en majuscules, dernier nœud = l'actif du lien. */
    private static String optionalRoute(String route, String asset) {
        if (route == null || route.isBlank()) {
            return null;
        }
        String r = route.trim();
        if (!ROUTE_FORMAT.matcher(r).matches() || !r.endsWith(">" + asset)) {
            throw new IllegalArgumentException("Route invalide : " + route + " (attendu : source>[intermédiaire>]" + asset + ")");
        }
        return r;
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
