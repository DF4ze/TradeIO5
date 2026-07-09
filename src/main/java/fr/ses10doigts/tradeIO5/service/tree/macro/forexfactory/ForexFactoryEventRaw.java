package fr.ses10doigts.tradeIO5.service.tree.macro.forexfactory;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO de désérialisation brute {@code GET https://nfs.faireconomy.media/ff_calendar_thisweek.json}
 * (format confirmé, cf. prompt d'implémentation Lot 2, item G) :
 * {@code [{ "title", "country", "date", "impact", "forecast", "previous" }]}. {@code date} est une
 * chaîne ISO-8601 <b>avec offset</b> (ex. {@code "2026-07-06T10:00:00-04:00"}), pas UTC directement
 * — conversion faite dans {@link ForexFactoryCalendarClient toMacroEvent}.
 */
@Data
@NoArgsConstructor
public class ForexFactoryEventRaw {
    private String title;
    private String country;
    private String date;
    private String impact;
    private String forecast;
    private String previous;
}
