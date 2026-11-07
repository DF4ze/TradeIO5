package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminBindingDto;
import fr.ses10doigts.tradeIO5.service.admin.AdminResourceNotFoundException;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.BindingDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.CheckDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.CheckedBindingDto;
import fr.ses10doigts.tradeIO5.model.dto.dca.bench.RainbowLiveBindingDtos.WalletOptionDto;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.CreateRequest;
import fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingService.UpdateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import fr.ses10doigts.tradeIO5.service.admin.AdminBindingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Supervision admin des bindings de tous les utilisateurs (lecture seule ; l'armement reste {@code /api/admin/execution/bindings/{id}/arm}). */
@Slf4j
@RestController
@RequestMapping("/api/admin/bindings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminBindingController {

    private final AdminBindingQueryService bindingQueryService;
    private final RainbowLiveBindingService bindingService;
    private final UserRepository userRepository;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public List<AdminBindingDto> list() {
        return bindingQueryService.list();
    }

    // ---- Agir au nom d'un utilisateur (l'admin l'aide à configurer ses liens ; mêmes règles que côté propriétaire) ----

    @GetMapping("/users/{userId}/wallets")
    public List<WalletOptionDto> wallets(@PathVariable Long userId) {
        return bindingService.wallets(target(userId)).stream().map(WalletOptionDto::of).toList();
    }

    @PostMapping("/users/{userId}")
    public ResponseEntity<CheckedBindingDto> create(@PathVariable Long userId, @RequestBody CreateRequest request) {
        User user = target(userId);
        log.warn("Admin {} crée un binding pour l'utilisateur {} (asset={})", actor(), user.getUsername(), request.assetSymbol());
        return ResponseEntity.status(HttpStatus.CREATED).body(CheckedBindingDto.of(bindingService.create(user, request)));
    }

    @PutMapping("/users/{userId}/{id}")
    public CheckedBindingDto update(@PathVariable Long userId, @PathVariable Long id, @RequestBody UpdateRequest request) {
        User user = target(userId);
        log.warn("Admin {} modifie le binding {} de l'utilisateur {}", actor(), id, user.getUsername());
        return CheckedBindingDto.of(bindingService.update(user, id, request));
    }

    @PutMapping("/users/{userId}/{id}/execution")
    public BindingDto setExecution(@PathVariable Long userId, @PathVariable Long id,
                                   @RequestBody RainbowLiveBindingController.ExecutionRequest request) {
        User user = target(userId);
        log.warn("Admin {} change l'exécution du binding {} de l'utilisateur {}", actor(), id, user.getUsername());
        return BindingDto.of(bindingService.setExecutionMode(user, id, request.resolved()));
    }

    @GetMapping("/users/{userId}/{id}/check")
    public CheckDto check(@PathVariable Long userId, @PathVariable Long id) {
        return CheckDto.of(bindingService.check(target(userId), id));
    }

    private User target(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new AdminResourceNotFoundException("Utilisateur introuvable : " + userId));
    }

    private String actor() {
        return authenticationFacade.getConnectedUser().getUsername();
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> bindingNotFound(
            fr.ses10doigts.tradeIO5.service.dca.atr.binding.RainbowLiveBindingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
