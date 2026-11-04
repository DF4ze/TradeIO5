package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminCredentialDto;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialEncryptionMigrator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final ReadCredentialEncryptionMigrator encryptionMigrator;

    public record EnabledRequest(boolean enabled) {
    }

    /** Saisie d'une clé pour un utilisateur (ou System). Les secrets ne sont jamais renvoyés ni journalisés ; absents de {@code toString}. */
    public record StoreRequest(Long userId, String provider, String scope, String apiKey, String secretKey, String passphrase,
                               Boolean enabled) {
        @Override
        public String toString() {
            return "StoreRequest[userId=" + userId + ", provider=" + provider + ", scope=" + scope + "]";
        }
    }

    @PostMapping
    public org.springframework.http.ResponseEntity<AdminCredentialDto> store(@RequestBody StoreRequest request) {
        return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(
                credentialService.storeAs(request, authenticationFacade.getConnectedUser().getUsername()));
    }

    @GetMapping
    public List<AdminCredentialDto> list() {
        return credentialService.list();
    }

    @PutMapping("/{id}/enabled")
    public AdminCredentialDto setEnabled(@PathVariable Long id, @RequestBody EnabledRequest request) {
        return credentialService.setEnabled(id, request.enabled(), authenticationFacade.getConnectedUser().getUsername());
    }

    /** Clé maître chargée ? écritures bloquées ? nombre de credentials encore en clair. Aucun secret. */
    @GetMapping("/encryption")
    public ReadCredentialEncryptionMigrator.Status encryptionStatus() {
        return encryptionMigrator.status();
    }

    /** Chiffre maintenant les secrets restés en clair (idempotent) ; sans clé maître : 0 et erreur au log. */
    @PostMapping("/encryption/migrate")
    public ReadCredentialEncryptionMigrator.Status migrate() {
        encryptionMigrator.migrate();
        return encryptionMigrator.status();
    }
}
