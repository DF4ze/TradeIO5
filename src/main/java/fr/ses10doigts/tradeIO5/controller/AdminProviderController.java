package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminProviderDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Déclaration des providers (nom, URL de base, activation) depuis la page Admin. Aucun secret. */
@RestController
@RequestMapping("/api/admin/providers")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminProviderController {

    private final AdminProviderService service;
    private final IAuthenticationFacade authenticationFacade;

    public record CreateRequest(String code, String name, String apiBaseUrl, Boolean enabled) {
    }

    public record UpdateRequest(String name, String apiBaseUrl, Boolean enabled) {
    }

    @GetMapping
    public AdminProviderDto.Listing list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<AdminProviderDto> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request.code(), request.name(),
                request.apiBaseUrl(), request.enabled(), authenticationFacade.getConnectedUser().getUsername()));
    }

    @PutMapping("/{id}")
    public AdminProviderDto update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return service.update(id, request.name(), request.apiBaseUrl(), request.enabled(),
                authenticationFacade.getConnectedUser().getUsername());
    }
}
