package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService;
import fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService.ProposalView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Propositions d'accueil d'un nouvel actif (D1b, lot 2) : liste, Valider, Refuser. Réservé ROLE_ADMIN (même contrôle que les paramètres de
 * modulation). Aucune application automatique : sans appel ici, rien ne change.
 */
@RestController
@RequestMapping("/api/admin/new-asset-proposals")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminNewAssetProposalController {

    private final NewAssetProposalAdminService service;
    private final IAuthenticationFacade authenticationFacade;

    /** Propositions à valider ({@code PROPOSE}) ; {@code all=true} : historique complet. */
    @GetMapping
    public List<ProposalView> list(@RequestParam(defaultValue = "false") boolean all) {
        return service.list(all);
    }

    @PostMapping("/{id}/validate")
    public ProposalView validate(@PathVariable Long id) {
        return service.validate(id, actor());
    }

    @PostMapping("/{id}/refuse")
    public ProposalView refuse(@PathVariable Long id) {
        return service.refuse(id, actor());
    }

    private String actor() {
        return authenticationFacade.getConnectedUser().getUsername();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
