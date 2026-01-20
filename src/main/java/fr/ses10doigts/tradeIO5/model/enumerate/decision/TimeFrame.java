package fr.ses10doigts.tradeIO5.model.enumerate.decision;

import lombok.Getter;

import java.time.Duration;

@Getter
public enum TimeFrame {
    Y1(365*24*60*60), Y3(3*365*24*60*60),
    M1(30*24*60*60), M2(2* 30*24*60*60), M3(3* 30*24*60*60), M6(6* 30*24*60*60),
    W1(7* 24*60*60), W2(2*7* 24*60*60),
    D1(24*60*60),
    H1(60*60), H4(4*60*60), H12(12*60*60),
    MIN1(60), MIN5(5*60) ;

    private final long nbSeconds;

    TimeFrame( long sec ){
        nbSeconds = sec;
    }

    public Duration getDuration() {
        return Duration.ofSeconds(nbSeconds);
    }

    public boolean isMultipleOf(TimeFrame other) {
        if (other == null || other.nbSeconds == 0) return false;
        return this.nbSeconds % other.nbSeconds == 0;
    }

}
