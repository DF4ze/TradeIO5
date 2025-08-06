package fr.ses10doigts.tradeIO5.security.service;

import org.springframework.security.core.Authentication;

import fr.ses10doigts.tradeIO5.security.model.User;

public interface IAuthenticationFacade {
    Authentication getAuthentication();

    User getConnectedUser();
}
