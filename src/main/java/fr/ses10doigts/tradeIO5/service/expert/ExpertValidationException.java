package fr.ses10doigts.tradeIO5.service.expert;

import lombok.Getter;

import java.util.Map;

/** Réglages invalides : un message par champ (400). */
@Getter
public class ExpertValidationException extends IllegalArgumentException {

    private final transient Map<String, String> fields;

    public ExpertValidationException(Map<String, String> fields) {
        super("Réglages invalides : " + String.join(" ; ", fields.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList()));
        this.fields = fields;
    }

    public ExpertValidationException(String field, String message) {
        this(Map.of(field, message));
    }
}
