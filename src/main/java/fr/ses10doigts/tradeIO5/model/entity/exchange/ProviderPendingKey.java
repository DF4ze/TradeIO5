package fr.ses10doigts.tradeIO5.model.entity.exchange;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * Paire de clés asymétriques générée par le serveur pour un exchange qui exige une clé publique enregistrée de son côté, en attente de
 * la clé API que l'utilisateur obtiendra en retour. Une seule paire en attente par (utilisateur, provider, portée) : en générer une autre
 * remplace la précédente. La clé privée est chiffrée (AES-GCM, {@code SecretCipher}) ; la clé publique n'est pas secrète. À la promotion
 * (clé API saisie et vérifiée) la privée passe dans l'{@link ApiCredential} et cette ligne est supprimée ; sans promotion elle expire.
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "provider_pending_key",
        uniqueConstraints = @UniqueConstraint(name = "uk_pending_key_user_provider_scope",
                columnNames = {"user_id", "provider_code", "scope"}))
public class ProviderPendingKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User user;

    /** Code du provider ({@code WebProviderCode#name()}), en texte : ajouter un provider ne change pas le schéma. */
    @Column(name = "provider_code", nullable = false, length = 32)
    private String providerCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CredentialScope scope;

    /** Algorithme JCA de la paire (ex. {@code Ed25519}). */
    @Column(nullable = false, length = 32)
    private String algorithm;

    /** Clé privée PKCS#8 en PEM, chiffrée ({@code enc:v1:...}) ; jamais renvoyée, jamais loguée. */
    @ToString.Exclude
    @Column(name = "private_key_enc", nullable = false, length = 8192)
    private String privateKeyEnc;

    /** Clé publique X.509 (SPKI) en PEM : à enregistrer chez l'exchange. */
    @Column(name = "public_key_pem", nullable = false, length = 2048)
    private String publicKeyPem;

    /** Empreinte SHA-256 (hexadécimale) de la clé publique : identifie la paire sans la montrer en entier. */
    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
