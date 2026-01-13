package fr.ses10doigts.tradeIO5.service.connector;


import fr.ses10doigts.tradeIO5.exceptions.NotFoundException;
import fr.ses10doigts.tradeIO5.model.entity.exchange.ApiCredential;
import fr.ses10doigts.tradeIO5.repository.ApiCredentialRepository;
import fr.ses10doigts.tradeIO5.security.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ApiCredentialService {

    private final ApiCredentialRepository apiCredentialRepository;

	public ApiCredential getFromExchangeAndUser(String exchangeCode, User user) throws NotFoundException {
		return apiCredentialRepository.findByUserAndEnabledTrueAndWebProvider_CodeAndWebProvider_EnabledTrue(user, exchangeCode)
            .orElseThrow(() -> new NotFoundException(
                "No enabled API credential found for user " + user.getUsername() + " and exchange " + exchangeCode));
    }


}