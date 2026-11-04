package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveDefaultPresets;
import fr.ses10doigts.tradeIO5.service.dca.atr.bench.RainbowLiveExecutionService;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Au démarrage du serveur, ramène l'état moteur des presets Trend Mix actifs à la veille (dernière bougie clôturée) s'il
 * est en retard : le service a pu être arrêté lors de passages sous DOWN2 ou d'autres événements que l'état incrémental ne
 * verrait jamais. Aucun run ni ordre (cf. {@link RainbowLiveExecutionService#resync}). Actif seulement si
 * {@value RainbowLiveDefaultPresets#RESYNC_ON_STARTUP_PROPERTY}{@code =true} (absent en test). Une erreur n'empêche jamais
 * le démarrage ; un preset non resynchronisable (candles pas à jour) est rattrapé par la détection de trou de la passe.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = RainbowLiveDefaultPresets.RESYNC_ON_STARTUP_PROPERTY, havingValue = "true")
public class RainbowLiveResyncRunner {

    private final RainbowLiveExecutionService executionService;
    private final DomainClock clock;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            log.info("RainbowLiveResyncRunner : {}", executionService.resync(clock.now(), null, false,
                    RainbowLiveDefaultPresets.RESYNC_ACTOR, "contrôle au démarrage"));
        } catch (Exception e) {
            log.error("RainbowLiveResyncRunner : resynchronisation au démarrage impossible", e);
        }
    }
}
