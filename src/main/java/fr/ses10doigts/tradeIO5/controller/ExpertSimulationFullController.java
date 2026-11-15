package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullDefaultsDto;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.simulation.FullSimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Défauts du formulaire du simulateur complet (mode EXPERT requis, lecture seule, base seulement, aucune donnée d'un autre
 * utilisateur) : wallet et valeur réelle, liens, modulation effective et bornes admin. Le lancement passe par
 * {@code POST /api/expert/simulations} (type {@code FULL}).
 */
@RestController
@RequestMapping("/api/expert/simulations/full")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertSimulationFullController {

    private final ExpertAccessGuard guard;
    private final FullSimulationService service;

    @GetMapping("/defaults")
    public FullDefaultsDto defaults(@RequestParam(required = false) Long walletId) {
        return service.defaults(guard.require(), walletId);
    }
}
