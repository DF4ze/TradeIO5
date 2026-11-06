package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.doctor.JobRun;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Jobs critiques de la chaîne quotidienne (A1–A5) : cron activé, exécuté à l'échéance, dernier run sans erreur. La liste des
 * jobs attendus vient de {@code tradeio.doctor.expected-jobs} (défaut : run Rainbow, plan, exécution) ; le cron effectif est lu
 * dans l'{@link Environment} (donc la config réellement chargée, VPS compris), jamais dans un fichier.
 */
@Component
@RequiredArgsConstructor
public class JobScheduleCheck implements DoctorCheck {

    /** Cron (propriété + fuseau) de chaque job suivi, repris des constantes des jobs. */
    private record JobSpec(String cronProperty, ZoneId zone) {
    }

    private static final Map<String, JobSpec> SPECS = Map.of(
            DoctorDefaults.JOB_RAINBOW_PASS, new JobSpec(RainbowLiveDefaultPresets.PASS_2355_CRON_PROPERTY,
                    ZoneId.of(RainbowLiveDefaultPresets.SCHEDULER_ZONE)),
            DoctorDefaults.JOB_EXECUTION_PLAN, new JobSpec(ExecutionDefaults.PLAN_CRON_PROPERTY,
                    ZoneId.of(ExecutionDefaults.SCHEDULER_ZONE)),
            DoctorDefaults.JOB_EXECUTION, new JobSpec(ExecutionDefaults.RUN_CRON_PROPERTY,
                    ZoneId.of(ExecutionDefaults.SCHEDULER_ZONE)));

    private final Environment environment;
    private final JobRunRepository jobRuns;
    private final DomainClock clock;

    @Override
    public String id() {
        return "JOB_SCHEDULE";
    }

    @Override
    public String label() {
        return "Jobs quotidiens : cron actif, exécuté à l'heure, sans erreur";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.PLANNING;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    public List<CheckResult> run() {
        List<CheckResult> results = new ArrayList<>();
        String expected = environment.getProperty(DoctorDefaults.EXPECTED_JOBS_PROPERTY, DoctorDefaults.EXPECTED_JOBS_DEFAULT);
        for (String name : Arrays.stream(expected.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList()) {
            JobSpec spec = SPECS.get(name);
            results.add(spec == null
                    ? CheckResult.unknown(name, "job inconnu du Docteur (" + DoctorDefaults.EXPECTED_JOBS_PROPERTY + ")")
                    : evaluate(name, spec));
        }
        return results;
    }

    private CheckResult evaluate(String name, JobSpec spec) {
        String cronValue = environment.getProperty(spec.cronProperty(), Scheduled.CRON_DISABLED).trim();
        if (cronValue.isEmpty() || Scheduled.CRON_DISABLED.equals(cronValue)) {
            return CheckResult.crit(name, "cron désactivé (" + spec.cronProperty() + "=-) alors que le job est attendu actif");
        }
        CronExpression cron;
        try {
            cron = CronExpression.parse(cronValue);
        } catch (IllegalArgumentException e) {
            return CheckResult.crit(name, "cron invalide (" + spec.cronProperty() + "=" + cronValue + ")");
        }
        Optional<JobRun> last = jobRuns.findFirstByJobNameOrderByStartedAtDesc(name);
        if (last.isEmpty()) {
            return CheckResult.unknown(name, "aucune exécution tracée (journal vide : premier déploiement ?), cron " + cronValue);
        }
        JobRun run = last.get();
        Instant now = clock.now();
        Instant due = previousFiring(cron, spec.zone(), now);
        if (due != null && run.getStartedAt().isBefore(due.minusSeconds(DoctorDefaults.JOB_START_SLACK_SECONDS))) {
            if (now.isAfter(due.plus(Duration.ofMinutes(DoctorDefaults.JOB_GRACE_MINUTES)))) {
                return CheckResult.crit(name, "en retard : échéance " + due + " sans exécution (dernier run " + run.getStartedAt()
                        + ", " + run.getStatus() + ")");
            }
            return CheckResult.ok(name, "échéance " + due + " en cours (délai de grâce), cron " + cronValue);
        }
        String detail = run.getTriggeredBy() + " " + run.getStartedAt() + " (" + run.getDurationMs() + " ms) " + run.getSummary();
        return switch (run.getStatus()) {
            case OK -> CheckResult.ok(name, "dernier run OK : " + detail);
            case PARTIAL -> CheckResult.warn(name, "dernier run partiel (" + run.getErrors() + " erreur(s)) : " + detail);
            case FAILED -> CheckResult.crit(name, "dernier run en échec : " + run.getErrorMessage() + " — " + detail);
        };
    }

    /** Dernière échéance du cron ≤ {@code now} (fenêtre de recherche : 2 jours), ou {@code null} s'il n'y en a pas. */
    static Instant previousFiring(CronExpression cron, ZoneId zone, Instant now) {
        ZonedDateTime cursor = now.atZone(zone).minusDays(2);
        Instant last = null;
        for (int i = 0; i < 500; i++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null || next.toInstant().isAfter(now)) {
                break;
            }
            last = next.toInstant();
            cursor = next;
        }
        return last;
    }
}
