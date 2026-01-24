package fr.ses10doigts.tradeIO5.model.enumerate.market;

import lombok.Getter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Getter
public enum TimeFrame {

    // ===== CALENDAR =====
    Y1(TimeFrameType.CALENDAR, ChronoUnit.YEARS, 1),
    Y3(TimeFrameType.CALENDAR, ChronoUnit.YEARS, 3),

    M1(TimeFrameType.CALENDAR, ChronoUnit.MONTHS, 1),
    M2(TimeFrameType.CALENDAR, ChronoUnit.MONTHS, 2),
    M3(TimeFrameType.CALENDAR, ChronoUnit.MONTHS, 3),
    M6(TimeFrameType.CALENDAR, ChronoUnit.MONTHS, 6),

    W1(TimeFrameType.CALENDAR, ChronoUnit.WEEKS, 1),
    W2(TimeFrameType.CALENDAR, ChronoUnit.WEEKS, 2),

    // ===== FIXED =====
    D1(TimeFrameType.FIXED, ChronoUnit.DAYS, 1),
    H1(TimeFrameType.FIXED, ChronoUnit.HOURS, 1),
    H4(TimeFrameType.FIXED, ChronoUnit.HOURS, 4),
    H12(TimeFrameType.FIXED, ChronoUnit.HOURS, 12),

    MIN1(TimeFrameType.FIXED, ChronoUnit.MINUTES, 1),
    MIN5(TimeFrameType.FIXED, ChronoUnit.MINUTES, 5);

    private final TimeFrameType type;
    private final ChronoUnit unit;
    private final int amount;

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");



    TimeFrame(TimeFrameType type, ChronoUnit unit, int amount) {
        this.type = type;
        this.unit = unit;
        this.amount = amount;
    }

    public boolean isFixed(){
        return type == TimeFrameType.FIXED;
    }

    public boolean isCalendar(){
        return type == TimeFrameType.CALENDAR;
    }

    public boolean isGreaterOrEqualThan(TimeFrame other) {
        if (other == null) return false;

        int thisRank = unitRank(this.unit);
        int otherRank = unitRank(other.unit);

        if (thisRank != otherRank) {
            return thisRank > otherRank;
        }

        // même unité → comparaison du multiplicateur
        return this.amount >= other.amount;
    }

    public Instant addTo(Instant instant, long quantity) {
        return instant
                .atZone(DEFAULT_ZONE)
                .plus(amount*quantity, unit)
                .toInstant();
    }

    public Instant removeTo(Instant instant, long quantity) {
        return instant
                .atZone(DEFAULT_ZONE)
                .minus(amount*quantity, unit)
                .toInstant();
    }

    public Instant addTo(Instant instant) {
        return addTo(instant, 1);
    }

    public Instant removeTo(Instant instant) {
        return addTo(instant, 1);
    }

    private static int unitRank(ChronoUnit unit) {
        return switch (unit) {
            case MINUTES -> 1;
            case HOURS   -> 2;
            case DAYS    -> 3;
            case WEEKS   -> 4;
            case MONTHS  -> 5;
            case YEARS   -> 6;
            default -> throw new IllegalArgumentException("Unsupported unit: " + unit);
        };
    }
}

