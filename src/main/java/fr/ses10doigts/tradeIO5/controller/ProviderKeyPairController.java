package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.CredentialStoredDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.KeyPairProviderDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.PendingKeyDto;
import fr.ses10doigts.tradeIO5.model.dto.admin.PromoteKeyRequest;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.connector.keypair.ProviderKeyPairService;
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

import java.util.List;

/**
 * Génération de paires de clés par l'utilisateur connecté (providers à clé publique enregistrée chez l'exchange, ex. Revolut X).
 * Jamais de clé privée dans les réponses. Sans {@code TRADEIO_MASTER_KEY} ⇒ 409 ; clé refusée ⇒ 400 ; exchange injoignable ⇒ 503.
 */
@RestController
@RequestMapping("/api/credentials/keypair")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ProviderKeyPairController {

    private final ProviderKeyPairService keyPairs;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping("/providers")
    public List<KeyPairProviderDto> providers() {
        return keyPairs.supported();
    }

    @PostMapping("/{provider}/{scope}")
    public ResponseEntity<PendingKeyDto> generate(@PathVariable String provider, @PathVariable String scope) {
        return ResponseEntity.status(HttpStatus.CREATED).body(keyPairs.generate(authenticationFacade.getConnectedUser(), provider, scope));
    }

    @GetMapping("/{provider}/{scope}")
    public ResponseEntity<PendingKeyDto> pending(@PathVariable String provider, @PathVariable String scope) {
        return keyPairs.pending(authenticationFacade.getConnectedUser(), provider, scope).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{provider}/{scope}/promote")
    public ResponseEntity<CredentialStoredDto> promote(@PathVariable String provider, @PathVariable String scope,
                                                       @RequestBody PromoteKeyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(keyPairs.promote(authenticationFacade.getConnectedUser(), provider, scope, request.apiKey()));
    }

    @DeleteMapping("/{provider}/{scope}")
    public ResponseEntity<Void> cancel(@PathVariable String provider, @PathVariable String scope) {
        keyPairs.cancel(authenticationFacade.getConnectedUser(), provider, scope);
        return ResponseEntity.noContent().build();
    }
}
