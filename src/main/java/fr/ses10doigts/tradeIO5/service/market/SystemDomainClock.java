package fr.ses10doigts.tradeIO5.service.market;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class SystemDomainClock implements DomainClock {
    @Override
    public Instant now() {
        return Instant.now();
    }
}