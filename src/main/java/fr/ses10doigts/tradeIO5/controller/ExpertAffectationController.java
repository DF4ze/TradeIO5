package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AffectationRequest;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAffectationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Affectations de l'utilisateur (Trend + Rainbow UP + Rainbow DOWN ; plusieurs par actif, nommées, clé = id), mode EXPERT requis. */
@RestController
@RequestMapping("/api/expert/affectations")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertAffectationController {

    private final ExpertAccessGuard guard;
    private final ExpertAffectationService service;

    @GetMapping
    public List<AffectationDto> list(@RequestParam(required = false) String asset) {
        return service.list(guard.require(), asset);
    }

    @GetMapping("/{id}")
    public AffectationDto get(@PathVariable Long id) {
        return service.get(guard.require(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AffectationDto create(@RequestBody AffectationRequest request) {
        return service.create(guard.require(), request);
    }

    @PutMapping("/{id}")
    public AffectationDto update(@PathVariable Long id, @RequestBody AffectationRequest request) {
        return service.update(guard.require(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(guard.require(), id);
    }
}
