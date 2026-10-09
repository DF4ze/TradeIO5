package fr.ses10doigts.tradeIO5.model.entity.dca.bench;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrState;
import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowMoon;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;

/**
 * État de la machine Rainbow ({@link RainbowAtrState}) d'un preset {@code TREND_MIX} à la FIN du jour {@code day}
 * (après la passe 23:55). Une ligne par (preset, jour) : la passe du lendemain repart de la dernière ligne
 * antérieure à son jour, la passe 00:05 relit celle de la veille sans rien écrire. Supprimé avec son preset.
 * Les NaN du moteur (« sans objet ») sont stockés {@code null}.
 */
@Entity
@Table(name = "rainbow_live_engine_state",
        uniqueConstraints = @UniqueConstraint(name = "uk_rainbow_live_engine_state_preset_day", columnNames = {"preset_id", "state_day"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RainbowLiveEngineState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "preset_id", nullable = false)
    private RainbowLivePreset preset;

    @Column(name = "state_day", nullable = false)
    private LocalDate day;

    private boolean buyArmed;
    private boolean sellArmed;
    private boolean buyLocked;
    private Double lowestSinceArmed;
    private Double highestSinceArmed;
    private int buyArmedDays;
    private int sellArmedDays;
    private int cooldown;
    private double reserveQty;
    private boolean moonActive;
    private Double moonPeak;

    public RainbowAtrState toState() {
        return new RainbowAtrState(buyArmed, sellArmed, buyLocked, nan(lowestSinceArmed), nan(highestSinceArmed),
                buyArmedDays, sellArmedDays, cooldown, reserveQty,
                new RainbowMoon.State(moonActive, nan(moonPeak)));
    }

    public void apply(RainbowAtrState s) {
        buyArmed = s.buyArmed();
        sellArmed = s.sellArmed();
        buyLocked = s.buyLocked();
        lowestSinceArmed = nullable(s.lowestSinceArmed());
        highestSinceArmed = nullable(s.highestSinceArmed());
        buyArmedDays = s.buyArmedDays();
        sellArmedDays = s.sellArmedDays();
        cooldown = s.cooldown();
        reserveQty = s.reserveQty();
        moonActive = s.moon().active();
        moonPeak = nullable(s.moon().peak());
    }

    public static RainbowLiveEngineState of(RainbowLivePreset preset, LocalDate day, RainbowAtrState s) {
        RainbowLiveEngineState e = RainbowLiveEngineState.builder().preset(preset).day(day).build();
        e.apply(s);
        return e;
    }

    private static double nan(Double v) {
        return v == null ? Double.NaN : v;
    }

    private static Double nullable(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
