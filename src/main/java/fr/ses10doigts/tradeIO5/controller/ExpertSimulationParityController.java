package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.simulation.FullReplayService;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ParityReport;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Parité du rejeu mono-actif avec la prod (lot 1 du simulateur complet, sans UI) : mode EXPERT requis, lecture seule.
 * Tous les paramètres sont facultatifs (défauts : lien BTC, du 1er run live à aujourd'hui, réglages du lien).
 */
@RestController
@RequestMapping("/api/expert/simulations/parity")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertSimulationParityController {

    private final ExpertAccessGuard guard;
    private final FullReplayService service;

    @GetMapping
    public ParityReport parity(@RequestParam(required = false) Long bindingId,
                               @RequestParam(required = false) String asset,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) Double cash0,
                               @RequestParam(required = false) Double qty0,
                               @RequestParam(required = false) Double baseAmount,
                               @RequestParam(required = false) Double bagPercent,
                               @RequestParam(required = false) Double exposureMin,
                               @RequestParam(required = false) Double exposureMax,
                               @RequestParam(required = false) Double power,
                               @RequestParam(required = false) Double bias,
                               @RequestParam(required = false) Double regulationMarginPct) {
        return service.parity(guard.require().user(), new FullReplayService.Request(bindingId, asset, from, to, cash0, qty0,
                baseAmount, bagPercent, exposureMin, exposureMax, power, bias, regulationMarginPct));
    }
}
