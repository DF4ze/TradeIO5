package fr.ses10doigts.tradeIO5.service.admin;

/** Ressource d'administration inconnue (credential, plan...) : mappée en 404. */
public class AdminResourceNotFoundException extends RuntimeException {

    public AdminResourceNotFoundException(String message) {
        super(message);
    }
}
