package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * Réglages d'un preset {@code TREND_MIX} : paramètres du Trend Mix, mapping du RANGE et les deux jeux Rainbow
 * (Bear / Bull, JSON {tuning, globals}). Absente ⇒ défauts du code ({@code RainbowLiveTrendConfigs#defaults}).
 * Supprimée avec son preset.
 */
@Entity
@Table(name = "rainbow_live_trend_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveTrendConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "preset_id", nullable = false, unique = true)
    private RainbowLivePreset preset;

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

    /** {@code RainbowSetSelector.RangeMapping}. */
    @Column(nullable = false, length = 16)
    private String rangeMapping;

    @Column(nullable = false, length = 8000)
    private String bearJson;

    @Column(nullable = false, length = 8000)
    private String bullJson;
}
