package fr.ses10doigts.tradeIO5.service.expert.simulation;

import fr.ses10doigts.tradeIO5.service.dca.atr.RainbowAtrDataset;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Repérage des jours (UTC) dans un dataset D1, partagé par le rejeu de parité, le rejeu libre et {@link LinkSignalReplay}. */
public final class ReplayDays {

    private ReplayDays() {
    }

    public static LocalDate dayOf(long millis) {
        return Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate();
    }

    /** Index de la bougie du jour {@code day} ; -1 si absente. */
    public static int indexOfDay(RainbowAtrDataset ds, LocalDate day) {
        for (int i = 0; i < ds.size(); i++) {
            if (dayOf(ds.time(i)).equals(day)) {
                return i;
            }
        }
        return -1;
    }

    /** Index de la dernière bougie d'un jour {@code <= day} ; -1 si aucune. */
    public static int lastIndexOnOrBefore(RainbowAtrDataset ds, LocalDate day) {
        int last = -1;
        for (int i = 0; i < ds.size(); i++) {
            if (!dayOf(ds.time(i)).isAfter(day)) {
                last = i;
            }
        }
        return last;
    }

    /** Index de la 1re bougie d'un jour {@code >= from} ; {@code ds.size()} si aucune. */
    public static int firstIndexOnOrAfter(RainbowAtrDataset ds, LocalDate from) {
        long fromMillis = from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        for (int i = 0; i < ds.size(); i++) {
            if (ds.time(i) >= fromMillis) {
                return i;
            }
        }
        return ds.size();
    }
}
