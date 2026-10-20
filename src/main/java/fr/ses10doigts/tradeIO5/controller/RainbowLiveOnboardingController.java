package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.LinkResult;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveOnboardingService.Proposal;
import fr.ses10doigts.tradeIO5.service.modulation.ModulationDefaults;
import fr.ses10doigts.tradeIO5.service.modulation.RainbowModulationControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Onboarding de l'utilisateur connecté : liaison automatique d'un wallet (liens inactifs) et montants de base proposés.
 * Lecture seule côté exchange, aucun ordre, aucune activation. Wallet d'un autre utilisateur => 404.
 */
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
}
