package fr.ses10doigts.tradeIO5.security.model;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "users",
uniqueConstraints = {
	@UniqueConstraint(columnNames = "username"),
	@UniqueConstraint(columnNames = "email")
})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 20)
    private String username;

    @NotBlank
    @Size(max = 50)
    @Email
    private String email;

    @NotBlank
    @Size(max = 120)
    private String password;

	@ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
			joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

	private boolean enabled;

    /**
     * Dernière connexion détectée (Palier 3, étape 5) — mise à jour à la fois au login explicite
     * (AuthController#authenticateUserForm) et, avec throttle, à chaque requête authentifiée revalidée
     * par AuthTokenFilter. Nullable : un utilisateur jamais connecté depuis l'ajout de ce champ (ou
     * créé avant) a cette valeur à null, pas une date arbitraire.
     */
    private Instant lastLogin;

    /**
     * Date d'archivage (Palier 3, étape 6) si cet utilisateur a été retiré de la mémoire active pour
     * inactivité prolongée (2 mois, cf. ArchivalService). Null tant que jamais archivé, ou après
     * restauration à la reconnexion.
     */
    private Instant archivedAt;
}
