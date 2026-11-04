package fr.ses10doigts.tradeIO5.configuration.initializer;

import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.repository.execution.ExecutionControlRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Défaut d'exécution : crée la ligne {@link ExecutionControl} si elle est absente (kill switch ENGAGÉ, multiplicateurs par
 * défaut) et <b>complète les seuls champs nuls</b> (plafonds absolus, seuils Fee Test) : valeur en base d'abord ; sinon propriété
 * (seuils Fee Test uniquement) ; sinon constante codée ici. Une valeur déjà en base n'est jamais modifiée (règle des défauts,
 * {@code docs/operations/flyway.md}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(70)
public class ExecutionControlInitializer implements CommandLineRunner {

    /** Valeurs codées en dernier recours (phase de test : 30 / 100 USD ; Fee Test 0,2 % / 0,8 %). */
    public static final BigDecimal DEFAULT_ABSOLUTE_MAX_ORDER_USD = new BigDecimal("30");
    public static final BigDecimal DEFAULT_ABSOLUTE_MAX_DAY_USD = new BigDecimal("100");
    public static final BigDecimal DEFAULT_FEE_TEST_WARN_PCT = new BigDecimal("0.2");
    public static final BigDecimal DEFAULT_FEE_TEST_RED_PCT = new BigDecimal("0.8");

    private final ExecutionControlRepository repository;
    private final DomainClock clock;
    private final Environment environment;

    @Override
    public void run(String... args) {
        ExecutionControl row = repository.findById(ExecutionControl.SINGLETON_ID).orElse(null);
        boolean created = row == null;
        if (created) {
            row = ExecutionControl.initial(clock.now());
        }
        boolean changed = created;
        if (row.getAbsoluteMaxOrderUsd() == null) {
            row.setAbsoluteMaxOrderUsd(DEFAULT_ABSOLUTE_MAX_ORDER_USD);
            changed = true;
        }
        if (row.getAbsoluteMaxDayUsd() == null) {
            row.setAbsoluteMaxDayUsd(DEFAULT_ABSOLUTE_MAX_DAY_USD);
            changed = true;
        }
        if (row.getFeeWarnPct() == null || row.getFeeRedPct() == null) {
            BigDecimal warn = row.getFeeWarnPct() != null ? row.getFeeWarnPct() : property(ExecutionDefaults.FEE_TEST_WARN_PROPERTY, DEFAULT_FEE_TEST_WARN_PCT);
            BigDecimal red = row.getFeeRedPct() != null ? row.getFeeRedPct() : property(ExecutionDefaults.FEE_TEST_RED_PROPERTY, DEFAULT_FEE_TEST_RED_PCT);
            if (warn.signum() < 0 || warn.compareTo(red) > 0) {
                log.warn("Seuils Fee Test incohérents (warn={} red={}) : défauts utilisés", warn, red);
                warn = DEFAULT_FEE_TEST_WARN_PCT;
                red = DEFAULT_FEE_TEST_RED_PCT;
            }
            row.setFeeWarnPct(warn);
            row.setFeeRedPct(red);
            changed = true;
        }
        if (!changed) {
            log.debug("ExecutionControl déjà complet");
            return;
        }
        repository.save(row);
        log.info("ExecutionControl {} : plafonds ordre {} × / jour {} × baseAmount, absolus {} / {} USD, Fee Test {} % / {} %",
                created ? "créé (kill switch engagé)" : "complété", row.getMaxOrderMultiplier(), row.getMaxDayMultiplier(),
                row.getAbsoluteMaxOrderUsd(), row.getAbsoluteMaxDayUsd(), row.getFeeWarnPct(), row.getFeeRedPct());
    }

    private BigDecimal property(String key, BigDecimal fallback) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Propriété {} invalide ({}) : défaut {} utilisé", key, value, fallback);
            return fallback;
        }
    }
}
