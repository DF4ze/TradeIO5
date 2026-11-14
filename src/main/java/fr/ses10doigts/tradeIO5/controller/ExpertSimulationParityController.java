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
 * Parité du rejeu avec la prod (simulateur complet, sans UI) : mode EXPERT requis, lecture seule. Tous les paramètres sont
 * facultatifs : sans {@code bindingId}/{@code asset}, tous les liens du wallet ({@code walletId}, défaut : wallet du lien le plus
 * prioritaire) sont rejoués avec cash et exposition communs ; avec l'un des deux, mono-lien. Période : du 1er run live de chaque
 * lien à aujourd'hui ; réglages = ceux de l'utilisateur (journal live).
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
                               @RequestParam(required = false) Long walletId,
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
        return service.parity(guard.require().user(), new FullReplayService.Request(bindingId, asset, walletId, from, to, cash0,
                qty0, baseAmount, bagPercent, exposureMin, exposureMax, power, bias, regulationMarginPct));
    }
}
