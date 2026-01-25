package fr.ses10doigts.tradeIO5.service.market;

import java.time.Duration;
import java.time.Instant;


public class FixedDomainClock implements DomainClock {

    private Instant current;

    public FixedDomainClock(Instant start) {
        this.current = start;
    }

    @Override
    public Instant now() {
        return current;
    }

    public void set(Instant instant) {
        this.current = instant;
    }

    public void advance(Duration duration) {
        this.current = this.current.plus(duration);
    }
}