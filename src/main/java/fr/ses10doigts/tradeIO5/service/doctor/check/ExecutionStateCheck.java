package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.dto.admin.AdminStateDto;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.service.admin.AdminStateService;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * État global de l'exécution (C1–C2) : kill switch (WARN « levé trop longtemps » optionnel, désactivé par défaut) et cohérence mode / verrou LIVE /
 * clé maître (sans clé maître, aucune credential n'est lisible).
 */
@Component
public class ExecutionStateCheck implements DoctorCheck {

    private static final String MODE_LIVE = "LIVE";

    private final AdminStateService stateService;
    private final DomainClock clock;
    private final int killSwitchLiftedWarnHours;

    public ExecutionStateCheck(AdminStateService stateService, DomainClock clock,
                               @Value(DoctorDefaults.KILL_SWITCH_LIFTED_WARN_HOURS) int killSwitchLiftedWarnHours) {
        this.stateService = stateService;
        this.clock = clock;
        this.killSwitchLiftedWarnHours = killSwitchLiftedWarnHours;
    }

    @Override
    public String id() {
        return "EXECUTION_STATE";
    }

    @Override
    public String label() {
        return "État de l'exécution : kill switch, mode, verrou LIVE, clé maître";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.EXECUTION;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    public List<CheckResult> run() {
        AdminStateDto state = stateService.state();
        boolean live = MODE_LIVE.equals(state.mode());
        return List.of(killSwitch(state), mode(state, live), masterKey(state, live));
    }

    private CheckResult killSwitch(AdminStateDto state) {
        if (state.killSwitch()) {
            return CheckResult.ok("kill-switch", "engagé : aucun ordre réel ne part");
        }
        Instant since = state.killSwitchUpdatedAt();
        String by = state.killSwitchUpdatedBy() == null ? "?" : state.killSwitchUpdatedBy();
        if (killSwitchLiftedWarnHours > 0 && since != null
                && Duration.between(since, clock.now()).toHours() >= killSwitchLiftedWarnHours) {
            return CheckResult.warn("kill-switch", "levé depuis " + since + " par " + by + " (> "
                    + killSwitchLiftedWarnHours + " h) : les ordres sont autorisés");
        }
        return CheckResult.ok("kill-switch", "levé (par " + by + (since == null ? "" : ", depuis " + since) + ") : ordres autorisés");
    }

    private CheckResult mode(AdminStateDto state, boolean live) {
        if (!live) {
            return CheckResult.ok("mode", "mode " + state.mode() + " : aucun ordre réel");
        }
        return state.liveUnlocked()
                ? CheckResult.ok("mode", "LIVE déverrouillé")
                : CheckResult.warn("mode", "mode LIVE mais verrou actif (live-unlocked=false) : aucun ordre réel ne part");
    }

    private CheckResult masterKey(AdminStateDto state, boolean live) {
        if (state.masterKeyConfigured()) {
            return CheckResult.ok("master-key", "clé maître configurée");
        }
        return live
                ? CheckResult.crit("master-key", "clé maître absente : credentials illisibles, aucun ordre possible")
                : CheckResult.warn("master-key", "clé maître absente (mode " + state.mode() + ")");
    }
}
