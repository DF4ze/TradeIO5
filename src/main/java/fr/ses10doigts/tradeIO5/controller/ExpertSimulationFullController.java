package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.FullDefaultsDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.FullSimulationDtos.PolicyCompareRequest;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.simulation.FullSimulationService;
import fr.ses10doigts.tradeIO5.service.expert.simulation.PolicyCompareService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Défauts du formulaire du simulateur complet (mode EXPERT requis, lecture seule, base seulement, aucune donnée d'un autre
 * utilisateur) : wallet et valeur réelle, liens, modulation effective et bornes admin. Le lancement passe par
 * {@code POST /api/expert/simulations} (type {@code FULL}). {@code POST /policy-compare} : comparaison synchrone de politiques de
 * modulation (CONSTANT + N) sur le même wallet / plage / capital.
 */
@RestController
@RequestMapping("/api/expert/simulations/full")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertSimulationFullController {

    private final ExpertAccessGuard guard;
    private final FullSimulationService service;
    private final PolicyCompareService compareService;

    @GetMapping("/defaults")
    public FullDefaultsDto defaults(@RequestParam(required = false) Long walletId) {
        return service.defaults(guard.require(), walletId);
    }

    @PostMapping("/policy-compare")
    public PolicyCompareDto policyCompare(@RequestBody PolicyCompareRequest request) {
        return compareService.compare(guard.require(), request);
    }
}
