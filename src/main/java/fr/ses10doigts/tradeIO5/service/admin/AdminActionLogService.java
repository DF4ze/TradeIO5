package fr.ses10doigts.tradeIO5.service.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.dto.admin.AdminActionDto;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminAction;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionLog;
import fr.ses10doigts.tradeIO5.model.entity.admin.AdminActionOutcome;
import fr.ses10doigts.tradeIO5.repository.admin.AdminActionLogRepository;
import fr.ses10doigts.tradeIO5.service.execution.run.OrderEventLog;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import fr.ses10doigts.tradeIO5.service.market.instrument.ExecutionDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Journal des actions admin (append-only). Chaque écriture a sa propre transaction : elle survit à l'échec de l'action
 * qu'elle trace, et un échec d'écriture ne casse jamais l'action (WARN seulement). Le payload est un JSON texte choisi
 * par l'appelant ; par sécurité toute clé qui ressemble à un secret est écartée.
 */
@Slf4j
@Service
public class AdminActionLogService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SECRET_KEY = Pattern.compile("(?i).*(secret|passphrase|apikey|api_key|password|token).*");

    private final AdminActionLogRepository repository;
    private final DomainClock clock;
    private final TransactionTemplate independentTx;

    public AdminActionLogService(AdminActionLogRepository repository, DomainClock clock, PlatformTransactionManager tm) {
        this.repository = repository;
        this.clock = clock;
        this.independentTx = new TransactionTemplate(tm);
        this.independentTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Payload ordonné à partir de paires clé, valeur, clé, valeur... (valeurs nulles omises). */
    public static Map<String, Object> payload(Object... keyValues) {
        return OrderEventLog.payload(keyValues);
    }

    public void record(String actor, AdminAction action, String target, Map<String, Object> payload, AdminActionOutcome outcome) {
        try {
            AdminActionLog entry = AdminActionLog.builder().actor(cut(actor, AdminActionLog.ACTOR_MAX)).action(action)
                    .target(cut(target, AdminActionLog.TARGET_MAX)).payload(render(payload)).outcome(outcome)
                    .createdAt(clock.now()).build();
            independentTx.executeWithoutResult(status -> repository.save(entry));
            log.info("Action admin {} {} par {} -> {}", action, target, actor, outcome);
        } catch (RuntimeException e) {
            log.warn("Journal admin : écriture impossible ({} {} par {}) : {}", action, target, actor, e.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public AdminActionDto.Page page(int page, int size) {
        int safeSize = Math.max(1, Math.min(size, ExecutionDefaults.MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Page<AdminActionLog> result = repository.findAllByOrderByIdDesc(PageRequest.of(safePage, safeSize));
        return new AdminActionDto.Page(safePage, safeSize, result.getTotalElements(), result.getContent().stream()
                .map(e -> new AdminActionDto(e.getId(), e.getActor(), e.getAction().name(), e.getTarget(), e.getPayload(),
                        e.getOutcome().name(), e.getCreatedAt())).toList());
    }

    private static String render(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        payload.forEach((k, v) -> {
            if (!SECRET_KEY.matcher(k).matches()) {
                safe.put(k, v);
            }
        });
        try {
            return cut(MAPPER.writeValueAsString(safe), AdminActionLog.PAYLOAD_MAX);
        } catch (JsonProcessingException e) {
            return "payload non sérialisable";
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
