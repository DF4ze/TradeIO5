package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.ssh.SshAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Création d'utilisateurs et accès VPS (tunnel SSH) — {@code ROLE_ADMIN} strict. Aucun rôle ni identité acceptés en
 * paramètre : le compte créé est toujours {@code ROLE_USER}, l'auteur est l'admin connecté.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserAccessController {

    private final SshAccessService service;
    private final IAuthenticationFacade authenticationFacade;

    public record CreateRequest(String username, boolean sshAccess) {
    }

    public record KeyRequest(String publicKey) {
    }

    public record EnabledRequest(boolean enabled) {
    }

    private String actor() {
        return authenticationFacade.getConnectedUser().getUsername();
    }

    /** Réponse unique : le mot de passe généré n'est jamais relisible ensuite. */
    @PostMapping
    public ResponseEntity<SshAccessService.Created> create(@RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createUser(actor(), request.username(), request.sshAccess()));
    }

    @GetMapping("/access")
    public List<SshAccessService.UserAccess> access() {
        return service.listAccess();
    }

    @PostMapping("/{id}/ssh-key")
    public SshAccessService.UserAccess registerKey(@PathVariable Long id, @RequestBody KeyRequest request) {
        return service.registerKey(actor(), id, request.publicKey());
    }

    @PostMapping("/{id}/ssh/sync")
    public SshAccessService.UserAccess sync(@PathVariable Long id) {
        return service.sync(actor(), id);
    }

    @PutMapping("/{id}/enabled")
    public SshAccessService.UserAccess setEnabled(@PathVariable Long id, @RequestBody EnabledRequest request) {
        return service.setEnabled(actor(), id, request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(actor(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/ssh/reconcile")
    public SshAccessService.Reconciliation reconcile() {
        return service.reconcile(actor());
    }

    /** Package client (scripts + tradeio.conf prérempli) en zip. */
    @GetMapping("/{id}/ssh/client-package")
    public ResponseEntity<byte[]> clientPackage(@PathVariable Long id) {
        byte[] body = service.clientPackage(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("tradeio-client-" + id + ".zip").build().toString())
                .body(body);
    }

    @GetMapping("/{id}/ssh/client-conf")
    public ResponseEntity<byte[]> clientConf(@PathVariable Long id) {
        byte[] body = service.clientConf(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("tradeio.conf").build().toString())
                .body(body);
    }
}
