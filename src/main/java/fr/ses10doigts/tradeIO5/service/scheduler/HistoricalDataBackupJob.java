package fr.ses10doigts.tradeIO5.service.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.service.backup.HistoricalDataBackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sauvegarde hebdomadaire des données historiques (bougies, flux ETF) vers {@code tradeio.backup.dir}.
 * Cron : {@code tradeio.backup.historical-cron} (défaut dimanche 04:00 ; {@code -} désactive).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoricalDataBackupJob {

    private final HistoricalDataBackupService backupService;

    @Scheduled(cron = "${tradeio.backup.historical-cron:0 0 4 * * SUN}")
    public void run() {
        log.info("HistoricalDataBackupJob : {}", backupService.exportAll());
    }
}
