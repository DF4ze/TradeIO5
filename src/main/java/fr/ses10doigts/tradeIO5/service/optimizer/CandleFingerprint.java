package fr.ses10doigts.tradeIO5.service.optimizer;

import fr.ses10doigts.tradeIO5.model.dto.market.MarketData;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

/** Empreinte des bougies utilisées pour un actif : premier / dernier jour, nombre, somme de contrôle (SHA-256 tronqué). */
public record CandleFingerprint(String asset, LocalDate firstDay, LocalDate lastDay, int count, String checksum) {

    private static final int CHECKSUM_BYTES = 8;

    /** Empreinte des bougies dont le jour (UTC) est {@code <= lastDay}. */
    public static CandleFingerprint of(String asset, List<MarketData> candles, LocalDate lastDay) {
        MessageDigest digest = sha256();
        LocalDate first = null;
        LocalDate last = null;
        int count = 0;
        ByteBuffer buffer = ByteBuffer.allocate(5 * Long.BYTES);
        for (MarketData c : candles) {
            LocalDate day = c.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate();
            if (day.isAfter(lastDay)) {
                continue;
            }
            buffer.clear();
            buffer.putLong(c.getTimestamp().toEpochMilli()).putDouble(c.getOpen().doubleValue()).putDouble(c.getHigh().doubleValue())
                    .putDouble(c.getLow().doubleValue()).putDouble(c.getClose().doubleValue());
            digest.update(buffer.array());
            first = first == null ? day : first;
            last = day;
            count++;
        }
        String checksum = HexFormat.of().formatHex(digest.digest(), 0, CHECKSUM_BYTES);
        return new CandleFingerprint(asset, first, last, count, checksum);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
