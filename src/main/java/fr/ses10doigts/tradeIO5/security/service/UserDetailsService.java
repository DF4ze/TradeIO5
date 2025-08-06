package fr.ses10doigts.tradeIO5.security.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import fr.ses10doigts.tradeIO5.security.model.User;

public interface UserDetailsService extends org.springframework.security.core.userdetails.UserDetailsService {
	@Override
	UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;

	public User getCurrentUser();
}