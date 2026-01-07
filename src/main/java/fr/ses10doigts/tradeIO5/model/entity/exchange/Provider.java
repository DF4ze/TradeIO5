package fr.ses10doigts.tradeIO5.model.entity.exchange;

import java.time.LocalDateTime;

import fr.ses10doigts.tradeIO5.model.enumerate.ProviderCode;
import jakarta.persistence.*;
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
@Table(name = "provider", uniqueConstraints = @UniqueConstraint(name = "uk_provider_code", columnNames = "code"))
@FilterDef(name = "enabledFilter", parameters = @ParamDef(name = "isEnabled", type = Boolean.class))
@Filter(name = "enabledFilter", condition = "enabled = :isEnabled")
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, unique = true)
    private ProviderCode code; // ex: "BINANCE", "KRAKEN"

    @Column(nullable = false, length = 100)
    private String name; // ex: "Binance Global", "Kraken Exchange"

    private String apiBaseUrl; // ex: "https://api.binance.com"

    private boolean enabled = true;

    private LocalDateTime createdAt;
}