package fr.ses10doigts.tradeIO5.service.execution;

import fr.ses10doigts.tradeIO5.configuration.initializer.ExecutionControlInitializer;
import fr.ses10doigts.tradeIO5.model.entity.execution.ExecutionControl;
import fr.ses10doigts.tradeIO5.repository.execution.ExecutionControlRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Accès à la ligne globale {@link ExecutionControl}. Aucune mise en cache : l'exécuteur relit à chaque étape, donc le kill
 * switch agit aussitôt. Ligne absente => kill switch engagé (échec fermé). Plafonds absolus et seuils Fee Test : champs nuls
 * => valeurs codées de {@link ExecutionControlInitializer} (accesseurs statiques, utilisables sur toute ligne lue).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionControlService {

    private final ExecutionControlRepository repository;
    private final DomainClock clock;

    @Transactional(readOnly = true)
    public ExecutionControl current() {
        return repository.findById(ExecutionControl.SINGLETON_ID).orElseGet(ExecutionControl::failClosed);
    }

    @Transactional
    public ExecutionControl setKillSwitch(boolean engaged, String by) {
        ExecutionControl control = repository.findById(ExecutionControl.SINGLETON_ID)
                .orElseGet(() -> ExecutionControl.initial(clock.now()));
        control.setKillSwitch(engaged);
        control.setUpdatedAt(clock.now());
        control.setUpdatedBy(by);
        ExecutionControl saved = repository.save(control);
        log.info("Kill switch d'exécution {} par {}", engaged ? "ENGAGÉ" : "levé", by);
        return saved;
    }

    /** Jeu complet des plafonds et seuils éditables par l'admin. */
    public record Limits(BigDecimal maxOrderMultiplier, BigDecimal maxDayMultiplier, BigDecimal absoluteMaxOrderUsd,
                         BigDecimal absoluteMaxDayUsd, BigDecimal feeWarnPct, BigDecimal feeRedPct) {
        public static Limits of(ExecutionControl c) {
            return new Limits(c.getMaxOrderMultiplier(), c.getMaxDayMultiplier(),
                    ExecutionControlService.absoluteMaxOrderUsd(c), ExecutionControlService.absoluteMaxDayUsd(c),
                    ExecutionControlService.feeWarnPct(c), ExecutionControlService.feeRedPct(c));
        }
    }

    /** Bornes de validation (incluses) : multiplicateurs, plafonds absolus (USD), seuils Fee Test (%). */
    /** Multiplicateurs par défaut (× baseAmount du preset) : ordre 1 ×, jour/utilisateur 2 ×. */
    public static final BigDecimal DEFAULT_MAX_ORDER_MULTIPLIER = ExecutionDefaults.DEFAULT_MAX_ORDER_MULTIPLIER;
    public static final BigDecimal DEFAULT_MAX_DAY_MULTIPLIER = ExecutionDefaults.DEFAULT_MAX_DAY_MULTIPLIER;
    public static final BigDecimal MAX_MULTIPLIER = BigDecimal.valueOf(100);
    public static final BigDecimal MAX_ABSOLUTE_USD = BigDecimal.valueOf(1_000_000);
    public static final BigDecimal MAX_FEE_PCT = BigDecimal.valueOf(100);

    public static BigDecimal absoluteMaxOrderUsd(ExecutionControl c) {
        return c.getAbsoluteMaxOrderUsd() != null ? c.getAbsoluteMaxOrderUsd() : ExecutionControlInitializer.DEFAULT_ABSOLUTE_MAX_ORDER_USD;
    }

    public static BigDecimal absoluteMaxDayUsd(ExecutionControl c) {
        return c.getAbsoluteMaxDayUsd() != null ? c.getAbsoluteMaxDayUsd() : ExecutionControlInitializer.DEFAULT_ABSOLUTE_MAX_DAY_USD;
    }

    public static BigDecimal feeWarnPct(ExecutionControl c) {
        return c.getFeeWarnPct() != null ? c.getFeeWarnPct() : ExecutionControlInitializer.DEFAULT_FEE_TEST_WARN_PCT;
    }

    public static BigDecimal feeRedPct(ExecutionControl c) {
        return c.getFeeRedPct() != null ? c.getFeeRedPct() : ExecutionControlInitializer.DEFAULT_FEE_TEST_RED_PCT;
    }

    /** Remplace le jeu complet ; {@link IllegalArgumentException} (rien écrit) si une valeur est absente ou hors bornes. */
    @Transactional
    public ExecutionControl updateLimits(Limits limits, String by) {
        validate(limits);
        ExecutionControl control = repository.findById(ExecutionControl.SINGLETON_ID)
                .orElseGet(() -> ExecutionControl.initial(clock.now()));
        control.setMaxOrderMultiplier(limits.maxOrderMultiplier());
        control.setMaxDayMultiplier(limits.maxDayMultiplier());
        control.setAbsoluteMaxOrderUsd(limits.absoluteMaxOrderUsd());
        control.setAbsoluteMaxDayUsd(limits.absoluteMaxDayUsd());
        control.setFeeWarnPct(limits.feeWarnPct());
        control.setFeeRedPct(limits.feeRedPct());
        control.setUpdatedAt(clock.now());
        control.setUpdatedBy(by);
        ExecutionControl saved = repository.save(control);
        log.info("Plafonds d'exécution modifiés par {} : {}", by, limits);
        return saved;
    }

    private static void validate(Limits l) {
        if (l == null) {
            throw new IllegalArgumentException("Plafonds manquants");
        }
        range("Multiplicateur par ordre", l.maxOrderMultiplier(), MAX_MULTIPLIER);
        range("Multiplicateur par jour", l.maxDayMultiplier(), MAX_MULTIPLIER);
        range("Plafond absolu par ordre", l.absoluteMaxOrderUsd(), MAX_ABSOLUTE_USD);
        range("Plafond absolu par jour", l.absoluteMaxDayUsd(), MAX_ABSOLUTE_USD);
        if (l.absoluteMaxDayUsd().compareTo(l.absoluteMaxOrderUsd()) < 0) {
            throw new IllegalArgumentException("Le plafond absolu par jour doit être ≥ au plafond absolu par ordre");
        }
        if (l.feeWarnPct() == null || l.feeRedPct() == null || l.feeWarnPct().signum() < 0
                || l.feeWarnPct().compareTo(l.feeRedPct()) > 0 || l.feeRedPct().compareTo(MAX_FEE_PCT) > 0) {
            throw new IllegalArgumentException("Seuils Fee Test : 0 ≤ avertissement ≤ bloquant ≤ " + MAX_FEE_PCT.toPlainString() + " %");
        }
    }

    private static void range(String label, BigDecimal value, BigDecimal max) {
        if (value == null || value.signum() <= 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(label + " : valeur > 0 et ≤ " + max.toPlainString() + " requise");
        }
    }
}
