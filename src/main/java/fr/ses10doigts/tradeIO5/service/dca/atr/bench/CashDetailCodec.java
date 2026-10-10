package fr.ses10doigts.tradeIO5.service.dca.atr.bench;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** (Dé)sérialise le détail du cash par membre de groupe (ex : {@code {"USDC":120.0,"USDT":30.0}}) stocké en JSON. */
final class CashDetailCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Double>> TYPE = new TypeReference<>() {
    };

    private CashDetailCodec() {
    }

    static String write(Map<String, Double> detail) {
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Détail du cash non sérialisable", e);
        }
    }

    /** Vide si le JSON est absent ou illisible (anciens snapshots). */
    static Map<String, Double> read(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
