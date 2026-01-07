package fr.ses10doigts.tradeIO5.model.dto.bot;

import fr.ses10doigts.tradeIO5.model.entity.currency.Wallet;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.DcaStrategyType;
import fr.ses10doigts.tradeIO5.model.enumerate.bot.Frequency;
import fr.ses10doigts.tradeIO5.service.bot.dca.strategy.DcaStrategy;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.math.BigDecimal;

@Entity
@Table(name = "dca_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DcaSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Référence au Wallet
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @Column(name = "asset", nullable = false)
    private String asset;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false)
    private Frequency purchaseFrequency;

    @Column(name = "day_of_month")   // Pour monthly
    private Integer dayOfMonth;

    @Column(name = "day_of_week")    // 1 = lundi, 7 = dimanche, pour weekly
    private Integer dayOfWeek;

    @Column(name = "purchase_time")
    private LocalTime purchaseTime;   // Heure de l’achat

    @Column(name = "base_amount", precision = 19, scale = 8, nullable = false)
    private BigDecimal baseAmount;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy")
    private DcaStrategyType strategy;

}