package fr.ses10doigts.tradeIO5.service.connector;


import java.util.List;

import fr.ses10doigts.tradeIO5.exceptions.NotFoundException;
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

	public ApiCredential getFromCurrentUser(String exchangeCode) throws NotFoundException {
		User user = userDetailsService.getCurrentUser();
		return getFromExchangeAndUser(exchangeCode, user);
	}

/*	public ApiCredential getFromWallet( Wallet wallet ) throws NotFoundException {
		return getFromExchangeAndUser(wallet.getProviderCode(), wallet.getUser());
	}
*/
	public ApiCredential getFromExchangeAndUser(String exchangeCode, User user) throws NotFoundException {
		return apiCredentialRepository.findByUserAndEnabledTrueAndProvider_CodeAndProvider_EnabledTrue(user, exchangeCode)
            .orElseThrow(() -> new NotFoundException(
                "No enabled API credential found for user " + user.getUsername() + " and exchange " + exchangeCode));
    }

	public List<ApiCredential> getAllCredentialsForCurrentUser() {
		User user = userDetailsService.getCurrentUser();
		return apiCredentialRepository.findByUserAndEnabledTrue(user);
	}

}