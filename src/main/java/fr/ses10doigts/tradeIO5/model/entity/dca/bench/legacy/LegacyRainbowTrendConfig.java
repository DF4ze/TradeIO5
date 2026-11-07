package fr.ses10doigts.tradeIO5.model.entity.dca.bench.legacy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Ancienne ligne de réglages Trend d'un preset (table {@code rainbow_live_trend_config}), lecture seule pour la migration. */
@Entity
@Table(name = "rainbow_live_trend_config")
@Getter
@NoArgsConstructor
public class LegacyRainbowTrendConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "preset_id", nullable = false, unique = true)
    private LegacyRainbowPreset preset;

    private int shortWindow;
    private int mediumWindow;
    private int longWindow;
    private double slopeScale;
    private double enterThreshold;
    private double exitThreshold;
    private int confirmDays;
    private int smaPeriod;
    private int atrPeriod;
    private double atrMultiplier;
    private boolean wickDown;
    private boolean wickUp;

    @Column(nullable = false, length = 16)
    private String rangeMapping;

    @Column(nullable = false, length = 8000)
    private String bearJson;

    @Column(nullable = false, length = 8000)
    private String bullJson;
}
