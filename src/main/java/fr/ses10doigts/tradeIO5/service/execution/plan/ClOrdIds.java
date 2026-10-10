package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Identifiant client déterministe d'une étape : {@code t5} + userId (base 36) + actif + {@code yyyyMMdd} + passe (1 = 23:55,
 * 2 = 00:05) + rang sur 2 chiffres. Alphanumérique, 32 caractères maximum (limite OKX) ; mêmes entrées => même identifiant.
 */
public final class ClOrdIds {

    public static final int MAX_LENGTH = 32;
    private static final String PREFIX = "t5";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private ClOrdIds() {
    }

    public static String of(long userId, String assetSymbol, LocalDate day, RainbowLivePass pass, int rank) {
        String id = PREFIX + Long.toString(userId, 36) + assetSymbol.toLowerCase() + DAY.format(day)
                + (pass == RainbowLivePass.T2355 ? 1 : 2) + String.format("%02d", rank);
        if (id.length() > MAX_LENGTH || !id.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128)) {
            throw new IllegalStateException("clOrdId invalide : " + id);
        }
        return id;
    }
}
