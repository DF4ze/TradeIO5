package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.DoctorDtos;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposal;
import fr.ses10doigts.tradeIO5.model.entity.dca.bench.NewAssetProposalStatus;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.repository.dca.bench.NewAssetProposalRepository;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.admin.NewAssetProposalAdminService;
import fr.ses10doigts.tradeIO5.service.doctor.AlarmService;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorReport;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pastille du menu admin : une proposition de nouvel actif à valider = 1 point, sans doublon avec son alarme, et conservée une fois l'alarme acquittée. */
@DisplayName("Docteur : compteur du menu admin (menuCount) et propositions à valider")
class DoctorAdminControllerMenuCountTest {

    private final DoctorService doctor = mock(DoctorService.class);
    private final AlarmService alarms = mock(AlarmService.class);
    private final NewAssetProposalRepository proposals = mock(NewAssetProposalRepository.class);
    private DoctorAdminController controller;

    @BeforeEach
    void setUp() {
        when(doctor.report()).thenReturn(new DoctorReport(Instant.parse("2026-11-24T10:00:00Z"), DoctorStatus.OK, List.of()));
        controller = new DoctorAdminController(doctor, alarms, mock(JobRunRepository.class), mock(AdminActionLogService.class), proposals, null);
    }

    private void state(long crit, long warn, long proposalAlarms, int pending) {
        when(alarms.countOpenBySeverity(DoctorStatus.CRIT)).thenReturn(crit);
        when(alarms.countOpenBySeverity(DoctorStatus.WARN)).thenReturn(warn);
        when(alarms.countActiveByCheckId(NewAssetProposalAdminService.ALARM_CHECK)).thenReturn(proposalAlarms);
        when(proposals.findByStatusOrderByCreatedAtAsc(NewAssetProposalStatus.PROPOSE)).thenReturn(java.util.Collections.nCopies(pending, mock(NewAssetProposal.class)));
    }

    @Test
    @DisplayName("proposition à valider + son alarme ouverte : 1 seul point (pas de doublon)")
    void proposalAndItsAlarmCountOnce() {
        state(0, 1, 1, 1);
        DoctorDtos.Report r = controller.report();
        assertEquals(1, r.pendingAssets());
        assertEquals(1, r.menuCount());
        assertEquals(1, r.activeWarn(), "le compteur du Diagnostic reste celui des alarmes");
    }

    @Test
    @DisplayName("alarme acquittée alors que la proposition reste à valider : la pastille la compte toujours")
    void acknowledgedAlarmKeepsProposalCounted() {
        state(0, 0, 0, 2);
        assertEquals(2, controller.report().menuCount());
    }

    @Test
    @DisplayName("autres alarmes ajoutées ; aucune proposition : menuCount = CRIT + WARN")
    void otherAlarmsAdded() {
        state(1, 3, 1, 1);
        assertEquals(1 + 3 - 1 + 1, controller.report().menuCount());
        state(2, 4, 0, 0);
        assertEquals(6, controller.report().menuCount());
        state(0, 0, 0, 0);
        assertEquals(0, controller.report().menuCount());
    }
}
