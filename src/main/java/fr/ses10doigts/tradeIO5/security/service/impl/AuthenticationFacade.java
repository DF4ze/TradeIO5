package fr.ses10doigts.tradeIO5.security.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.repository.UserRepository;
import fr.ses10doigts.tradeIO5.security.service.IAuthenticationFacade;

@Component
public class AuthenticationFacade implements IAuthenticationFacade {

    @Autowired
    UserRepository userRepository;

    @Override
    public Authentication getAuthentication() {
	return SecurityContextHolder.getContext().getAuthentication();
    }

    @Override
    public User getConnectedUser() {
	String sUser = getAuthentication().getName();
	User user = userRepository.findByUsername(sUser)
		.orElseThrow(() -> new RuntimeException("Error: Connected user not find in DB."));

	return user;
    }
}