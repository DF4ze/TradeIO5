package fr.ses10doigts.tradeIO5.service.dca.atr.binding;

import fr.ses10doigts.tradeIO5.model.dto.execution.FeeTestLevel;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathQuote;
import fr.ses10doigts.tradeIO5.model.dto.execution.PathStatus;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.PortfolioStatus;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowAtrConfig;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLiveBinding;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePreset;
import fr.ses10doigts.tradeIO5.repository.dca.bench.RainbowLiveBindingRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.PortfolioReading;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLivePresetConfigResolver;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RealPortfolioSource;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.Candidate;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.PresetOrigin;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
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
 *   <li>{@link #link} : pour chaque actif de {@link RainbowLiveDefaultPresets#ASSETS}, {@link BindingCheck} puis création
 *   d'un lien <b>inactif</b> sur le preset système de l'actif ; un actif non tradable ou illisible n'est pas lié, la raison
 *   est retournée. Idempotent : un lien existant n'est jamais modifié.</li>
 *   <li>{@link #proposals} : {@code baseAmount = cash alloué / (autonomyDays × multiplicateur moyen des zones)}, cash du
 *   wallet réparti à parts égales entre ses liens ; devis du chemin d'achat (minimum de l'exchange, Fee Test). Rien n'est
 *   enregistré : l'utilisateur applique via {@code PUT /bindings/{id}}.</li>
 * </ul>
 * Aucun ordre, aucune activation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RainbowLiveOnboardingService {

    private static final int AMOUNT_SCALE = 2;

    public enum LinkOutcome { LINKED, ALREADY_LINKED, NOT_LINKED }

    /** Résultat par actif : lien créé ou existant, ou raison de la non-liaison. */
    public record LinkResult(String assetSymbol, LinkOutcome outcome, Long bindingId, String presetName, String status,
                             String message) {
    }

    /**
     * Proposition de montant de base d'un lien. {@code proposedBaseAmount} nul si le cash est illisible ou nul.
     * {@code autonomyDays} = jours visés ; {@code feeTestLevel} / {@code warning} viennent du devis (nuls si indisponible).
     */
    public record Proposal(Long bindingId, String assetSymbol, Double currentBaseAmount, Double presetBaseAmount,
                           Double cashAllocated, Double averageMultiplier, int autonomyDays, Double proposedBaseAmount,
                           boolean belowMinimum, FeeTestLevel feeTestLevel, String warning) {
    }

    private final RainbowLiveBindingService bindingService;
    private final RainbowLiveBindingRepository bindingRepository;
    private final BindingCheck bindingCheck;
    private final BindingPathQuoteService pathQuoteService;
    private final RealPortfolioSource portfolioSource;
    private final RainbowLivePresetConfigResolver configResolver;
    private final RainbowModulationControlService modulationControl;

    @Transactional
    public List<LinkResult> link(User user, Long walletId) {
        Wallet wallet = bindingService.wallets(user).stream().filter(w -> w.getId().equals(walletId)).findFirst()
                .orElseThrow(() -> RainbowLiveBindingNotFoundException.wallet(walletId));
        List<LinkResult> results = new ArrayList<>();
        for (String asset : RainbowLiveDefaultPresets.ASSETS) {
            results.add(linkAsset(user, wallet, asset));
        }
        log.info("Onboarding user={} wallet={} : {}", user.getId(), walletId, results.stream()
                .map(r -> r.assetSymbol() + ":" + r.outcome()).toList());
        return results;
    }

    private LinkResult linkAsset(User user, Wallet wallet, String asset) {
        var existing = bindingRepository.findByUserAndAssetSymbolAndWallet_Id(user, asset, wallet.getId());
        if (existing.isPresent()) {
            RainbowLiveBinding b = existing.get();
            return new LinkResult(asset, LinkOutcome.ALREADY_LINKED, b.getId(), b.getPreset().getName(), null, null);
        }
        BindingCheckResult check = bindingCheck.check(wallet, asset);
        if (!check.isOk()) {
            return new LinkResult(asset, LinkOutcome.NOT_LINKED, null, null, check.status().name(), check.message());
        }
        Candidate preset = bindingService.candidates(user, wallet.getId(), asset).stream()
                .filter(c -> c.origin() == PresetOrigin.SYSTEM).findFirst().orElse(null);
        if (preset == null) {
            return new LinkResult(asset, LinkOutcome.NOT_LINKED, null, null, null, "Aucun preset système pour " + asset);
        }
        RainbowLiveBinding binding = bindingService.create(user, new RainbowLiveBindingService.CreateRequest(asset,
                preset.id(), wallet.getId(), null, null)).binding();
        return new LinkResult(asset, LinkOutcome.LINKED, binding.getId(), preset.name(), check.status().name(), null);
    }

    /** Proposition sur l'autonomie par défaut de l'admin ({@code autonomyDays}). */
    public List<Proposal> proposals(User user, Long walletId) {
        return proposals(user, walletId, null);
    }

    /**
     * @param autonomyDays jours sur lesquels l'utilisateur veut étaler le cash ; nul => défaut admin ; hors bornes absolues
     *                     ({@code ModulationDefaults} autonomyDays) => {@link IllegalArgumentException}
     */
    @Transactional
    public List<Proposal> proposals(User user, Long walletId, Integer autonomyDays) {
        int days = autonomyDays == null ? modulationControl.current().getAutonomyDays() : requireDays(autonomyDays);
        List<RainbowLiveBinding> bindings = bindingRepository.findByUserOrderByPriorityAscAssetSymbolAsc(user).stream()
                .filter(b -> b.getWallet().getId().equals(walletId)).toList();
        if (bindings.isEmpty()) {
            return List.of();
        }
        PortfolioReading reading = portfolioSource.read(bindings.getFirst());
        double share = reading.status() == PortfolioStatus.OK ? reading.cash() / bindings.size() : Double.NaN;
        return bindings.stream().map(b -> proposal(user, b, share, days)).toList();
    }

    private static int requireDays(int days) {
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS);
        if (!limit.contains(BigDecimal.valueOf(days))) {
            throw new IllegalArgumentException("Durée d'autonomie hors bornes : " + days + " jours (entre " + limit.min().toPlainString()
                    + " et " + limit.max().toPlainString() + ")");
        }
        return days;
    }

    private Proposal proposal(User user, RainbowLiveBinding binding, double cashShare, int days) {
        RainbowLivePreset preset = binding.getPreset();
        RainbowAtrConfig config = configResolver.config(preset);
        double mean = averageMultiplier(config);
        Double presetBase = config.getBaseAmount();
        if (Double.isNaN(cashShare) || cashShare <= 0 || mean <= 0) {
            String why = Double.isNaN(cashShare) ? "Soldes illisibles : proposition indisponible"
                    : "Aucun cash disponible sur ce wallet : proposition indisponible";
            return new Proposal(binding.getId(), binding.getAssetSymbol(), binding.getBaseAmount(), presetBase,
                    Double.isNaN(cashShare) ? null : cashShare, mean, days, null, false, null, why);
        }
        BigDecimal base = BigDecimal.valueOf(cashShare / (days * mean)).setScale(AMOUNT_SCALE, RoundingMode.DOWN);
        if (base.signum() <= 0) {
            return new Proposal(binding.getId(), binding.getAssetSymbol(), binding.getBaseAmount(), presetBase, cashShare,
                    mean, days, null, false, null, "Cash trop faible pour " + days + " jours d'autonomie");
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
                days, base.doubleValue(), below, level, warning);
    }

    /** Multiplicateur moyen des zones du jeu (Bear pour un Trend Mix) : moyenne de x2, x1 et x0,5. */
    public static double averageMultiplier(RainbowAtrConfig config) {
        return (config.getMultX2() + config.getMultX1() + config.getMultX0_5()) / 3.0;
    }
}
