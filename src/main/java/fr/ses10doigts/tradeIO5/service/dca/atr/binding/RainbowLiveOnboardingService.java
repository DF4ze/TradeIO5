package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.AssetAffectation;
import fr.ses10doigts.tradeIO5.model.entity.modulation.RainbowModulationControl;
import fr.ses10doigts.tradeIO5.model.entity.user.StartingProfile;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AssetRegistry;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.AffectationConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RealPortfolioSource;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.UserModulationService;
import fr.ses10doigts.tradeIO5.service.modulation.AssetBaseRateService;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Onboarding (spec V1 §6) : liaison automatique d'un wallet aux actifs du périmètre, puis montant de base proposé.
 * <ul>
 *   <li>{@link #link} : pour chaque actif demandé (à défaut {@link AssetRegistry#SEED_ASSETS}, périmètre par défaut tant que l'écran de choix
 *   d'actifs n'existe pas), {@link BindingCheck} puis création (éligibilité chez le provider du wallet comprise) d'un lien <b>inactif</b> sur le preset système de l'actif ; un actif non tradable ou illisible n'est pas lié, la raison
 *   est retournée. Idempotent : un lien existant n'est jamais modifié.</li>
 *   <li>{@link #proposals} : cash du wallet réparti à parts égales entre ses liens ; devis du chemin d'achat (minimum de
 *   l'exchange, Fee Test). Deux bases, la proposition reste <b>indicative</b> (rien ne force l'utilisateur) :
 *   <ul>
 *     <li><b>défaut = taux de profil</b> : {@code baseAmount = taux(profil de départ, actif) × cash alloué au lien}
 *     (table de calage, éditable en admin). Actif sans ligne propre (hors BTC / ETH) : repli sur la ligne BTC, drapeau
 *     {@code uncalibrated}. Profil absent ou {@code CUSTOM} : pas de table, repli sur l'autonomie du défaut admin ;</li>
 *     <li><b>autonomie</b>, si l'utilisateur saisit explicitement une durée : {@code cash alloué / (jours × multiplicateur
 *     moyen des zones)}.</li>
 *   </ul>
 *   Rien n'est enregistré : l'utilisateur applique via {@code PUT /bindings/{id}}.</li>
 * </ul>
 * Aucun ordre, aucune activation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveOnboardingService {

    private static final int AMOUNT_SCALE = 2;

    /** Mention affichée avec toute proposition issue d'un taux de profil : la cible n'est pas une garantie. */
    public static final String RISK_NOTE = "cible de risque calibrée sur l'historique, pas une garantie";
    public static final String UNCALIBRATED_NOTE = "niveau non calibré pour cet actif";

    /** Base d'une proposition : taux de profil (défaut) ou autonomie. */
    public enum Basis { PROFILE_RATE, AUTONOMY }

    public enum LinkOutcome { LINKED, ALREADY_LINKED, NOT_LINKED }

    /** Résultat par actif : lien créé ou existant, ou raison de la non-liaison. */
    public record LinkResult(String assetSymbol, LinkOutcome outcome, Long bindingId, String affectationName, String status,
                             String message) {
    }

    /**
     * Proposition de montant de base d'un lien. {@code proposedBaseAmount} nul si le cash est illisible ou nul.
     * {@code basis} dit d'où vient le montant ; {@code autonomyDays} = jours visés (nul en base {@link Basis#PROFILE_RATE}) ;
     * {@code profile} / {@code baseRate} / {@code uncalibrated} / {@code riskNote} ne servent qu'au taux de profil
     * ({@code uncalibrated} : repli sur la ligne BTC) ; {@code feeTestLevel} / {@code warning} viennent du devis (nuls si indisponible).
     */
    public record Proposal(Long bindingId, String assetSymbol, Double currentBaseAmount, Double presetBaseAmount,
                           Double cashAllocated, Double averageMultiplier, Integer autonomyDays, Double proposedBaseAmount,
                           boolean belowMinimum, FeeTestLevel feeTestLevel, String warning, Basis basis, StartingProfile profile,
                           Double baseRate, boolean uncalibrated, String riskNote) {
    }

    private final RainbowLiveBindingService bindingService;
    private final RainbowLiveBindingRepository bindingRepository;
    private final BindingCheck bindingCheck;
    private final BindingPathQuoteService pathQuoteService;
    private final RealPortfolioSource portfolioSource;
    private final AffectationConfigResolver configResolver;
    private final RainbowModulationControlService modulationControl;
    private final UserModulationService userModulation;
    private final AssetBaseRateService assetRates;

    @Transactional
    public List<LinkResult> link(User user, Long walletId) {
        return link(user, walletId, AssetRegistry.SEED_ASSETS);
    }

    /** Liaison d'une liste d'actifs ; un actif inéligible ou non tradable n'est pas lié, la raison est retournée. */
    @Transactional
    public List<LinkResult> link(User user, Long walletId, List<String> assets) {
        Wallet wallet = bindingService.wallets(user).stream().filter(w -> w.getId().equals(walletId)).findFirst()
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.wallet(walletId));
        List<LinkResult> results = new ArrayList<>();
        for (String asset : assets) {
            results.add(linkAsset(user, wallet, asset));
        }
        log.info("Onboarding user={} wallet={} : {}", user.getId(), walletId, results.stream()
                .map(r -> r.assetSymbol() + ":" + r.outcome()).toList());
        return results;
    }

    private LinkResult linkAsset(User user, Wallet wallet, String asset) {
        var existing = bindingRepository.findByUserAndAssetSymbolAndWallet_Id(user, asset, wallet.getId()).filter(b -> !b.isArchived());
        if (existing.isPresent()) {
            RainbowLiveBinding b = existing.get();
            return new LinkResult(asset, LinkOutcome.ALREADY_LINKED, b.getId(), b.getAffectation().label(), null, null);
        }
        BindingCheckResult check = bindingCheck.check(wallet, asset);
        if (!check.isOk()) {
            return new LinkResult(asset, LinkOutcome.NOT_LINKED, null, null, check.status().name(), check.message());
        }
        RainbowLiveBinding binding;
        try {
            binding = bindingService.create(user, new RainbowLiveBindingService.CreateRequest(asset, wallet.getId(), null, null)).binding();
        } catch (IllegalArgumentException e) { // inéligible chez le provider du wallet (raison lisible)
            return new LinkResult(asset, LinkOutcome.NOT_LINKED, null, null, "INELIGIBLE", e.getMessage());
        }
        return new LinkResult(asset, LinkOutcome.LINKED, binding.getId(), binding.getAffectation().label(), check.status().name(), null);
    }

    /** Proposition au taux du profil de l'utilisateur (défaut) ; repli sur l'autonomie du défaut admin si le profil n'a pas de table. */
    public List<Proposal> proposals(User user, Long walletId) {
        return proposals(user, walletId, null);
    }

    /**
     * @param autonomyDays jours saisis explicitement par l'utilisateur : proposition par autonomie ; nul => taux du profil (défaut),
     *                     ou autonomie du défaut admin si le profil est absent / {@code CUSTOM} ; hors bornes absolues
     *                     ({@code ModulationDefaults} autonomyDays) => {@link IllegalArgumentException}
     */
    @Transactional
    public List<Proposal> proposals(User user, Long walletId, Integer autonomyDays) {
        RainbowModulationControl control = modulationControl.current();
        int days = autonomyDays == null ? control.getAutonomyDays() : requireDays(autonomyDays);
        StartingProfile profile = autonomyDays == null ? userModulation.profileOf(user) : null;
        boolean byProfile = profile != null && profile.selectable();
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream()
                .filter(b -> b.getWallet().getId().equals(walletId)).toList();
        if (bindings.isEmpty()) {
            return List.of();
        }
        PortfolioReading reading = portfolioSource.read(bindings.getFirst());
        double share = reading.status() == PortfolioStatus.OK ? reading.cash() / bindings.size() : Double.NaN;
        return bindings.stream().map(b -> proposal(user, b, share, days, byProfile ? profile : null)).toList();
    }

    private static int requireDays(int days) {
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS);
        if (!limit.contains(BigDecimal.valueOf(days))) {
            throw new IllegalArgumentException("Durée d'autonomie hors bornes : " + days + " jours (entre " + limit.min().toPlainString()
                    + " et " + limit.max().toPlainString() + ")");
        }
        return days;
    }

    /** @param profile profil dont on lit le taux ({@link AssetBaseRateService}) ; {@code null} => base par autonomie */
    private Proposal proposal(User user, RainbowLiveBinding binding, double cashShare, int days, StartingProfile profile) {
        AssetAffectation preset = binding.getAffectation();
        RainbowAtrConfig config = configResolver.config(preset);
        double mean = averageMultiplier(config);
        Double presetBase = config.getBaseAmount();
        AssetBaseRateService.Rate rate = profile == null ? null : assetRates.resolve(profile, binding.getAssetSymbol());
        Basis basis = rate == null ? Basis.AUTONOMY : Basis.PROFILE_RATE;
        Integer shownDays = rate == null ? days : null;
        Double shownRate = rate == null ? null : rate.value().doubleValue();
        boolean uncalibrated = rate != null && !rate.calibrated();
        String note = rate == null ? null : RISK_NOTE;
        StartingProfile shownProfile = rate == null ? null : profile;
        if (Double.isNaN(cashShare) || cashShare <= 0 || (rate == null && mean <= 0)) {
            String why = Double.isNaN(cashShare) ? "Soldes illisibles : proposition indisponible"
                    : "Aucun cash disponible sur ce wallet : proposition indisponible";
            return new Proposal(binding.getId(), binding.getAssetSymbol(), binding.getBaseAmount(), presetBase,
                    Double.isNaN(cashShare) ? null : cashShare, mean, shownDays, null, false, null, why, basis, shownProfile,
                    shownRate, uncalibrated, note);
        }
        BigDecimal base = rate == null ? referenceBaseAmount(cashShare, days, mean) : profileBaseAmount(cashShare, rate.value());
        if (base.signum() <= 0) {
            return new Proposal(binding.getId(), binding.getAssetSymbol(), binding.getBaseAmount(), presetBase, cashShare,
                    mean, shownDays, null, false, null, rate == null ? "Cash trop faible pour " + days + " jours d'autonomie"
                    : "Cash trop faible pour le taux du profil", basis, shownProfile, shownRate, uncalibrated, note);
        }
        FeeTestLevel level = null;
        boolean below = false;
        String warning = null;
        try {
            BindingPathQuoteService.Result result = pathQuoteService.quote(user, binding.getId(), base);
            PathQuote quote = result.quote();
            level = quote.feeTestLevel();
            below = quote.status() == PathStatus.BELOW_MIN;
            warning = result.warning();
        } catch (RuntimeException e) {
            warning = "Devis du chemin d'achat indisponible : minimum et Fee Test non vérifiés";
            log.warn("Onboarding : devis indisponible binding={} : {}", binding.getId(), e.getMessage());
        }
        return new Proposal(binding.getId(), binding.getAssetSymbol(), binding.getBaseAmount(), presetBase, cashShare, mean,
                shownDays, base.doubleValue(), below, level, warning, basis, shownProfile, shownRate, uncalibrated, note);
    }

    /** Montant de base au taux de profil : {@code taux × cash alloué au lien}, arrondi comme l'autonomie (échelle 2, vers le bas). */
    public static BigDecimal profileBaseAmount(double cashShare, BigDecimal baseRate) {
        return BigDecimal.valueOf(cashShare).multiply(baseRate).setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /** Montant de base proposé : {@code cash alloué / (autonomyDays × multiplicateur moyen)}, arrondi vers le bas ; réutilisé par l'optimiseur (budget de référence). */
    public static BigDecimal referenceBaseAmount(double cashShare, int days, double meanMultiplier) {
        return BigDecimal.valueOf(cashShare / (days * meanMultiplier)).setScale(AMOUNT_SCALE, RoundingMode.DOWN);
    }

    /** Multiplicateur moyen des zones du jeu (Bear pour un Trend Mix) : moyenne de x2, x1 et x0,5. */
    public static double averageMultiplier(RainbowAtrConfig config) {
        return (config.getMultX2() + config.getMultX1() + config.getMultX0_5()) / 3.0;
    }
}
