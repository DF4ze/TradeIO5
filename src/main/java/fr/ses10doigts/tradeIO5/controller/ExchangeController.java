package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.entity.exchange.CredentialScope;
import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.connector.credential.ExchangeKeysService;
import fr.ses10doigts.tradeIO5.service.connector.credential.ExchangeKeysService.ExchangeView;
import fr.ses10doigts.tradeIO5.service.connector.credential.ExchangeKeysService.SupportedExchange;
import fr.ses10doigts.tradeIO5.service.connector.credential.ExchangeKeysService.TestResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Page « Exchanges » de l'utilisateur connecté : état de ses clés lecture/écriture (aucun secret en retour), test de la clé de
 * lecture, suppression d'une clé ou de l'exchange. La saisie passe par {@code /api/credentials/read} et {@code /api/credentials/trade}.
 */
@RestController
@RequestMapping("/api/exchanges")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class ExchangeController {

    /** {@code serverIp} : adresse publique du serveur (propriété {@code tradeio.server.public-ip}), à autoriser dans la restriction d'IP des clés ; nulle si non configurée. */
    public record ExchangesDto(List<ExchangeView> exchanges, List<SupportedExchange> supported, String serverIp) {
    }

    private final ExchangeKeysService exchangeService;
    private final IAuthenticationFacade authenticationFacade;

    @org.springframework.beans.factory.annotation.Value("${tradeio.server.public-ip:}")
    private String serverIp;

    @GetMapping
    public ExchangesDto list() {
        return new ExchangesDto(exchangeService.list(authenticationFacade.getConnectedUser()), exchangeService.supported(),
                serverIp == null || serverIp.isBlank() ? null : serverIp.strip());
    }

    @PostMapping("/{provider}/test")
    public TestResult test(@PathVariable WebProviderCode provider) {
        return exchangeService.testRead(authenticationFacade.getConnectedUser(), provider);
    }

    @DeleteMapping("/{provider}/keys/{scope}")
    public ResponseEntity<Void> deleteKey(@PathVariable WebProviderCode provider, @PathVariable CredentialScope scope) {
        exchangeService.deleteKey(authenticationFacade.getConnectedUser(), provider, scope);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> deleteExchange(@PathVariable WebProviderCode provider) {
        exchangeService.deleteExchange(authenticationFacade.getConnectedUser(), provider);
        return ResponseEntity.noContent().build();
    }
}
