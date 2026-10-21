package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.CredentialStoredDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.OwnReadCredentialRequest;
import fr.ses10doigts.tradeIO5.model.dto.admin.OwnTradeCredentialRequest;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminCredentialService;
import fr.ses10doigts.tradeIO5.service.connector.credential.ReadCredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saisie par le propriétaire de ses propres clés TRADE (ordres) et READ (soldes), chiffrées au repos. L'utilisateur est toujours celui qui est connecté :
 * aucun identifiant d'utilisateur n'est lu dans le corps. Réponse sans secret ; sans {@code TRADEIO_MASTER_KEY} ⇒ 409.
 */
@RestController
@RequestMapping("/api/credentials")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class OwnCredentialController {

    private final AdminCredentialService credentialService;
    private final ReadCredentialService readCredentialService;
    private final IAuthenticationFacade authenticationFacade;

    @PostMapping("/trade")
    public ResponseEntity<CredentialStoredDto> storeTrade(@RequestBody OwnTradeCredentialRequest request) {
        User user = authenticationFacade.getConnectedUser();
        ApiCredential saved = credentialService.store(user, user.getUsername(), request.provider(), request.apiKey(),
                request.secretKey(), request.passphrase(), request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CredentialStoredDto(saved.getId(),
                request.provider().name(), saved.getScope().name(), saved.isEnabled()));
    }

    @PostMapping("/read")
    public ResponseEntity<CredentialStoredDto> storeRead(@RequestBody OwnReadCredentialRequest request) {
        User user = authenticationFacade.getConnectedUser();
        ApiCredential saved = readCredentialService.store(user, request.provider(), request.apiKey(), request.secretKey(),
                request.passphrase(), request.enabled() == null || request.enabled());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CredentialStoredDto(saved.getId(),
                request.provider().name(), saved.getScope().name(), saved.isEnabled()));
    }
}
