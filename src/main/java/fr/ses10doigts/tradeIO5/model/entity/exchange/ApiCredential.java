package fr.ses10doigts.tradeIO5.model.entity.exchange;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "api_credentials",
		uniqueConstraints = @UniqueConstraint(name = "uk_credential_user_provider_scope",
				columnNames = { "user_id", "web_provider_id", "scope" }))
public class ApiCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User user;

	@ManyToOne(optional = false)
    @JoinColumn(
            name = "web_provider_id",
            nullable = false
    )
	private WebProvider webProvider;

    /** Portée de la clé (défaut {@code READ}) ; unique par (utilisateur, provider, portée). */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CredentialScope scope = CredentialScope.READ;

    /** Clé en clair pour {@code READ} ; valeur chiffrée AES-GCM ({@code enc:v1:...}) pour {@code TRADE}. */
    @ToString.Exclude
    @Column(nullable = false, length = 512)
    private String apiKey;

    @ToString.Exclude
    @Column(length = 512)
    private String secretKey;

    /** Requise par certains exchanges (OKX) ; nulle sinon. */
    @ToString.Exclude
    @Column(length = 512)
    private String passphrase;

    private boolean enabled = true;

    private LocalDateTime createdAt;
}