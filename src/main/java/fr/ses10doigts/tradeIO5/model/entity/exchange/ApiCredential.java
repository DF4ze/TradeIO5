package fr.ses10doigts.tradeIO5.model.entity.exchange;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "api_credentials",
		uniqueConstraints = @UniqueConstraint(name = "uk_credential_user_provider", columnNames = { "user_id", "web_provider_id" }))
@FilterDef(name = "enabledFilter", parameters = @ParamDef(name = "isEnabled", type = Boolean.class))
@Filter(name = "enabledFilter", condition = "enabled = :isEnabled")
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

    @Column(nullable = false)
    private String apiKey;

    @Column(nullable = false)
    private String secretKey;

    private boolean enabled = true;

    private LocalDateTime createdAt;
}