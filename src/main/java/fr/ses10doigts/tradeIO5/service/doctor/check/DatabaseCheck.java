package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

/** Base de données joignable et réactive (D1) : connexion valide, latence sous {@link DoctorDefaults#DB_SLOW_MS} ms. */
@Component
@RequiredArgsConstructor
public class DatabaseCheck implements DoctorCheck {

    private static final String SUBJECT = "database";
    private static final int VALID_TIMEOUT_SECONDS = 3;

    private final DataSource dataSource;

    @Override
    public String id() {
        return "DATABASE";
    }

    @Override
    public String label() {
        return "Base de données : connexion et latence";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.INFRA;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.EXECUTION;
    }

    @Override
    public List<CheckResult> run() {
        long t0 = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(VALID_TIMEOUT_SECONDS);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (!valid) {
                return List.of(CheckResult.crit(SUBJECT, "connexion invalide (" + ms + " ms)"));
            }
            return List.of(ms >= DoctorDefaults.DB_SLOW_MS
                    ? CheckResult.warn(SUBJECT, "lente : " + ms + " ms pour obtenir une connexion valide")
                    : CheckResult.ok(SUBJECT, "joignable (" + ms + " ms)"));
        } catch (Exception e) {
            return List.of(CheckResult.crit(SUBJECT, "injoignable : " + e.getClass().getSimpleName() + " " + e.getMessage()));
        }
    }
}
