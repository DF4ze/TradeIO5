package fr.ses10doigts.tradeIO5.service.scheduler;

import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Passe périodique du Docteur (toutes les 5 min par défaut, première passe 1 min après le démarrage). Actif par défaut. */
@Component
@RequiredArgsConstructor
public class DoctorJob {

    private final DoctorService doctor;

    @Scheduled(initialDelayString = DoctorDefaults.INITIAL_DELAY_MS, fixedDelayString = DoctorDefaults.INTERVAL_MS)
    public void run() {
        doctor.runAll();
    }
}
