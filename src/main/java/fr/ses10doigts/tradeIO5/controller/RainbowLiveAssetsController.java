package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.market.eligibility.EligibleAssetsQueryService;
import fr.ses10doigts.tradeIO5.service.market.eligibility.EligibleAssetsQueryService.EligibleAsset;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Actifs éligibles chez les exchanges de l'utilisateur connecté (catalogue en base, aucun appel réseau). Erreurs mappées par {@link RainbowLiveControllerAdvice}. */
@RestController
@RequestMapping("/api/rainbow-live/assets")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveAssetsController {

    private final EligibleAssetsQueryService eligibleAssets;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping("/eligible")
    public List<EligibleAsset> eligible(@RequestParam(required = false) String provider, @RequestParam(required = false) String q,
                                        @RequestParam(required = false) Integer limit) {
        return eligibleAssets.list(authenticationFacade.getConnectedUser(), provider, q, limit);
    }
}
