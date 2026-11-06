package fr.ses10doigts.tradeIO5.controller;

import fr.ses10doigts.tradeIO5.model.dto.admin.DoctorDtos;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.model.entity.doctor.Alarm;
import fr.ses10doigts.tradeIO5.model.entity.doctor.AlarmState;
import fr.ses10doigts.tradeIO5.model.entity.doctor.DoctorStatus;
import fr.ses10doigts.tradeIO5.repository.doctor.JobRunRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;
import fr.ses10doigts.tradeIO5.service.admin.AdminActionLogService;
import fr.ses10doigts.tradeIO5.service.doctor.AlarmService;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorReport;
import fr.ses10doigts.tradeIO5.service.doctor.DoctorService;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Docteur (Administration) : rapport des contrôles, relance d'une passe, alarmes (liste, acquittement, mise en sommeil) et
 * journal des jobs. Observateur : aucune route n'agit sur le système ; acquitter / endormir est tracé au journal admin.
 */
@RestController
@RequestMapping("/api/admin/doctor")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DoctorAdminController {

    /** Pagination du journal des jobs : taille maximale acceptée. */
    private static final int MAX_PAGE_SIZE = 100;

    private final DoctorService doctor;
    private final AlarmService alarms;
    private final JobRunRepository jobRuns;
    private final AdminActionLogService journal;
    private final IAuthenticationFacade authenticationFacade;

    @GetMapping
    public DoctorDtos.Report report() {
        return toDto(doctor.report());
    }

    /** Relance une passe immédiate (lecture seule) et rend le nouveau rapport. */
    @PostMapping("/run")
    public DoctorDtos.Report run() {
        return toDto(doctor.runAll());
    }

    /** Alarmes ; {@code state} absent = ouvertes (ACTIVE + ACKED), {@code ALL} = historique complet. */
    @GetMapping("/alarms")
    public List<DoctorDtos.AlarmView> alarms(@RequestParam(required = false) String state) {
        Set<AlarmState> states;
        if (state == null || state.isBlank()) {
            states = EnumSet.of(AlarmState.ACTIVE, AlarmState.ACKED);
        } else if ("ALL".equalsIgnoreCase(state)) {
            states = Set.of();
        } else {
            states = EnumSet.of(AlarmState.valueOf(state.toUpperCase()));
        }
        return alarms.list(states).stream().map(DoctorDtos.AlarmView::of).toList();
    }

    @PostMapping("/alarms/{id}/ack")
    public DoctorDtos.AlarmView ack(@PathVariable Long id, @RequestBody(required = false) DoctorDtos.AckRequest request) {
        String comment = request == null ? null : request.comment();
        Alarm alarm = alarms.acknowledge(id, actor(), comment);
        journal.record(actor(), AdminAction.DOCTOR, "alarm:" + id,
                AdminActionLogService.payload("action", "ack", "key", alarm.getAlarmKey()), AdminActionOutcome.OK);
        return DoctorDtos.AlarmView.of(alarm);
    }

    @PostMapping("/alarms/{id}/snooze")
    public DoctorDtos.AlarmView snooze(@PathVariable Long id, @RequestBody DoctorDtos.SnoozeRequest request) {
        Alarm alarm = alarms.snooze(id, actor(), request.hours(), request.comment());
        journal.record(actor(), AdminAction.DOCTOR, "alarm:" + id,
                AdminActionLogService.payload("action", "snooze", "hours", request.hours(), "key", alarm.getAlarmKey()),
                AdminActionOutcome.OK);
        return DoctorDtos.AlarmView.of(alarm);
    }

    /** Journal des exécutions de jobs, plus récentes d'abord. */
    @GetMapping("/jobs")
    public DoctorDtos.JobRunPage jobs(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "" + ExecutionDefaults.DEFAULT_PAGE_SIZE) int size) {
        return DoctorDtos.JobRunPage.of(jobRuns.findAllByOrderByStartedAtDesc(
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE))));
    }

    private DoctorDtos.Report toDto(DoctorReport report) {
        return new DoctorDtos.Report(report.runAt(), report.worst(), alarms.countOpenBySeverity(DoctorStatus.CRIT),
                alarms.countOpenBySeverity(DoctorStatus.WARN), report.results());
    }

    private String actor() {
        return authenticationFacade.getConnectedUser().getUsername();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<RainbowLiveControllerAdvice.ErrorResponse> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new RainbowLiveControllerAdvice.ErrorResponse(e.getMessage()));
    }
}
