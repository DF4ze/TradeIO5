package fr.ses10doigts.tradeIO5.service.doctor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tampon mémoire des logs WARN / ERROR (borné à {@link DoctorDefaults#LOG_BUFFER_MAX}) alimenté par un appender logback
 * greffé sur le logger racine au démarrage. Sert au comptage « erreurs / warnings des dernières 24 h » du Docteur.
 * <b>Limite assumée</b> : le tampon est vidé à chaque redémarrage (les fichiers de logs restent la source complète).
 */
@Slf4j
@Component
public class DoctorLogBuffer {

    /** Un événement de log retenu. */
    public record Entry(Instant at, Level level, String logger, String message) {
    }

    private static final String APPENDER_NAME = "DOCTOR_LOG_BUFFER";

    private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();
    private final Instant startedAt = Instant.now();
    private AppenderBase<ILoggingEvent> appender;

    @PostConstruct
    void install() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            log.warn("DoctorLogBuffer : logback absent, comptage des logs indisponible");
            return;
        }
        appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                    record(new Entry(Instant.ofEpochMilli(event.getTimeStamp()), event.getLevel(), event.getLoggerName(),
                            cut(event.getFormattedMessage())));
                }
            }
        };
        appender.setContext(context);
        appender.setName(APPENDER_NAME);
        appender.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @PreDestroy
    void uninstall() {
        if (appender != null && LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
        }
    }

    /** Enregistre une entrée (public pour les tests). */
    public void record(Entry entry) {
        entries.addLast(entry);
        while (entries.size() > DoctorDefaults.LOG_BUFFER_MAX) {
            entries.pollFirst();
        }
    }

    /** Entrées d'un niveau depuis {@code since}, de la plus ancienne à la plus récente. */
    public List<Entry> since(Instant since, Level level) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            if (e.level() == level && !e.at().isBefore(since)) {
                result.add(e);
            }
        }
        return result;
    }

    /** Démarrage du tampon (borne basse de ce qu'il peut avoir vu). */
    public Instant startedAt() {
        return startedAt;
    }

    private static String cut(String message) {
        return message == null || message.length() <= DoctorDefaults.LOG_MESSAGE_MAX ? message
                : message.substring(0, DoctorDefaults.LOG_MESSAGE_MAX);
    }
}
