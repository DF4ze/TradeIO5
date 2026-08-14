package fr.ses10doigts.tradeIO5.service.market;

import java.time.Instant;

public interface DomainClock {
    Instant now();
}