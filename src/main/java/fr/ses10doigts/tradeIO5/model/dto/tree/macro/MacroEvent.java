package fr.ses10doigts.tradeIO5.model.dto.tree.macro;

import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventImpact;
import fr.ses10doigts.tradeIO5.model.enumerate.tree.macro.MacroEventSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Événement de calendrier macro normalisé (étude "indicateurs-macro-externes" §14 item G), commun
 * aux deux sources ({@link MacroEventSource#FINNHUB}/{@link MacroEventSource#FOREXFACTORY}).
 * <p>
 * {@code forecast}/{@code previous}/{@code actual} restent en {@link String} volontairement : leur
 * format varie trop selon l'événement ({@code "54.2"}, {@code "-78.3B"}, {@code "2.50%"}) pour être
 * typés numériquement sans une logique de parsing par unité — hors scope de ce lot (cf. prompt
 * d'implémentation, item G, §"À faire" point 2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MacroEvent {

    private String title;
    private String country;
    private Instant dateTime;
    private MacroEventImpact impact;
    private MacroEventSource source;

    private String forecast;
    private String previous;
    private String actual;
}
