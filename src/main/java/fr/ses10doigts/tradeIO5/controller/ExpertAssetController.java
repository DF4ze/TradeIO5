package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.expert.ExpertDtos.AssetDto;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAccessGuard;
import fr.ses10doigts.tradeIO5.service.expert.ExpertAssetService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Actifs proposés par l'Espace Expert (mode EXPERT requis). Erreurs : {@link ExpertControllerAdvice}. */
@RestController
@RequestMapping("/api/expert/assets")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExpertAssetController {

    private final ExpertAccessGuard guard;
    private final ExpertAssetService service;

    @GetMapping
    public List<AssetDto> assets() {
        return service.list(guard.require());
    }
}
