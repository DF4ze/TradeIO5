package fr.ses10doigts.tradeIO5.model.entity.tree.indicator;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.indicator.ParameterType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "indicator_parameter",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_indicatorparam_param_key", columnNames = {"parameter_set_id", "param_key"})
       })
@Getter
@Setter
@NoArgsConstructor
public class IndicatorParameter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "parameter_set_id", nullable = false)
    private IndicatorParameterSet parameterSet;

    @Column(name = "param_key", nullable = false, length = 50)
    private String key; // ex: period, fastPeriod, slowPeriod

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParameterType type;

    @Column(name = "numeric_value")
    private Double numericValue;

    @Column(name = "string_value", length = 100)
    private String stringValue;

    @Column(name = "boolean_value")
    private Boolean booleanValue;
}
