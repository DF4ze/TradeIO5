package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.WalletOptionDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Wallets de l'utilisateur connecté pour le formulaire de binding : {@code {id, name, exchange}} seulement (ni solde ni credential). */
@RestController
@RequestMapping("/api/rainbow-live/wallets")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveWalletOptionsController {

    private final RainbowLiveBindingService bindingService;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public List<WalletOptionDto> wallets() {
        return bindingService.wallets(authenticationFacade.getConnectedUser()).stream().map(WalletOptionDto::of).toList();
    }
}
