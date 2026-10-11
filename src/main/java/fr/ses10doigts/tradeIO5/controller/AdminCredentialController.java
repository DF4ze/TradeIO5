package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminCredentialDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Supervision admin des credentials de tous les utilisateurs : liste masquée, (dés)activation. Aucun secret. */
@RestController
@RequestMapping("/api/admin/credentials")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminCredentialController {

    private final AdminCredentialService credentialService;
    private final IAuthenticationFacade authenticationFacade;

    public record EnabledRequest(boolean enabled) {
    }

    @GetMapping
    public List<AdminCredentialDto> list() {
        return credentialService.list();
    }

    @PutMapping("/{id}/enabled")
    public AdminCredentialDto setEnabled(@PathVariable Long id, @RequestBody EnabledRequest request) {
        return credentialService.setEnabled(id, request.enabled(), authenticationFacade.getConnectedUser().getUsername());
    }
}
