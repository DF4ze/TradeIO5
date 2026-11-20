package fr.ses10doigts.tradeIO5.service.optimizer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Blocs de validation de l'étape B : {@code count} blocs de {@code months} mois, contigus et NON chevauchants, le plus récent finissant à {@code lastEnd}. */
public final class TuneBlocks {

    /** {@code index} 0 = bloc le plus récent ; {@code from} et {@code to} inclus. */
    public record Block(int index, LocalDate from, LocalDate to) {
        public String label() {
            return from + " → " + to;
        }
    }

    private TuneBlocks() {
    }

    public static List<Block> build(LocalDate lastEnd, int months, int count) {
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate to = lastEnd.minusMonths((long) months * i);
            out.add(new Block(i, to.minusMonths(months).plusDays(1), to));
        }
        return List.copyOf(out);
    }
}
