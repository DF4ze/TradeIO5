package fr.ses10doigts.tradeIO5.service.expert;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Réglages d'un preset à plat (nom du paramètre -> valeur) pour détecter et résumer ce qui change. */
final class PresetSettings {

    private PresetSettings() {
    }

    /** Ajoute les composants du record, préfixés. */
    static void flatten(Map<String, Object> out, String prefix, Record record) {
        try {
            for (RecordComponent rc : record.getClass().getRecordComponents()) {
                out.put(prefix + rc.getName(), rc.getAccessor().invoke(record));
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Paramètres dont la valeur diffère : nom -> « avant → après ». */
    static Map<String, String> changes(Map<String, Object> before, Map<String, Object> after) {
        Map<String, String> changes = new LinkedHashMap<>();
        after.forEach((k, v) -> {
            if (!Objects.equals(before.get(k), v)) {
                changes.put(k, before.get(k) + " → " + v);
            }
        });
        return changes;
    }
}
