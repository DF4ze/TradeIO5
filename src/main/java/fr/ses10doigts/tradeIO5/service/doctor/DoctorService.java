package fr.ses10doigts.tradeIO5.service.doctor;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Passe du Docteur : exécute tous les {@link DoctorCheck} (chacun isolé : une exception devient un verdict WARN « contrôle en
 * erreur », jamais un arrêt de la passe), rapproche le résultat des alarmes ({@link AlarmService#reconcile}) et garde le
 * dernier rapport en mémoire. Observateur uniquement : aucun contrôle n'agit sur le système.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorService {

    /** Sujet du verdict émis quand un contrôle lève une exception. */
    static final String CHECK_ERROR_SUBJECT = "_contrôle";

    private final List<DoctorCheck> checks;
    private final AlarmService alarmService;
    private final DomainClock clock;

    private volatile DoctorReport last;

    /** Lance une passe complète (sérialisée) et rapproche les alarmes. */
    public synchronized DoctorReport runAll() {
        List<DoctorReport.Entry> entries = new ArrayList<>();
        Set<String> evaluated = new HashSet<>();
        for (DoctorCheck check : checks) {
            try {
                for (CheckResult r : check.run()) {
                    entries.add(entry(check, r.subject(), r.status(), r.message()));
                }
                evaluated.add(check.id());
            } catch (Exception e) {
                log.error("Docteur : le contrôle {} a échoué", check.id(), e);
                entries.add(entry(check, CHECK_ERROR_SUBJECT, DoctorStatus.WARN,
                        "contrôle en erreur : " + e.getClass().getSimpleName() + " " + e.getMessage()));
            }
        }
        DoctorReport report = new DoctorReport(clock.now(), worst(entries), List.copyOf(entries));
        try {
            alarmService.reconcile(report, evaluated);
        } catch (Exception e) {
            log.error("Docteur : rapprochement des alarmes impossible", e);
        }
        last = report;
        log.debug("Docteur : passe terminée, pire statut {} ({} verdict(s))", report.worst(), entries.size());
        return report;
    }

    /** Dernier rapport ; lance une première passe s'il n'y en a pas encore. */
    public DoctorReport report() {
        DoctorReport report = last;
        return report != null ? report : runAll();
    }

    private static DoctorReport.Entry entry(DoctorCheck check, String subject, DoctorStatus status, String message) {
        return new DoctorReport.Entry(check.id(), check.label(), check.family(), check.impact(), subject, status, message);
    }

    private static DoctorStatus worst(List<DoctorReport.Entry> entries) {
        DoctorStatus worst = DoctorStatus.OK;
        for (DoctorReport.Entry e : entries) {
            if (e.status().severityRank() > worst.severityRank()) {
                worst = e.status();
            } else if (e.status() == DoctorStatus.UNKNOWN && worst == DoctorStatus.OK) {
                worst = DoctorStatus.UNKNOWN;
            }
        }
        return worst;
    }
}
