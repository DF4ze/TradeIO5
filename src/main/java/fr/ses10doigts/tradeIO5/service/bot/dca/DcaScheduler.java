package fr.ses10doigts.tradeIO5.service.bot.dca;

import fr.ses10doigts.tradeIO5.model.dto.bot.DcaSettings;
import fr.ses10doigts.tradeIO5.repository.DcaSettingsRepository;
import fr.ses10doigts.tradeIO5.service.connector.ProviderApiService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class DcaScheduler {

    // Injection d'un repository ou d'un client d'exchange
    private final DcaSettingsRepository dcaRepository;
    private final ProviderApiService exchangeService;

    public DcaScheduler(DcaSettingsRepository dcaRepository, ProviderApiService exchangeService) {
        this.dcaRepository = dcaRepository;
        this.exchangeService = exchangeService;
    }

    // Méthode planifiée toutes les minutes pour vérifier si un achat est à déclencher
    @Scheduled(cron = "0 * * * * ?") // toutes les minutes
    public void checkAndExecuteDca() {
        dcaRepository.findByEnabledTrue().forEach(settings -> {
            if (isTimeToLaunch(settings)) {
                executeDCA(settings);
            }
        });
    }

    private boolean isTimeToLaunch(DcaSettings settings) {
        LocalDateTime now = LocalDateTime.now();
        switch (settings.getPurchaseFrequency()) {
            case DAILY:
                return now.getHour() == settings.getPurchaseTime().getHour()
                        && now.getMinute() == settings.getPurchaseTime().getMinute();
            case WEEKLY:
                return now.getDayOfWeek().getValue() == settings.getDayOfWeek()
                        && now.getHour() == settings.getPurchaseTime().getHour()
                        && now.getMinute() == settings.getPurchaseTime().getMinute();
            case MONTHLY:
                return now.getDayOfMonth() == settings.getDayOfMonth()
                        && now.getHour() == settings.getPurchaseTime().getHour()
                        && now.getMinute() == settings.getPurchaseTime().getMinute();
            default:
                return false;
        }
    }

    private void executeDCA(DcaSettings settings) {
        BigDecimal amount = settings.getBaseAmount();
        // Ici on pourra appliquer les ajustements RSI/Rainbow Chart plus tard
        //exchangeService.buy(settings.getUserId(), amount);
        System.out.println("Executed DCA " + settings + " montant : " + amount);
    }
}