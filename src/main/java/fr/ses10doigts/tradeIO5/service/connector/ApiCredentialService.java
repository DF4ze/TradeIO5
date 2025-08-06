package fr.ses10doigts.tradeIO5.service.connector;


import java.util.List;

import org.springframework.stereotype.Service;

import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import fr.ses10doigts.tradeIO5.security.service.UserDetailsServiceImpl;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ApiCredentialService {

    private final ApiCredentialRepository apiCredentialRepository;
    private final UserDetailsServiceImpl userDetailsService;

    public ApiCredential getCredentialForCurrentUser(String exchangeCode) {
        User user = userDetailsService.getCurrentUser();
		return apiCredentialRepository.findByUserAndExchange_CodeAndEnabledTrue(user, exchangeCode)
            .orElseThrow(() -> new RuntimeException(
                "No API credential found for user " + user.getUsername() + " and exchange " + exchangeCode));
    }

	public List<ApiCredential> getAllCredentialsForCurrentUser() {
		User user = userDetailsService.getCurrentUser();
		return apiCredentialRepository.findByUserAndEnabledTrue(user);
	}

	public List<ApiCredential> getAllCredentials() {
		return apiCredentialRepository.findByEnabledTrue();
	}
}