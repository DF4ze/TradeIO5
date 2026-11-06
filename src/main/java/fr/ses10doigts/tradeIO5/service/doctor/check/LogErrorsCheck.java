package fr.ses10doigts.tradeIO5.service.doctor.check;

import ch.qos.logback.classic.Level;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorLogBuffer;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Logs ERROR / WARN des dernières {@link DoctorDefaults#LOG_WINDOW_HOURS} h (E1–E2, version légère : comptage + dernier
 * échantillon, sans agrégation). Un ERROR suffit à lever une alarme WARN ; les WARN n'alarment qu'au-delà de
 * {@link DoctorDefaults#LOG_WARN_THRESHOLD} (bruit connu). Le tampon est vidé au redémarrage.
 */
@Component
@RequiredArgsConstructor
public class LogErrorsCheck implements DoctorCheck {

    private final DoctorLogBuffer buffer;
    private final DomainClock clock;

    @Override
    public String id() {
        return "LOG_ERRORS";
    }

    @Override
    public String label() {
        return "Logs ERROR / WARN des dernières 24 h";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.LOGS;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.QUALITY;
    }

    @Override
    public List<CheckResult> run() {
        Instant now = clock.now();
        Instant since = now.minus(Duration.ofHours(DoctorDefaults.LOG_WINDOW_HOURS));
        Instant from = since.isBefore(buffer.startedAt()) ? buffer.startedAt() : since;
        String scope = "depuis " + from + " (tampon vidé au redémarrage)";
        return List.of(level("ERROR", Level.ERROR, from, scope, 1), level("WARN", Level.WARN, from, scope,
                DoctorDefaults.LOG_WARN_THRESHOLD));
    }

    private CheckResult level(String subject, Level level, Instant from, String scope, int alarmThreshold) {
        List<DoctorLogBuffer.Entry> found = buffer.since(from, level);
        if (found.isEmpty()) {
            return CheckResult.ok(subject, "aucun log " + subject + " " + scope);
        }
        DoctorLogBuffer.Entry last = found.getLast();
        String message = found.size() + " log(s) " + subject + " " + scope + " ; dernier " + last.at() + " "
                + last.logger() + " — " + last.message();
        return found.size() >= alarmThreshold ? CheckResult.warn(subject, message) : CheckResult.ok(subject, message);
    }
}
