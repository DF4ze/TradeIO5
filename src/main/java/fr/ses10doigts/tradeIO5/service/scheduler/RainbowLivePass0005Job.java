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
 * Passe 00:05 UTC du bench Rainbow (vraie clôture de la veille ; action « qui aurait été prise », jamais appliquée).
 * <b>Désactivé par défaut</b> ({@code tradeio.rainbow-live.pass-0005-cron=-}), cron cible {@code 0 5 0 * * *},
 * {@code zone = "UTC"} explicite. Déclenchement manuel : {@code POST /api/admin/rainbow-live/run?pass=T0005}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RainbowLivePass0005Job {

    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;

    @Scheduled(cron = RainbowLiveDefaultPresets.PASS_0005_CRON, zone = RainbowLiveDefaultPresets.SCHEDULER_ZONE)
    public void run() {
        log.info("RainbowLivePass0005Job : {}", executionService.runPass(RainbowLivePass.T0005, clock.now()));
    }
}
