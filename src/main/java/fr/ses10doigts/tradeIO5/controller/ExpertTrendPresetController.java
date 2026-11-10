package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.CreatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.DuplicatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.ImpactDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.PresetHeaderDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.TrendPresetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.TrendPresetRequest;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.TrendPresetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Presets Trend (SYSTEM + ceux de l'appelant), mode EXPERT requis. Droits et erreurs : {@code TrendPresetService}, {@link ExpertControllerAdvice}. */
@RestController
@RequestMapping("/api/expert/trend-presets")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertTrendPresetController {

    private final ExpertAccessGuard guard;
    private final TrendPresetService service;

    @GetMapping
    public List<PresetHeaderDto> list(@RequestParam String asset) {
        return service.list(guard.require(), asset);
    }

    @GetMapping("/{id}")
    public TrendPresetDto get(@PathVariable Long id) {
        return service.detail(guard.require(), id);
    }

    /** Impact d'une modification d'un preset SYSTEM (ADMIN) : comptes d'Affectations, sans identité. */
    @GetMapping("/{id}/impact")
    public ImpactDto impact(@PathVariable Long id) {
        return service.impact(guard.require(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TrendPresetDto create(@RequestBody CreatePresetRequest request) {
        return service.createPreset(guard.require(), request);
    }

    @PutMapping("/{id}")
    public TrendPresetDto update(@PathVariable Long id, @RequestBody TrendPresetRequest request) {
        return service.updatePreset(guard.require(), id, request);
    }

    @PostMapping("/{id}/save-as-system")
    @ResponseStatus(HttpStatus.CREATED)
    public TrendPresetDto saveAsSystem(@PathVariable Long id, @RequestBody TrendPresetRequest request) {
        return service.saveAsSystemPreset(guard.require(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(guard.require(), id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public TrendPresetDto duplicate(@PathVariable Long id, @RequestBody(required = false) DuplicatePresetRequest request) {
        return service.duplicatePreset(guard.require(), id, request);
    }
}
