package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveWalletDtos.LiveAssetDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveWalletQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Wallet réel des presets live de l'utilisateur connecté, tel qu'enregistré dans le dernier snapshot en base. Aucun
 * appel exchange à la lecture ; scopé à l'utilisateur.
 */
@RestController
@RequestMapping("/api/rainbow-live/live-wallet")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveWalletController {

    private final RainbowLiveWalletQueryService queryService;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public List<LiveAssetDto> liveAssets() {
        return queryService.liveAssets(authenticationFacade.getConnectedUser());
    }
}
