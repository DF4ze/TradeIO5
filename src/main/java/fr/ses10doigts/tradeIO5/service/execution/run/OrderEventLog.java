package fr.ses10doigts.tradeIO5.service.execution.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ses10doigts.tradeIO5.model.entity.execution.OrderEventType;
import fr.ses10doigts.tradeIO5.model.entity.execution.RainbowLiveOrderEvent;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderEventRepository;
import fr.ses10doigts.tradeIO5.repository.execution.RainbowLiveOrderPlanRepository;
import fr.ses10doigts.tradeIO5.service.market.DomainClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Écriture du journal d'audit (append-only). Chaque événement est écrit dans sa propre transaction : il survit à l'échec de
 * l'étape qui le suit. Le payload ne contient que des couples clé=valeur choisis par l'appelant (jamais un secret, jamais une
 * réponse brute de l'exchange) ; tronqué à {@link RainbowLiveOrderEvent#PAYLOAD_MAX} caractères.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RainbowLiveOrderEventRepository repository;
    private final RainbowLiveOrderPlanRepository planRepository;
    private final DomainClock clock;

    /** Payload ordonné à partir de paires clé, valeur, clé, valeur... (valeurs nulles omises). */
    public static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1] instanceof java.math.BigDecimal d
                        ? d.stripTrailingZeros().toPlainString() : keyValues[i + 1]);
            }
        }
        return map;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long planId, Long stepId, String clOrdId, OrderEventType type, Map<String, Object> payload) {
        String text = render(payload);
        repository.save(RainbowLiveOrderEvent.builder().plan(planRepository.getReferenceById(planId)).stepId(stepId)
                .clOrdId(clOrdId).type(type).payload(text).createdAt(clock.now()).build());
        log.debug("Audit plan={} step={} {} {}", planId, stepId, type, text);
    }

    private static String render(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            String text = MAPPER.writeValueAsString(payload);
            return text.length() > RainbowLiveOrderEvent.PAYLOAD_MAX ? text.substring(0, RainbowLiveOrderEvent.PAYLOAD_MAX) : text;
        } catch (JsonProcessingException e) {
            return "payload non sérialisable";
        }
    }
}
