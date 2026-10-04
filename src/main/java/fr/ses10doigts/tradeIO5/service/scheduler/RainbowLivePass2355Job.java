package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Passe 23:55 UTC du bench Rainbow (bougie du jour en cours, action fictive appliquée au wallet mock).
 * <b>Désactivé par défaut</b> ({@code tradeio.rainbow-live.pass-2355-cron=-}), cron cible {@code 0 55 23 * * *},
 * {@code zone = "UTC"} explicite. Déclenchement manuel : {@code POST /api/admin/rainbow-live/run?pass=T2355}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RainbowLivePass2355Job {

    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;

    @Scheduled(cron = RainbowLiveDefaultPresets.PASS_2355_CRON, zone = RainbowLiveDefaultPresets.SCHEDULER_ZONE)
    public void run() {
        log.info("RainbowLivePass2355Job : {}", executionService.runPass(RainbowLivePass.T2355, clock.now()));
    }
}
