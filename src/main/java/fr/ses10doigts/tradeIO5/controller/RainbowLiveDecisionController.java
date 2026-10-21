package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.portfolio.DecisionDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.portfolio.DecisionQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Décisions quotidiennes de l'utilisateur connecté (défaut : 30 derniers jours UTC). Lecture seule. */
@RestController
@RequestMapping("/api/rainbow-live/decisions")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveDecisionController {

    private static final int DEFAULT_DAYS = 30;

    private final DecisionQueryService service;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public List<DecisionDto> decisions(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) String assetSymbol,
                                       @RequestParam(required = false) Long walletId) {
        LocalDate end = to != null ? to : LocalDate.now(java.time.ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS);
        return service.decisions(authenticationFacade.getConnectedUser(), start, end, assetSymbol, walletId);
    }
}
