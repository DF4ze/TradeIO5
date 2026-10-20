package fr.ses10doigts.tradeIO5.service.portfolio;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Prochain déclenchement de la passe quotidienne d'après le cron configuré ; passe désactivée (cron « - ») ⇒ aucune date. */
@Service
public class NextPassService {

    /** {@code nextRunAt} nul quand le planificateur est désactivé. */
    public record NextPass(boolean enabled, Instant nextRunAt) {
    }

    private final String cron;
    private final DomainClock clock;

    public NextPassService(@Value(RainbowLiveDefaultPresets.PASS_2355_CRON) String cron, DomainClock clock) {
        this.cron = cron;
        this.clock = clock;
    }

    public NextPass next() {
        if (cron == null || cron.isBlank() || "-".equals(cron.trim())) {
            return new NextPass(false, null);
        }
        ZonedDateTime next = CronExpression.parse(cron.trim())
                .next(clock.now().atZone(ZoneId.of(RainbowLiveDefaultPresets.SCHEDULER_ZONE)));
        return new NextPass(true, next == null ? null : next.toInstant());
    }
}
