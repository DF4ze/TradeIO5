package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.PathQuoteDto;
import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.Proposal;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Onboarding de l'utilisateur connecté : liaison automatique d'un wallet (liens inactifs) et montants de base proposés.
 * Lecture seule côté exchange, aucun ordre, aucune activation. Wallet d'un autre utilisateur => 404.
 */
@Slf4j
@RestController
@RequestMapping("/api/rainbow-live/onboarding")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveOnboardingController {

    public record LinkRequest(Long walletId) {
    }

    /** Bande proposée par défaut : neutre (aucune modulation de bande) tant que l'utilisateur n'a pas choisi la sienne. */
    public record BandSuggestion(double exposureMin, double exposureMax) {
    }

    /** Durée d'étalement du cash : défaut admin et bornes absolues, en jours. */
    public record AutonomyRange(int defaultDays, int minDays, int maxDays) {
    }

    public record ProposalsDto(List<Proposal> proposals, BandSuggestion suggestedBand, AutonomyRange autonomy) {
    }

    /** Devis d'un exchange (wallet) : {@code quote} nul et {@code error} renseigné si ce wallet est inutilisable ou illisible. */
    public record WalletPathQuote(Long walletId, String walletName, String exchange, PathQuoteDto quote, String error) {
    }

    private final RainbowLiveBindingService bindingService;
    private final BindingPathQuoteService pathQuoteService;
    private final RainbowLiveOnboardingService onboardingService;
    private final IAuthenticationFacade authenticationFacade;
    private final RainbowModulationControlService modulationControl;

    private int autonomyDefault() {
        return modulationControl.current().getAutonomyDays();
    }

    @PostMapping("/link")
    public List<LinkResult> link(@RequestBody LinkRequest request) {
        return onboardingService.link(authenticationFacade.getConnectedUser(), request.walletId());
    }

    @GetMapping("/proposals")
    public ProposalsDto proposals(@RequestParam Long walletId, @RequestParam(required = false) Integer days) {
        ModulationDefaults.Limit limit = ModulationDefaults.limit(ModulationDefaults.AUTONOMY_DAYS);
        return new ProposalsDto(onboardingService.proposals(authenticationFacade.getConnectedUser(), walletId, days),
                new BandSuggestion(ModulationDefaults.NEUTRAL_EXPOSURE_MIN, ModulationDefaults.NEUTRAL_EXPOSURE_MAX),
                new AutonomyRange(autonomyDefault(), limit.min().intValue(), limit.max().intValue()));
    }

    /**
     * Chemin d'achat de {@code asset} pour {@code amount} USD sur chacun des wallets actifs de l'utilisateur (route, frais,
     * Fee Test) : sert à choisir l'exchange avant de créer le lien. Lecture seule ; un wallet en échec n'empêche pas les autres.
     */
    @GetMapping("/path-quotes")
    public List<WalletPathQuote> pathQuotes(@RequestParam String asset, @RequestParam BigDecimal amount) {
        User user = authenticationFacade.getConnectedUser();
        return bindingService.wallets(user).stream().map(w -> walletQuote(user, w, asset, amount)).toList();
    }

    private WalletPathQuote walletQuote(User user, Wallet wallet, String asset, BigDecimal amount) {
        String exchange = wallet.getWebProviderCode() == null ? null : wallet.getWebProviderCode().name();
        try {
            return new WalletPathQuote(wallet.getId(), wallet.getName(), exchange,
                    PathQuoteDto.of(pathQuoteService.quoteWallet(user, wallet.getId(), asset, amount)), null);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Devis chemin wallet={} actif={} indisponible : {}", wallet.getId(), asset, e.toString());
            return new WalletPathQuote(wallet.getId(), wallet.getName(), exchange, null,
                    e.getMessage() == null ? "Devis indisponible" : e.getMessage());
        }
    }
}
