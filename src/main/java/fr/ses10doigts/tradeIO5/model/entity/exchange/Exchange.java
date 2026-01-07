package fr.ses10doigts.tradeIO5.model.entity.exchange;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "exchange", uniqueConstraints = @UniqueConstraint(columnNames = "code"))
@FilterDef(name = "enabledFilter", parameters = @ParamDef(name = "isEnabled", type = Boolean.class))
@Filter(name = "enabledFilter", condition = "enabled = :isEnabled")
public class Exchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, unique = true)
    private String code; // ex: "BINANCE", "KRAKEN"

    @Column(nullable = false, length = 100)
    private String name; // ex: "Binance Global", "Kraken Exchange"

    private String apiBaseUrl; // ex: "https://api.binance.com"

    private boolean enabled = true;

    private LocalDateTime createdAt;
}