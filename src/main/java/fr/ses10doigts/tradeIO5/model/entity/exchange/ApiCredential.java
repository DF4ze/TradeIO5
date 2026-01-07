package fr.ses10doigts.tradeIO5.model.entity.exchange;

import java.time.LocalDateTime;

import fr.ses10doigts.tradeIO5.security.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "api_credentials",
		uniqueConstraints = @UniqueConstraint(name = "uk_credential_user_provider", columnNames = { "user_id", "provider_id" }))
@FilterDef(name = "enabledFilter", parameters = @ParamDef(name = "isEnabled", type = Boolean.class))
@Filter(name = "enabledFilter", condition = "enabled = :isEnabled")
public class ApiCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private User user;

	@ManyToOne(optional = false)
	private Provider provider;

    @Column(nullable = false)
    private String apiKey;

    @Column(nullable = false)
    private String secretKey;

    private boolean enabled = true;

    private LocalDateTime createdAt;
}