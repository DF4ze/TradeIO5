package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.dca.bench.PathQuoteDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.BindingDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.CheckDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.CheckedBindingDto;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.BindingPathQuoteService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.UpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * Bindings du bench grandeur nature (preset live + wallet réel par actif) de l'utilisateur connecté. Scopé à
 * l'utilisateur : binding, preset ou wallet d'un autre utilisateur ou inexistant ⇒ 404. Erreurs mappées par
 * {@link RainbowLiveControllerAdvice}. Lecture seule côté exchange, aucun ordre.
 */
@RestController
@RequestMapping("/api/rainbow-live/bindings")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class RainbowLiveBindingController {

    private final RainbowLiveBindingService bindingService;
    private final BindingPathQuoteService pathQuoteService;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public List<BindingDto> list() {
        return bindingService.list(authenticationFacade.getConnectedUser()).stream().map(BindingDto::of).toList();
    }

    @GetMapping("/{id}")
    public BindingDto get(@PathVariable Long id) {
        return BindingDto.of(bindingService.get(authenticationFacade.getConnectedUser(), id));
    }

    @PostMapping
    public ResponseEntity<CheckedBindingDto> create(@RequestBody CreateRequest request) {
        User user = authenticationFacade.getConnectedUser();
        return ResponseEntity.status(HttpStatus.CREATED).body(CheckedBindingDto.of(bindingService.create(user, request)));
    }

    @PutMapping("/{id}")
    public CheckedBindingDto update(@PathVariable Long id, @RequestBody UpdateRequest request) {
        return CheckedBindingDto.of(bindingService.update(authenticationFacade.getConnectedUser(), id, request));
    }

    public record ExecutionRequest(boolean enabled) {
    }

    /** Interrupteur d'exécution du binding (défaut éteint). */
    @PutMapping("/{id}/execution")
    public BindingDto setExecution(@PathVariable Long id, @RequestBody ExecutionRequest request) {
        return BindingDto.of(bindingService.setExecution(authenticationFacade.getConnectedUser(), id, request.enabled()));
    }

    /** Confirmation du 1ᵉʳ ordre réel (après armement admin). */
    @PostMapping("/{id}/confirm-first-live")
    public BindingDto confirmFirstLive(@PathVariable Long id) {
        return BindingDto.of(bindingService.confirmFirstLive(authenticationFacade.getConnectedUser(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bindingService.delete(authenticationFacade.getConnectedUser(), id);
        return ResponseEntity.noContent().build();
    }

    /** Revérifie la disponibilité (appelle l'exchange en lecture seule). */
    @GetMapping("/{id}/check")
    public CheckDto check(@PathVariable Long id) {
        return CheckDto.of(bindingService.check(authenticationFacade.getConnectedUser(), id));
    }

    /**
     * Devis à la demande du chemin d'achat (frais réels du compte, carnet public, Fee Test) pour {@code amount} USD.
     * Lecture seule : un seul calcul, aucun ordre, aucune écriture.
     */
    @GetMapping("/{id}/path-quote")
    public PathQuoteDto pathQuote(@PathVariable Long id, @RequestParam BigDecimal amount,
                                  @RequestParam(required = false) String preferredQuote) {
        return PathQuoteDto.of(pathQuoteService.quote(authenticationFacade.getConnectedUser(), id, amount, preferredQuote));
    }
}
