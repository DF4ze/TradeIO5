package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.doctor.JobTrigger;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.doctor.JobOutcome;
import fr.ses10doigts.tradeIO5.service.doctor.JobRunRecorder;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Passe 23:55 UTC du bench Rainbow (signal du jour sur la bougie D1 encore ouverte, puis rafraîchissement du run de la veille).
 * <b>Désactivé par défaut</b> ({@code tradeio.rainbow-live.pass-2355-cron=-}), cron cible {@code 0 55 23 * * *},
 * {@code zone = "UTC"} explicite. Déclenchement manuel : {@code POST /api/admin/rainbow-live/run}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RainbowLivePass2355Job {

    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;
    private final JobRunRecorder recorder;

    @Scheduled(cron = RainbowLiveDefaultPresets.PASS_2355_CRON, zone = RainbowLiveDefaultPresets.SCHEDULER_ZONE)
    public void run() {
        log.info("RainbowLivePass2355Job : {}", recorder.run(DoctorDefaults.JOB_RAINBOW_PASS, JobTrigger.CRON,
                () -> executionService.runPass(clock.now()), RainbowLiveExecutionService.PassSummary::errors));
    }
}
