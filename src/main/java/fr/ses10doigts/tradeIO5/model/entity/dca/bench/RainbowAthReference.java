package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

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

import java.time.LocalDate;

/**
 * ATH de référence d'un ACTIF (pas d'un utilisateur), « plus haut atteint jusqu'à la fin du jour {@code day} UTC
 * inclus ». Une ligne par (actif, jour traité) : la passe 00:05 relit ainsi l'ATH de la VEILLE même si la passe 23:55
 * du jour l'a déjà mis à jour. Semé une fois sur l'historique D1 complet puis alimenté par la passe 23:55.
 */
@Entity
@Table(name = "rainbow_ath_reference",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_ath_reference_asset_day", columnNames = {"asset_symbol", "ref_day"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowAthReference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_symbol", nullable = false, length = 16)
    private String assetSymbol;

    /** Jour UTC jusqu'auquel (inclus) l'ATH est valable. */
    @Column(name = "ref_day", nullable = false)
    private LocalDate day;

    private double athValue;

    /** Horodatage (ms) de la bougie qui a posé cet ATH. */
    private long athTimeMillis;
}
