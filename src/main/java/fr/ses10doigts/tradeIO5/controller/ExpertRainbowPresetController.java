package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.CreatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.DuplicatePresetRequest;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.PresetHeaderDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.RainbowPresetDto;
import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.RainbowPresetRequest;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.RainbowPresetService;
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

/** Presets Rainbow (SYSTEM + ceux de l'appelant), mode EXPERT requis. Droits et erreurs : {@code RainbowPresetService}, {@link ExpertControllerAdvice}. */
@RestController
@RequestMapping("/api/expert/rainbow-presets")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertRainbowPresetController {

    private final ExpertAccessGuard guard;
    private final RainbowPresetService service;

    @GetMapping
    public List<PresetHeaderDto> list(@RequestParam String asset) {
        return service.list(guard.require(), asset);
    }

    @GetMapping("/{id}")
    public RainbowPresetDto get(@PathVariable Long id) {
        return service.detail(guard.require(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RainbowPresetDto create(@RequestBody CreatePresetRequest request) {
        return service.createPreset(guard.require(), request);
    }

    @PutMapping("/{id}")
    public RainbowPresetDto update(@PathVariable Long id, @RequestBody RainbowPresetRequest request) {
        return service.updatePreset(guard.require(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(guard.require(), id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public RainbowPresetDto duplicate(@PathVariable Long id, @RequestBody(required = false) DuplicatePresetRequest request) {
        return service.duplicatePreset(guard.require(), id, request);
    }
}
