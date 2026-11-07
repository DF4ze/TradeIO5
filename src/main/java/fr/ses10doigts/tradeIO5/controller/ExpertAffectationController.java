package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AssignRequest;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAffectationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Affectations de l'utilisateur (Trend + Rainbow UP + Rainbow DOWN par actif), mode EXPERT requis. L'activation reste sur {@code /api/rainbow-live}. */
@RestController
@RequestMapping("/api/expert/affectations")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertAffectationController {

    private final ExpertAccessGuard guard;
    private final ExpertAffectationService service;

    @GetMapping
    public List<AffectationDto> list() {
        return service.list(guard.require());
    }

    @GetMapping("/{asset}")
    public AffectationDto get(@PathVariable String asset) {
        return service.get(guard.require(), asset);
    }

    @PutMapping("/{asset}")
    public AffectationDto assign(@PathVariable String asset, @RequestBody AssignRequest request) {
        return service.assign(guard.require(), asset, request);
    }
}
