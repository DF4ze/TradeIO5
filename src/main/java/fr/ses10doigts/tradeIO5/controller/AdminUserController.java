package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/** Liste (id, nom) des comptes pour les sélecteurs « agir pour un utilisateur » de la page Admin, compte technique System compris. */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserRepository userRepository;

    public record AdminUserDto(Long id, String username) {
    }

    @GetMapping
    public List<AdminUserDto> list() {
        return userRepository.findAll().stream().sorted(Comparator.comparing(u -> u.getUsername().toLowerCase()))
                .map(u -> new AdminUserDto(u.getId(), u.getUsername())).toList();
    }
}
