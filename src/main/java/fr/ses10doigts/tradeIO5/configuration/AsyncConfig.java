package fr.ses10doigts.tradeIO5.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Active {@code @Async} (import des transactions déclenché hors du thread de la requête). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
