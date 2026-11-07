package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationCreatedDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertSimulationDtos.SimulationRequest;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.simulation.ExpertSimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simulations de l'Espace Expert (mode EXPERT requis) : lancement asynchrone, progression puis résultat, libération.
 * Une simulation n'est visible que de son créateur. Erreurs : {@link ExpertControllerAdvice}.
 */
@RestController
@RequestMapping("/api/expert/simulations")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertSimulationController {

    private final ExpertAccessGuard guard;
    private final ExpertSimulationService service;

    @PostMapping
    public ResponseEntity<SimulationCreatedDto> startSimulation(@RequestBody SimulationRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startSimulation(guard.require(), request));
    }

    @GetMapping("/{id}")
    public SimulationDto get(@PathVariable String id) {
        return service.get(guard.require(), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(guard.require(), id);
        return ResponseEntity.noContent().build();
    }
}
