package fr.ses10doigts.tradeIO5.configuration.initializer;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.service.backup.HistoricalDataBackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Recharge les données historiques (bougies, flux ETF) depuis les fichiers de backup, table par table, si vide. */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(5)
public class HistoricalDataInitializer implements CommandLineRunner {

    private final HistoricalDataBackupService backupService;

    @Override
    public void run(String... args) {
        log.info("Seed historique : {}", backupService.importIfEmpty());
    }
}
