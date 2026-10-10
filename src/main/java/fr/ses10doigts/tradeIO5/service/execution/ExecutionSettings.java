package fr.ses10doigts.tradeIO5.service.execution;

import fr.ses10doigts.tradeIO5.service.execution.credential.SecretCipher;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Réglages d'exécution lus une fois au démarrage. {@code tradeio.execution.mode} (défaut {@code DRY_RUN}) :
 * {@code LIVE} fait échouer le démarrage tant que {@code tradeio.execution.live-unlocked} n'est pas vrai <b>et</b> qu'aucune
 * clé maître n'est configurée ; valeur inconnue => échec aussi. Le déverrouillage en production se fait à l'étape d, avec Clem.
 */
@Slf4j
@Getter
@Component
public class ExecutionSettings {

    private final ExecutionMode mode;
    private final Duration planTtl;
    private final BigDecimal slippageTolerancePct;
    private final boolean liveUnlocked;

    @Autowired
    public ExecutionSettings(
            @Value("${" + ExecutionDefaults.MODE_PROPERTY + ":DRY_RUN}") String mode,
            @Value("${" + ExecutionDefaults.PLAN_TTL_PROPERTY + ":PT5M}") Duration planTtl,
            @Value("${" + ExecutionDefaults.SLIPPAGE_TOLERANCE_PROPERTY + ":0.1}") BigDecimal slippageTolerancePct,
            @Value("${" + ExecutionDefaults.LIVE_UNLOCKED_PROPERTY + ":false}") boolean liveUnlocked,
            SecretCipher cipher) {
        this(mode, planTtl, slippageTolerancePct, liveUnlocked, cipher.isConfigured());
    }

    public ExecutionSettings(String mode, Duration planTtl, BigDecimal slippageTolerancePct, boolean liveUnlocked,
                             boolean masterKeyConfigured) {
        this.mode = parse(mode);
        this.liveUnlocked = liveUnlocked;
        if (this.mode == ExecutionMode.LIVE && !(liveUnlocked && masterKeyConfigured)) {
            throw new IllegalStateException(ExecutionDefaults.MODE_PROPERTY + "=LIVE refusé : "
                    + (liveUnlocked ? "" : ExecutionDefaults.LIVE_UNLOCKED_PROPERTY + " n'est pas vrai")
                    + (!liveUnlocked && !masterKeyConfigured ? " et " : "")
                    + (masterKeyConfigured ? "" : "clé maître " + ExecutionDefaults.MASTER_KEY_ENV + " absente"));
        }
        if (planTtl.isZero() || planTtl.isNegative() || slippageTolerancePct.signum() < 0) {
            throw new IllegalArgumentException("Réglages d'exécution invalides : plan-ttl=" + planTtl
                    + " slippage-tolerance-pct=" + slippageTolerancePct);
        }
        this.planTtl = planTtl;
        this.slippageTolerancePct = slippageTolerancePct;
        log.info("Exécution : mode={} verrou LIVE={} plan-ttl={} tolérance de slippage={} %", this.mode,
                liveUnlocked ? "levé" : "verrouillé", planTtl, slippageTolerancePct);
    }

    /** Réglages sans déverrouillage LIVE ni clé maître (tests). */
    public ExecutionSettings(String mode, Duration planTtl, BigDecimal slippageTolerancePct) {
        this(mode, planTtl, slippageTolerancePct, false, false);
    }

    /** Réglages par défaut du code (tests). */
    public ExecutionSettings() {
        this(ExecutionMode.DRY_RUN.name(), ExecutionDefaults.PLAN_TTL, ExecutionDefaults.SLIPPAGE_TOLERANCE_PCT);
    }

    private static ExecutionMode parse(String value) {
        try {
            return ExecutionMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(ExecutionDefaults.MODE_PROPERTY + " inconnu : '" + value
                    + "' (OFF, DRY_RUN, LIVE attendus)", e);
        }
    }

    /** Produit-on des plans ? ({@code OFF} coupe aussi le dry-run ; {@code LIVE} planifie comme {@code DRY_RUN}) */
    public boolean planningEnabled() {
        return mode != ExecutionMode.OFF;
    }

    /** Les ordres réels sont-ils permis ? Seul {@code LIVE} (qui n'existe que verrou levé + clé maître) l'est. */
    public boolean liveExecution() {
        return mode == ExecutionMode.LIVE;
    }
}
