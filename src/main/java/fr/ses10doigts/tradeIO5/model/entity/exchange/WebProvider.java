package fr.ses10doigts.tradeIO5.model.entity.exchange;

import fr.ses10doigts.tradeIO5.model.enumerate.WebProviderCode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "provider", uniqueConstraints = @UniqueConstraint(name = "uk_provider_code", columnNames = "code"))
public class WebProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, unique = true)
    private WebProviderCode code; // ex: "BINANCE", "KRAKEN"

    @Column(nullable = false, length = 100)
    private String name; // ex: "Binance Global", "Kraken Exchange"

    private String apiBaseUrl; // ex: "https://api.binance.com"

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}