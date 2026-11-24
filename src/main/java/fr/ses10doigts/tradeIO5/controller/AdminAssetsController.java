package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService.BlocklistEntry;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService.Overview;
import fr.ses10doigts.tradeIO5.service.admin.AdminAssetsService.SettingsView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Onglet admin « Actifs » (actifs dynamiques, lot Admin), réservé ROLE_ADMIN : vue par actif, liste noire (ajout / retrait), seuil de volume 24 h.
 * Chaque écriture est journalisée ({@link AdminAssetsService}). Le recalcul d'une proposition est sur {@code /api/admin/new-asset-proposals/recompute}.
 */
@RestController
@RequestMapping("/api/admin/assets")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAssetsController {

    /** Corps de l'ajout en liste noire : symbole nu (normalisé côté serveur) et motif libre facultatif. */
    public record BlocklistRequest(String asset, String reason) {
    }

    /** Corps de la modification du seuil : volume spot 24 h minimum en USD (&gt;= 0). */
    public record MinVolumeRequest(BigDecimal minVolumeUsd) {
    }

    private final AdminAssetsService service;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public Overview overview() {
        return service.overview();
    }

    @GetMapping("/blocklist")
    public List<BlocklistEntry> blocklist() {
        return service.blocklist();
    }

    @PostMapping("/blocklist")
    public ResponseEntity<BlocklistEntry> addToBlocklist(@RequestBody BlocklistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addToBlocklist(request == null ? null : request.asset(),
                request == null ? null : request.reason(), actor()));
    }

    @DeleteMapping("/blocklist/{asset}")
    public ResponseEntity<Void> removeFromBlocklist(@PathVariable String asset) {
        service.removeFromBlocklist(asset, actor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public SettingsView settings() {
        return service.settings();
    }

    @PutMapping("/settings")
    public SettingsView updateSettings(@RequestBody MinVolumeRequest request) {
        return service.updateMinVolume(request == null ? null : request.minVolumeUsd(), actor());
    }

    private String actor() {
        return authenticationFacade.getConnectedUser().getUsername();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
