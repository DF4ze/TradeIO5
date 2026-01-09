package fr.ses10doigts.tradeIO5.model.entity.decision.strategy.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.decision.IndicatorCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "indicator_parameter_set",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_indicatorparamset_code_name", columnNames = {"indicator_code", "name"})
       })
@Getter
@Setter
@NoArgsConstructor
public class IndicatorParameterSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "indicator_code", nullable = false, length = 30)
    private IndicatorCode indicatorCode; // ex: RSI, MACD

    @Column(nullable = false, length = 100)
    private String name; // ex: "RSI short term"

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Pour versionner / faire évoluer un set
     */
    @Column(nullable = false)
    private Integer version = 1;

    /**
     * Optionnel : rattachement fonctionnel
     * STRATEGY / USER / GLOBAL
     *
    @Column(length = 30)
    private String scope;
*/
    @OneToMany(
        mappedBy = "parameterSet",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.EAGER
    )
    private List<IndicatorParameter> parameters = new ArrayList<>();
}
