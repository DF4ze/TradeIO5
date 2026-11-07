package fr.ses10doigts.tradeIO5.service.expert;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Erreur métier de l'API Espace Expert : statut HTTP + code stable exploitable par les écrans. */
@Getter
public class ExpertApiException extends RuntimeException {

    public enum Code {
        EXPERT_MODE_REQUIRED(HttpStatus.FORBIDDEN),
        /** Consultation réservée à l'administrateur (impact d'un preset SYSTEM). */
        ADMIN_REQUIRED(HttpStatus.FORBIDDEN),
        /** Preset SYSTEM modifié par un non-admin : l'écran invite à le dupliquer. */
        PRESET_SYSTEM_READ_ONLY(HttpStatus.FORBIDDEN),
        PRESET_SYSTEM_UNDELETABLE(HttpStatus.FORBIDDEN),
        PRESET_NOT_FOUND(HttpStatus.NOT_FOUND),
        PRESET_REVISION_CONFLICT(HttpStatus.CONFLICT),
        PRESET_IN_USE(HttpStatus.CONFLICT),
        /** Simulation inexistante, expirée ou d'un autre utilisateur. */
        SIMULATION_NOT_FOUND(HttpStatus.NOT_FOUND),
        SIMULATION_ALREADY_RUNNING(HttpStatus.CONFLICT),
        SIMULATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
        /** File d'attente des simulations saturée. */
        SIMULATION_BUSY(HttpStatus.TOO_MANY_REQUESTS),
        /** Historique insuffisant pour les réglages demandés (points requis / disponibles dans les détails). */
        ELIGIBILITY_INSUFFICIENT(HttpStatus.UNPROCESSABLE_ENTITY);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final Code code;
    private final transient Object details;

    public ExpertApiException(Code code, String message) {
        this(code, message, null);
    }

    public ExpertApiException(Code code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }
}
