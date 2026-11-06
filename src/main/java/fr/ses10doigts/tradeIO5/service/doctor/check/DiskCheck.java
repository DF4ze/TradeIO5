package fr.ses10doigts.tradeIO5.service.doctor.check;

import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorFamily;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorImpact;
import fr.ses10doigts.tradeIO5.service.doctor.CheckResult;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorCheck;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorDefaults;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Espace disque libre du volume du répertoire de travail (logs, sauvegardes) — D2 : WARN sous 15 %, CRIT sous 5 %. */
@Component
public class DiskCheck implements DoctorCheck {

    private static final String SUBJECT = "volume-applicatif";
    private static final long GIB = 1024L * 1024L * 1024L;

    @Override
    public String id() {
        return "DISK";
    }

    @Override
    public String label() {
        return "Espace disque du volume applicatif";
    }

    @Override
    public DoctorFamily family() {
        return DoctorFamily.INFRA;
    }

    @Override
    public DoctorImpact impact() {
        return DoctorImpact.QUALITY;
    }

    @Override
    public List<CheckResult> run() {
        try {
            var store = Files.getFileStore(Path.of(".").toAbsolutePath());
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                return List.of(CheckResult.unknown(SUBJECT, "taille du volume indisponible"));
            }
            long freePct = usable * 100 / total;
            String message = freePct + " % libre (" + usable / GIB + " Go sur " + total / GIB + " Go)";
            if (freePct < DoctorDefaults.DISK_CRIT_FREE_PCT) {
                return List.of(CheckResult.crit(SUBJECT, message));
            }
            return List.of(freePct < DoctorDefaults.DISK_WARN_FREE_PCT
                    ? CheckResult.warn(SUBJECT, message) : CheckResult.ok(SUBJECT, message));
        } catch (IOException e) {
            return List.of(CheckResult.unknown(SUBJECT, "lecture impossible : " + e.getMessage()));
        }
    }
}
