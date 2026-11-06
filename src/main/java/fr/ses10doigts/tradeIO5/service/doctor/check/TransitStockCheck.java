package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.model.entity.execution.TransitStock;
import fr.ses10doigts.tradeIO5.repository.execution.TransitStockRepository;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Stock intermédiaire en transit (jambe 2 en attente) non résolu au-delà de {@link DoctorDefaults#TRANSIT_STOCK_MAX_HOURS} h (C5). */
@Component
@RequiredArgsConstructor
public class TransitStockCheck implements DoctorCheck {

    private final TransitStockRepository stocks;
    private final DomainClock clock;

    @Override
    public String id() {
        return "TRANSIT_STOCK";
    }

    @Override
    public String label() {
        return "Stocks en transit non résolus depuis trop longtemps";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.EXECUTION;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    public List<CheckResult> run() {
        Instant now = clock.now();
        List<CheckResult> results = new ArrayList<>();
        for (TransitStock stock : stocks.findByResolvedAtIsNull()) {
            long hours = Duration.between(stock.getCreatedAt(), now).toHours();
            if (hours >= DoctorDefaults.TRANSIT_STOCK_MAX_HOURS) {
                results.add(CheckResult.warn("stock#" + stock.getId() + " " + stock.getCurrency() + "→" + stock.getTargetAsset(),
                        stock.getQuantity() + " " + stock.getCurrency() + " en transit vers " + stock.getTargetAsset()
                                + " depuis " + hours + " h (wallet " + stock.getWalletId() + ", plan " + stock.getOriginPlanId() + ")"));
            }
        }
        return results;
    }
}
