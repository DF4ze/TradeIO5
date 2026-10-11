package fr.ses10doigts.tradeIO5.service.execution.plan;

import fr.ses10doigts.tradeIO5.model.entity.dca.bench.RainbowLivePass;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Identifiant client déterministe d'une étape : {@code t5} + longueur et valeur du userId (base 36) + longueur et valeur du
 * walletId (base 36) + actif + {@code yyyyMMdd} + passe (1 = 23:55, 2 = 00:05) + rang sur 2 chiffres. Les longueurs lèvent
 * toute ambiguïté entre userId et walletId. Alphanumérique, 32 caractères maximum (limite OKX) ; mêmes entrées => même
 * identifiant ; deux wallets du même actif => identifiants distincts.
 */
public final class ClOrdIds {

    public static final int MAX_LENGTH = 32;
    private static final String PREFIX = "t5";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private ClOrdIds() {
    }

    public static String of(long userId, long walletId, String assetSymbol, LocalDate day, RainbowLivePass pass, int rank) {
        String id = PREFIX + base36(userId) + base36(walletId) + assetSymbol.toLowerCase() + DAY.format(day)
                + (pass == RainbowLivePass.T2355 ? 1 : 2) + String.format("%02d", rank);
        if (id.length() > MAX_LENGTH || !id.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128)) {
            throw new IllegalStateException("clOrdId invalide : " + id);
        }
        return id;
    }

    /** Longueur (1 caractère base 36) puis valeur en base 36. */
    private static String base36(long value) {
        String digits = Long.toString(value, 36);
        return Integer.toString(digits.length(), 36) + digits;
    }
}
